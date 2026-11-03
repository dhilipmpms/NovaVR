/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.cardboard

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Thin JNI facade over the Google Cardboard SDK C API.
 *
 * Threading contract:
 *  - [initialize] may be called from any thread (idempotent).
 *  - `nativeViewer*` functions issue OpenGL calls and must run on the GL thread
 *    that owns the current EGL context.
 *  - Head tracker functions are thread-safe in the SDK.
 */
object CardboardNative {

    /** Matches `CardboardEye`. */
    const val EYE_LEFT = 0
    const val EYE_RIGHT = 1

    /** Matches `CardboardViewportOrientation`. */
    const val ORIENTATION_LANDSCAPE_LEFT = 0
    const val ORIENTATION_LANDSCAPE_RIGHT = 1

    @Volatile
    private var initialized = false

    init {
        System.loadLibrary("novavr_cardboard")
    }

    /**
     * Initialises the SDK once per process.
     *
     * The SDK keeps a global reference to the context for the lifetime of the
     * process and calls `Context.getDisplay()` on it, so we hand it a display
     * context derived from the application context instead of an Activity
     * (which would leak).
     */
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        val display = app.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val displayContext = if (display != null) app.createDisplayContext(display) else app
        nativeInitialize(displayContext)
        initialized = true
    }

    /** Device params previously saved by a Cardboard viewer pairing, or null. */
    fun savedDeviceParams(): ByteArray? = nativeGetSavedDeviceParams()

    /** Built-in parameters of the original Cardboard V1 viewer. */
    fun cardboardV1DeviceParams(): ByteArray =
        checkNotNull(nativeGetCardboardV1DeviceParams()) { "Cardboard V1 params unavailable" }

    /**
     * The active headset profile: parameters saved by a previous Cardboard
     * viewer pairing if present, otherwise the Cardboard V1 profile.
     * Requires [initialize].
     */
    fun headsetProfileParams(): ByteArray {
        check(initialized) { "CardboardNative.initialize() must be called first" }
        return savedDeviceParams() ?: cardboardV1DeviceParams()
    }

    @JvmStatic private external fun nativeInitialize(context: Context)
    @JvmStatic private external fun nativeGetSavedDeviceParams(): ByteArray?
    @JvmStatic private external fun nativeGetCardboardV1DeviceParams(): ByteArray?

    // Head tracker ----------------------------------------------------------
    @JvmStatic external fun nativeHeadTrackerCreate(): Long
    @JvmStatic external fun nativeHeadTrackerDestroy(handle: Long)
    @JvmStatic external fun nativeHeadTrackerPause(handle: Long)
    @JvmStatic external fun nativeHeadTrackerResume(handle: Long)
    @JvmStatic external fun nativeHeadTrackerRecenter(handle: Long)
    @JvmStatic external fun nativeHeadTrackerSetLowPassFilter(handle: Long, cutoffHz: Int)

    /** Writes `[px, py, pz, qx, qy, qz, qw]` into [outPose] (size >= 7). */
    @JvmStatic external fun nativeHeadTrackerGetPose(
        handle: Long,
        predictionNanos: Long,
        orientation: Int,
        outPose: FloatArray,
    )

    // Viewer (lens distortion + GLES 3.0 distortion renderer) -----------------
    @JvmStatic external fun nativeViewerCreate(deviceParams: ByteArray, width: Int, height: Int): Long
    @JvmStatic external fun nativeViewerDestroy(handle: Long)
    @JvmStatic external fun nativeViewerGetEyeFromHeadMatrix(handle: Long, eye: Int, outMatrix: FloatArray)
    @JvmStatic external fun nativeViewerGetProjectionMatrix(
        handle: Long,
        eye: Int,
        zNear: Float,
        zFar: Float,
        outMatrix: FloatArray,
    )

    /** Writes `[left, right, bottom, top]` half-angles in radians. */
    @JvmStatic external fun nativeViewerGetFieldOfView(handle: Long, eye: Int, outFov: FloatArray)
    @JvmStatic external fun nativeViewerRender(
        handle: Long,
        texture: Int,
        targetFramebuffer: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    )
}
