/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.novavr.app.mirror.MirrorLauncherDialogs
import org.novavr.app.mirror.ScreenMirrorService
import org.novavr.app.mirror.rememberMirrorLauncher
import org.novavr.app.novaApp
import org.novavr.app.ui.VRControls
import org.novavr.app.ui.VROverlay
import org.novavr.app.ui.theme.NovaVRTheme

/**
 * Full-screen VR showing the calibration test pattern (and, in calibration
 * mode, per-eye markers). Screen mirroring runs in an overlay window owned by
 * [ScreenMirrorService]; this activity can start it and then gets out of the
 * way, because an activity covering the shared app would stop it rendering.
 *
 * Lifecycle: the [VRSession] is resumed in onResume and paused in onPause
 * (GL released, sensors off), destroyed in onDestroy.
 */
class VRActivity : ComponentActivity() {

    private lateinit var session: VRSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val calibrationMode = intent.getBooleanExtra(EXTRA_CALIBRATION, false)
        val repository = novaApp.settings
        configureWindow()

        session = VRSession(
            context = this,
            settingsRepository = repository,
            calibrationMode = calibrationMode,
            mirrorSource = null,
            displayProvider = ::currentDisplay,
            onPreferredDisplayMode = { modeId ->
                if (window.attributes.preferredDisplayModeId != modeId) {
                    window.attributes = window.attributes.apply { preferredDisplayModeId = modeId }
                }
            },
        )

        setContent {
            val settings by repository.settings.collectAsStateWithLifecycle()
            // Starting mirroring hands VR over to the overlay window.
            val mirrorLauncher = rememberMirrorLauncher(onStarted = ::finish)
            NovaVRTheme {
                VROverlay(
                    glView = session.glView,
                    state = session.state,
                    settings = settings,
                    calibrationMode = calibrationMode,
                    mirror = null,
                    controls = VRControls(
                        onExit = ::finish,
                        exitDescription = "Exit VR",
                        onToggleMode = session::toggleDisplayMode,
                        onRecenter = session::recenter,
                        onAdjustSize = session::adjustScreenSize,
                        onAdjustDistance = session::adjustScreenDistance,
                        onStartMirroring = if (ScreenMirrorService.isSupported) mirrorLauncher::start else null,
                    ),
                )
                MirrorLauncherDialogs(mirrorLauncher)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        session.resume()
        hideSystemBars()
    }

    override fun onPause() {
        session.pause()
        super.onPause()
    }

    override fun onDestroy() {
        session.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun configureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The rendered image must cover the whole physical panel for the lens
        // distortion to line up, so draw under the camera cutout.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay

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
