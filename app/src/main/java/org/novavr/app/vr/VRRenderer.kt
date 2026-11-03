/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.opengl.GLES11Ext
import org.novavr.app.mirror.MirrorSource
import org.novavr.app.settings.DisplayMode
import org.novavr.app.settings.VRSettings
import org.novavr.app.utils.DeviceInfo
import org.novavr.cardboard.CardboardNative
import org.novavr.cardboard.DeviceParams
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Scene renderer for VR mode, running on the [GLSurfaceView] GL thread.
 *
 * Per frame: read the predicted head pose, render the scene for both eyes
 * through [StereoRenderer] and let the Cardboard SDK apply lens distortion.
 *
 * The virtual screen shows either the captured Android display ([mirrorSource]
 * set: frames are sampled straight from the SurfaceTexture, zero-copy) or the
 * generated calibration test pattern.
 *
 * Settings arrive from the UI thread through [updateSettings] and are applied
 * at the start of the next frame, so the UI never touches GL state directly.
 */
internal class VRRenderer(
    private val headTracker: HeadTracker,
    private val deviceInfo: DeviceInfo,
    initialSettings: VRSettings,
    private val calibrationMode: Boolean,
    private val mirrorSource: MirrorSource?,
    private val listener: Listener,
) : GLSurfaceView.Renderer {

    interface Listener {
        /**
         * Called on the GL thread roughly once per second.
         * @param captureFps new captured frames per second (0 without mirroring).
         * @param hasCapturedFrame whether any captured frame has arrived yet.
         * @param captureBlank the captured stream has stayed completely black.
         */
        fun onFrameStats(fps: Float, captureFps: Float, hasCapturedFrame: Boolean, captureBlank: Boolean)

        /** Called on the GL thread if the Cardboard viewer cannot be created. */
        fun onRendererError(message: String)

        /**
         * Called on the GL thread when the surface becomes portrait (true) or
         * landscape (false). Cardboard optics need a landscape surface; on
         * large screens Android may ignore the activity's orientation lock.
         */
        fun onPortraitSurface(portrait: Boolean)
    }

    // Recreated whenever a new EGL context appears (see onSurfaceCreated).
    private var stereo = StereoRenderer()
    private var virtualScreen = VirtualScreen()
    private var environment = Environment()
    private var eyeMarkers = EyeMarkers()
    private var blankProbe = BlankFrameProbe()

    private val pendingSettings = AtomicReference<VRSettings?>(null)
    private var settings = initialSettings
    private var contentMode: DisplayMode? = null
    private val testPattern = ScreenTexture()
    private val mirrorTexture = ScreenTexture(target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
    private var mirrorTextureName = 0
    private var glInitialized = false

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var portraitSurface = false

    @Volatile
    private var predictionNanos = DEFAULT_PREDICTION_NS

    private val headView = FloatArray(16)
    private val eyeView = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val eyeProjection = FloatArray(16)

    private var statsWindowStartNs = 0L
    private var statsFrames = 0
    private var statsCaptureFramesStart = 0L

    private val eyeDrawer = StereoRenderer.EyeDrawer { eye, eyeFromHead, projection ->
        Matrix.multiplyMM(eyeView, 0, eyeFromHead, 0, headView, 0)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, eyeView, 0)
        if (settings.showEnvironment) environment.draw(viewProjection)
        virtualScreen.draw(viewProjection, if (mirrorSource != null) mirrorTexture else testPattern)
        if (calibrationMode) {
            // Head-locked: ignores head rotation, keeps per-eye stereo offset.
            Matrix.multiplyMM(eyeProjection, 0, projection, 0, eyeFromHead, 0)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            eyeMarkers.draw(eye, eyeProjection)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
    }

    /** Thread-safe; applied on the next frame. */
    fun updateSettings(newSettings: VRSettings) {
        pendingSettings.set(newSettings)
    }

    /**
     * Sets pose prediction from the display refresh rate. GLSurfaceView
     * queues roughly two to three frames, so predicting that far ahead keeps
     * the image aligned with the head when it is finally shown.
     */
    fun setRefreshRate(hz: Float) {
        val frameNs = (1_000_000_000.0 / hz.coerceAtLeast(30f)).toLong()
        predictionNanos = (frameNs * 3).coerceIn(MIN_PREDICTION_NS, DEFAULT_PREDICTION_NS)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new EGL context means every previous GL object is already gone.
        if (glInitialized) abandonGlObjects()
        try {
            virtualScreen.init(external = mirrorSource != null)
            environment.init()
            if (calibrationMode) eyeMarkers.init()
            if (mirrorSource != null) {
                mirrorTextureName = GlUtil.createExternalTexture()
                mirrorSource.attach(mirrorTextureName)
                blankProbe.init()
            }
            glInitialized = true
            contentMode = null
        } catch (e: RuntimeException) {
            Log.e(TAG, "GL initialisation failed", e)
            listener.onRendererError(e.message ?: "OpenGL initialisation failed")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        portraitSurface = height > width
        listener.onPortraitSurface(portraitSurface)
        if (portraitSurface) stereo.release() else configureStereo()
    }

    override fun onDrawFrame(gl: GL10?) {
        applyPendingSettings()
        if (mirrorSource != null) {
            latchMirrorFrame(mirrorSource)
            blankProbe.update(SystemClock.elapsedRealtimeNanos(), mirrorTexture)
        } else if (contentMode != settings.displayMode) {
            updateTestPattern()
        }

        headTracker.getHeadView(predictionNanos, headView)
        stereo.render(eyeDrawer)
        trackFrameRate()
    }

    /** Releases all GL resources. Must be queued on the GL thread before pausing. */
    fun releaseGl() {
        if (!glInitialized) return
        mirrorSource?.detach()
        blankProbe.release()
        GlUtil.deleteTexture(mirrorTextureName)
        mirrorTextureName = 0
        mirrorTexture.id = 0
        GlUtil.deleteTexture(testPattern.id)
        testPattern.id = 0
        stereo.release()
        virtualScreen.release()
        environment.release()
        eyeMarkers.release()
        glInitialized = false
        contentMode = null
        Log.d(TAG, "GL resources released")
    }

    /**
     * The previous EGL context was lost without [releaseGl] running, so its GL
     * names are already invalid. Drop the objects holding them (never delete
     * stale names: they may alias objects in the new context). Only the native
     * Cardboard viewer is freed, before anything is created in the new context.
     */
    private fun abandonGlObjects() {
        // The stream is still bound to the dead context; unbinding may fail,
        // in which case attaching to the new context will report the error.
        runCatching { mirrorSource?.detach() }
        mirrorTextureName = 0
        mirrorTexture.id = 0
        testPattern.id = 0
        stereo.abandon()
        stereo = StereoRenderer()
        virtualScreen = VirtualScreen()
        environment = Environment()
        eyeMarkers = EyeMarkers()
        blankProbe = BlankFrameProbe()
        glInitialized = false
    }

    private fun applyPendingSettings() {
        val next = pendingSettings.getAndSet(null) ?: return
        val previous = settings
        settings = next
        if (next.lensSeparationMm != previous.lensSeparationMm ||
            next.renderScale != previous.renderScale
        ) {
            configureStereo()
        }
        updateGeometry()
    }

    private fun configureStereo() {
        if (surfaceWidth == 0 || surfaceHeight == 0 || portraitSurface) return
        val params = DeviceParams.withInterLensDistance(
            CardboardNative.headsetProfileParams(),
            settings.lensSeparationMm,
        )
        if (!stereo.configure(surfaceWidth, surfaceHeight, params, settings.renderScale)) {
            listener.onRendererError("Unable to initialise the Cardboard lens model")
        }
        updateGeometry()
    }

    private fun latchMirrorFrame(source: MirrorSource) {
        if (source.latchLatest(mirrorTexture.texMatrix)) {
            mirrorTexture.id = mirrorTextureName
            mirrorTexture.aspect = source.aspect
        } else {
            mirrorTexture.id = 0 // black until the first frame arrives
        }
    }

    private fun updateTestPattern() {
        val mode = settings.displayMode
        val bitmap = ScreenContent.createTestPattern(
            mode = mode,
            aspect = aspectFor(mode),
            sourceLongPx = deviceInfo.displayLongPx,
            sourceShortPx = deviceInfo.displayShortPx,
        )
        GlUtil.deleteTexture(testPattern.id)
        testPattern.id = GlUtil.createTexture(bitmap)
        testPattern.aspect = aspectFor(mode)
        ScreenTexture.setBitmapFlip(testPattern.texMatrix)
        bitmap.recycle()
        contentMode = mode
        updateGeometry()
    }

    private fun updateGeometry() {
        virtualScreen.setGeometry(
            diagonalInches = settings.screenDiagonalInches,
            aspect = aspectFor(settings.displayMode),
            distance = settings.screenDistanceMeters,
        )
    }

    private fun aspectFor(mode: DisplayMode): Float =
        if (mode == DisplayMode.PORTRAIT) deviceInfo.portraitAspect else deviceInfo.landscapeAspect

    private fun trackFrameRate() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        statsFrames++
        val elapsed = now - statsWindowStartNs
        if (elapsed >= 1_000_000_000L) {
            val captured = mirrorSource?.latchedFrames ?: 0L
            listener.onFrameStats(
                fps = statsFrames * 1e9f / elapsed,
                captureFps = (captured - statsCaptureFramesStart) * 1e9f / elapsed,
                hasCapturedFrame = mirrorSource?.hasFrame == true,
                captureBlank = mirrorSource != null && blankProbe.isBlank,
            )
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(
                    TAG,
                    "fps=%.1f capture=%.1f frame=%s headView=%s".format(
                        statsFrames * 1e9f / elapsed,
                        (captured - statsCaptureFramesStart) * 1e9f / elapsed,
                        mirrorSource?.hasFrame,
                        headView.joinToString(limit = 16) { "%.2f".format(it) },
                    ),
                )
            }
            statsCaptureFramesStart = captured
            statsFrames = 0
            statsWindowStartNs = now
        }
    }

    private companion object {
        const val TAG = "NovaVR-Renderer"
        const val DEFAULT_PREDICTION_NS = 50_000_000L
        const val MIN_PREDICTION_NS = 25_000_000L
    }
}
