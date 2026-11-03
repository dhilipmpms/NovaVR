/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.novavr.app.input.CursorSnapshot
import org.novavr.app.input.NovaCommand
import org.novavr.app.mirror.MirrorSource
import org.novavr.app.settings.VRSettings
import org.novavr.app.settings.VRSettingsRepository
import org.novavr.app.utils.DeviceInfo
import org.novavr.cardboard.CardboardNative
import kotlin.math.abs

/** Observable state of a running VR session, read by the Compose overlay. */
class VRSessionState {
    var fps by mutableFloatStateOf(0f)
        internal set
    var captureFps by mutableFloatStateOf(0f)
        internal set
    var hasCapturedFrame by mutableStateOf(false)
        internal set
    var captureBlank by mutableStateOf(false)
        internal set
    var errorMessage by mutableStateOf<String?>(null)
        internal set
    var portraitSurface by mutableStateOf(false)
        internal set
}

/**
 * The VR engine, independent of the window hosting it: head tracker, GL
 * surface, renderer, display tracking and settings forwarding.
 *
 * Hosted by [VRActivity] (test pattern / calibration) and by the mirroring
 * overlay window ([org.novavr.app.mirror.VROverlayWindow]). The host calls
 * [resume] / [pause] / [destroy] from its own lifecycle and applies the
 * preferred display mode to its window via [onPreferredDisplayMode].
 *
 * Main thread only (except where noted).
 */
class VRSession(
    context: Context,
    private val settingsRepository: VRSettingsRepository,
    calibrationMode: Boolean,
    mirrorSource: MirrorSource?,
    cursorSource: (() -> CursorSnapshot)?,
    dock: List<NovaCommand>,
    private val displayProvider: () -> Display?,
    private val onPreferredDisplayMode: (modeId: Int) -> Unit,
) {
    val state = VRSessionState()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope: CoroutineScope = MainScope()
    private var settingsJob: Job? = null
    private var resumed = false
    private var preferHighRefreshRate = settingsRepository.current.preferHighRefreshRate
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    private val headTracker: HeadTracker
    private val renderer: VRRenderer
    val glView: GLSurfaceView

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == displayProvider()?.displayId) onDisplayUpdated()
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    init {
        CardboardNative.initialize(context)
        headTracker = HeadTracker()
        renderer = VRRenderer(
            headTracker = headTracker,
            deviceInfo = DeviceInfo.query(context),
            initialSettings = settingsRepository.current,
            calibrationMode = calibrationMode,
            mirrorSource = mirrorSource,
            cursorSource = cursorSource,
            dock = dock,
            listener = object : VRRenderer.Listener {
                override fun onFrameStats(
                    fps: Float,
                    captureFps: Float,
                    hasCapturedFrame: Boolean,
                    captureBlank: Boolean,
                ) {
                    mainHandler.post {
                        state.fps = fps
                        state.captureFps = captureFps
                        state.hasCapturedFrame = hasCapturedFrame
                        state.captureBlank = captureBlank
                    }
                }

                override fun onRendererError(message: String) {
                    mainHandler.post { state.errorMessage = message }
                }

                override fun onPortraitSurface(portrait: Boolean) {
                    mainHandler.post { state.portraitSurface = portrait }
                }
            },
        )
        glView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            // RGB888, no depth/stencil: depth lives in the offscreen eye buffer.
            setEGLConfigChooser(8, 8, 8, 0, 0, 0)
            preserveEGLContextOnPause = false
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = applySurfaceFrameRate()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                applySurfaceFrameRate()
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
    }

    fun resume() {
        if (resumed) return
        resumed = true
        displayManager.registerDisplayListener(displayListener, mainHandler)
        onDisplayUpdated()
        headTracker.resume()
        glView.onResume()
        settingsJob = scope.launch {
            settingsRepository.settings.collect { settings ->
                renderer.updateSettings(settings)
                preferHighRefreshRate = settings.preferHighRefreshRate
                applyRefreshRatePreference(settings.preferHighRefreshRate)
                applySurfaceFrameRate()
            }
        }
    }

    /**
     * Stops rendering and sensor sampling. GL objects are freed on the GL
     * thread while the context is still current: the queued event runs before
     * the GL thread processes the pause request, and [GLSurfaceView.onPause]
     * blocks until it has.
     */
    fun pause() {
        if (!resumed) return
        resumed = false
        settingsJob?.cancel()
        settingsJob = null
        glView.queueEvent { renderer.releaseGl() }
        glView.onPause()
        headTracker.pause()
        displayManager.unregisterDisplayListener(displayListener)
    }

    fun destroy() {
        pause()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        headTracker.close()
    }

    // Controls -----------------------------------------------------------------

    fun recenter() = headTracker.recenter()

    fun toggleDisplayMode() =
        settingsRepository.update { it.copy(displayMode = it.displayMode.toggled()) }

    fun adjustScreenSize(steps: Int) = settingsRepository.update {
        it.copy(screenDiagonalInches = it.screenDiagonalInches + steps * VRSettings.DIAGONAL_STEP)
    }

    fun adjustScreenDistance(steps: Int) = settingsRepository.update {
        it.copy(screenDistanceMeters = it.screenDistanceMeters + steps * VRSettings.DISTANCE_STEP)
    }

    // Display ------------------------------------------------------------------

    private fun onDisplayUpdated() {
        val display = displayProvider() ?: return
        headTracker.onDisplayRotationChanged(display.rotation)
        renderer.setRefreshRate(display.refreshRate)
    }

    /**
     * Picks the display mode with the highest (or a 60 Hz class) refresh rate
     * at the current resolution, from the modes the device reports.
     */
    private fun applyRefreshRatePreference(preferHigh: Boolean) {
        val display = displayProvider() ?: return
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val target = if (preferHigh) {
            candidates.maxByOrNull { it.refreshRate }
        } else {
            candidates.minByOrNull { abs(it.refreshRate - 60f) }
        } ?: return
        onPreferredDisplayMode(target.modeId)
    }

    /**
     * Votes for a frame rate on the GL surface itself. Activities can pick a
     * display mode, but windows such as the mirroring overlay cannot; a
     * surface frame-rate vote works for both and lets a 120 Hz panel run at
     * 120 Hz while VR is shown.
     */
    private fun applySurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val surface = glView.holder.surface ?: return
        if (!surface.isValid) return
        val display = displayProvider() ?: return
        val current = display.mode
        val rates = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate }
        val rate = if (preferHighRefreshRate) rates.maxOrNull() else rates.minByOrNull { abs(it - 60f) }
        if (rate == null || rate <= 0f) return
        runCatching {
            surface.setFrameRate(
                rate,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ALWAYS,
            )
        }
    }
}
