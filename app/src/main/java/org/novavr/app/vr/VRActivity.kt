/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.novavr.app.novaApp
import org.novavr.app.settings.VRSettingsRepository
import org.novavr.app.ui.VROverlay
import org.novavr.app.ui.theme.NovaVRTheme
import org.novavr.app.utils.DeviceInfo
import org.novavr.cardboard.CardboardNative

/**
 * Full-screen stereoscopic VR session.
 *
 * Lifecycle (all resources are bound to this activity):
 *  - onCreate:  initialise Cardboard SDK (once per process), create head tracker,
 *               GL surface and renderer.
 *  - onResume:  resume GL thread and head-tracker sensors.
 *  - onPause:   release GL resources on the GL thread, pause GL thread, pause
 *               sensors. Nothing renders or samples sensors in the background.
 *  - onDestroy: destroy head tracker.
 */
class VRActivity : ComponentActivity() {

    private lateinit var settingsRepository: VRSettingsRepository
    private lateinit var headTracker: HeadTracker
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VRRenderer

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fps by mutableFloatStateOf(0f)
    private var errorMessage by mutableStateOf<String?>(null)
    private var portraitSurface by mutableStateOf(false)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) onDisplayUpdated()
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = novaApp.settings
        val calibrationMode = intent.getBooleanExtra(EXTRA_CALIBRATION, false)
        val deviceInfo = DeviceInfo.query(this)

        CardboardNative.initialize(this)
        headTracker = HeadTracker()

        configureWindow()

        renderer = VRRenderer(
            headTracker = headTracker,
            deviceInfo = deviceInfo,
            initialSettings = settingsRepository.current,
            calibrationMode = calibrationMode,
            listener = object : VRRenderer.Listener {
                override fun onFrameStats(fps: Float) {
                    mainHandler.post { this@VRActivity.fps = fps }
                }

                override fun onRendererError(message: String) {
                    mainHandler.post { errorMessage = message }
                }

                override fun onPortraitSurface(portrait: Boolean) {
                    mainHandler.post { portraitSurface = portrait }
                }
            },
        )

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            // RGB888, no depth/stencil: depth lives in the offscreen eye buffer.
            setEGLConfigChooser(8, 8, 8, 0, 0, 0)
            preserveEGLContextOnPause = false
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        setContent {
            NovaVRTheme {
                VROverlay(
                    glView = glView,
                    calibrationMode = calibrationMode,
                    fps = fps,
                    errorMessage = errorMessage ?: if (portraitSurface) {
                        "Rotate the device to landscape to use the headset."
                    } else {
                        null
                    },
                    onExit = ::finish,
                    onToggleMode = ::toggleDisplayMode,
                    onRecenter = headTracker::recenter,
                )
            }
        }

        // Settings can change from the overlay (mode toggle); forward them to
        // the renderer only while visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.settings.collect { settings ->
                    renderer.updateSettings(settings)
                    applyRefreshRatePreference(settings.preferHighRefreshRate)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, mainHandler)
        onDisplayUpdated()
        headTracker.resume()
        glView.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        // Free GL objects while the context is still current; the queued event
        // runs before the GL thread processes the pause request.
        glView.queueEvent { renderer.releaseGl() }
        glView.onPause()
        headTracker.pause()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        headTracker.close()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun toggleDisplayMode() {
        settingsRepository.update { it.copy(displayMode = it.displayMode.toggled()) }
    }

    private fun onDisplayUpdated() {
        val display = currentDisplay() ?: return
        headTracker.onDisplayRotationChanged(display.rotation)
        renderer.setRefreshRate(display.refreshRate)
    }

    private fun configureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The rendered image must cover the whole physical panel for the lens
        // distortion to line up, so draw under the camera cutout.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }
        // Keeps clocks stable over long sessions instead of thermal throttling
        // in bursts, which would show up as judder.
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power.isSustainedPerformanceModeSupported) window.setSustainedPerformanceMode(true)
    }

    /**
     * Picks the display mode with the highest (or a 60 Hz class) refresh rate
     * at the current resolution. Uses only the modes the device reports, so it
     * works on any panel (60/90/120/144 Hz...).
     */
    private fun applyRefreshRatePreference(preferHigh: Boolean) {
        val display = currentDisplay() ?: return
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val target = if (preferHigh) {
            candidates.maxByOrNull { it.refreshRate }
        } else {
            candidates.minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
        } ?: return
        if (window.attributes.preferredDisplayModeId != target.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = target.modeId }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) display
        else windowManager.defaultDisplay

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_CALIBRATION = "org.novavr.app.extra.CALIBRATION"

        fun intent(context: Context, calibration: Boolean = false): Intent =
            Intent(context, VRActivity::class.java).putExtra(EXTRA_CALIBRATION, calibration)
    }
}
