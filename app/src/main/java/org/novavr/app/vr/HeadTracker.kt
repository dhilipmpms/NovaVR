/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.view.Surface
import org.novavr.cardboard.CardboardNative

/**
 * Head orientation tracking backed by the Cardboard SDK.
 *
 * The SDK fuses the gyroscope and accelerometer (extended Kalman filter with
 * gyroscope bias estimation and a neck model) and predicts the pose for the
 * time the frame will actually be displayed, which keeps motion-to-photon
 * latency low.
 *
 * Lifecycle: [resume] in `onResume`, [pause] in `onPause` (sensors are
 * unregistered while paused, so nothing runs in the background), [close] in
 * `onDestroy`.
 */
class HeadTracker {

    private val lock = Any()
    private var handle: Long = CardboardNative.nativeHeadTrackerCreate()
    private val pose = FloatArray(7)

    /** Cardboard viewport orientation, updated from the display rotation. */
    @Volatile
    private var viewportOrientation = CardboardNative.ORIENTATION_LANDSCAPE_LEFT

    init {
        check(handle != 0L) { "Unable to create Cardboard head tracker" }
    }

    fun resume() = synchronized(lock) {
        if (handle != 0L) CardboardNative.nativeHeadTrackerResume(handle)
    }

    fun pause() = synchronized(lock) {
        if (handle != 0L) CardboardNative.nativeHeadTrackerPause(handle)
    }

    /** Re-aligns the forward direction (zero yaw) with where the user looks now. */
    fun recenter() = synchronized(lock) {
        if (handle != 0L) CardboardNative.nativeHeadTrackerRecenter(handle)
    }

    fun close() = synchronized(lock) {
        if (handle != 0L) {
            CardboardNative.nativeHeadTrackerDestroy(handle)
            handle = 0L
        }
    }

    /**
     * Maps an Android display rotation to the Cardboard viewport orientation.
     * VR only runs in landscape; `ROTATION_90` is Android "landscape" and
     * `ROTATION_270` is "reverseLandscape".
     */
    fun onDisplayRotationChanged(rotation: Int) {
        viewportOrientation = when (rotation) {
            Surface.ROTATION_270 -> CardboardNative.ORIENTATION_LANDSCAPE_RIGHT
            else -> CardboardNative.ORIENTATION_LANDSCAPE_LEFT
        }
    }

    /**
     * Writes the world-to-head ("head view") matrix predicted
     * [predictionNanos] into the future into [outHeadView] (column-major).
     * Returns false if the tracker is closed (identity is written).
     */
    fun getHeadView(predictionNanos: Long, outHeadView: FloatArray): Boolean {
        synchronized(lock) {
            if (handle == 0L) {
                android.opengl.Matrix.setIdentityM(outHeadView, 0)
                return false
            }
            CardboardNative.nativeHeadTrackerGetPose(handle, predictionNanos, viewportOrientation, pose)
        }
        poseToHeadView(pose, outHeadView)
        return true
    }

    companion object {
        /**
         * Builds `T(position) * R(q)` exactly like the Cardboard reference
         * sample. The SDK's quaternion (x, y, z, w) already describes the
         * world-to-head rotation.
         */
        internal fun poseToHeadView(pose: FloatArray, out: FloatArray) {
            val x = pose[3]; val y = pose[4]; val z = pose[5]; val w = pose[6]
            val xx = 2 * x * x; val yy = 2 * y * y; val zz = 2 * z * z
            val xy = 2 * x * y; val xz = 2 * x * z; val yz = 2 * y * z
            val xw = 2 * x * w; val yw = 2 * y * w; val zw = 2 * z * w
            // Column 0
            out[0] = 1 - yy - zz; out[1] = xy + zw; out[2] = xz - yw; out[3] = 0f
            // Column 1
            out[4] = xy - zw; out[5] = 1 - xx - zz; out[6] = yz + xw; out[7] = 0f
            // Column 2
            out[8] = xz + yw; out[9] = yz - xw; out[10] = 1 - xx - yy; out[11] = 0f
            // Column 3: translation (neck model offset)
            out[12] = pose[0]; out[13] = pose[1]; out[14] = pose[2]; out[15] = 1f
        }
    }
}
