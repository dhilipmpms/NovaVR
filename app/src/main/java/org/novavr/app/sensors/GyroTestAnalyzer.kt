/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.sensors

import android.hardware.Sensor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Immutable view of the gyroscope test, published to the UI. */
data class GyroTestSnapshot(
    val sampleRateHz: Float = 0f,
    val angularVelocity: FloatArray = FloatArray(3),
    val peakAngularVelocity: FloatArray = FloatArray(3),
    /** Integrated rotation (radians) around the device Y and X axes. */
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    /** Standard deviation of |ω| over the recent window, rad/s. */
    val noise: Float = 0f,
    /** Magnitude of the accelerometer vector, m/s². */
    val gravity: Float = 0f,
    val sampleCount: Long = 0,
) {
    val axesPassed: BooleanArray
        get() = BooleanArray(3) { peakAngularVelocity[it] >= AXIS_THRESHOLD_RAD_S }

    /** All three axes responded and the sensor delivers enough samples for VR. */
    val passed: Boolean
        get() = axesPassed.all { it } && sampleRateHz >= MIN_RATE_HZ

    override fun equals(other: Any?): Boolean = other is GyroTestSnapshot &&
        sampleRateHz == other.sampleRateHz &&
        angularVelocity.contentEquals(other.angularVelocity) &&
        peakAngularVelocity.contentEquals(other.peakAngularVelocity) &&
        yaw == other.yaw && pitch == other.pitch && noise == other.noise &&
        gravity == other.gravity && sampleCount == other.sampleCount

    override fun hashCode(): Int = sampleCount.hashCode() * 31 + sampleRateHz.hashCode()

    companion object {
        /** A deliberate head turn easily exceeds this (~57°/s). */
        const val AXIS_THRESHOLD_RAD_S = 1.0f

        /** Below this rate head tracking becomes visibly laggy. */
        const val MIN_RATE_HZ = 100f
    }
}

/**
 * Pure (Android-independent apart from sensor type constants) analysis of a
 * stream of [MotionSample]s for the calibration gyroscope test.
 * Not thread-safe; feed it from a single coroutine.
 */
class GyroTestAnalyzer {

    private val timestamps = LongArray(RATE_WINDOW)
    private var timestampCount = 0
    private var timestampIndex = 0

    private val magnitudes = FloatArray(NOISE_WINDOW)
    private var magnitudeCount = 0
    private var magnitudeIndex = 0

    private val current = FloatArray(3)
    private val peak = FloatArray(3)
    private var lastGyroTimestamp = 0L
    private var yaw = 0f
    private var pitch = 0f
    private var gravity = 0f
    private var samples = 0L

    fun process(sample: MotionSample) {
        when (sample.type) {
            Sensor.TYPE_GYROSCOPE -> processGyro(sample)
            Sensor.TYPE_ACCELEROMETER ->
                gravity = sqrt(sample.x * sample.x + sample.y * sample.y + sample.z * sample.z)
        }
    }

    private fun processGyro(s: MotionSample) {
        samples++
        current[0] = s.x; current[1] = s.y; current[2] = s.z
        for (i in 0..2) peak[i] = max(peak[i], abs(current[i]))

        if (lastGyroTimestamp != 0L) {
            val dt = (s.timestampNs - lastGyroTimestamp) / 1e9f
            // Ignore gaps (e.g. app paused) so the dot does not jump.
            if (dt in 0f..0.1f) {
                yaw += s.y * dt
                pitch += s.x * dt
                yaw = yaw.coerceIn(-MAX_ANGLE, MAX_ANGLE)
                pitch = pitch.coerceIn(-MAX_ANGLE, MAX_ANGLE)
            }
        }
        lastGyroTimestamp = s.timestampNs

        timestamps[timestampIndex] = s.timestampNs
        timestampIndex = (timestampIndex + 1) % RATE_WINDOW
        if (timestampCount < RATE_WINDOW) timestampCount++

        magnitudes[magnitudeIndex] = sqrt(s.x * s.x + s.y * s.y + s.z * s.z)
        magnitudeIndex = (magnitudeIndex + 1) % NOISE_WINDOW
        if (magnitudeCount < NOISE_WINDOW) magnitudeCount++
    }

    fun resetOrientation() {
        yaw = 0f
        pitch = 0f
    }

    fun reset() {
        timestampCount = 0; timestampIndex = 0
        magnitudeCount = 0; magnitudeIndex = 0
        current.fill(0f); peak.fill(0f)
        lastGyroTimestamp = 0L
        yaw = 0f; pitch = 0f; gravity = 0f; samples = 0
    }

    fun snapshot(): GyroTestSnapshot = GyroTestSnapshot(
        sampleRateHz = sampleRate(),
        angularVelocity = current.copyOf(),
        peakAngularVelocity = peak.copyOf(),
        yaw = yaw,
        pitch = pitch,
        noise = noise(),
        gravity = gravity,
        sampleCount = samples,
    )

    private fun sampleRate(): Float {
        if (timestampCount < 2) return 0f
        val newest = timestamps[(timestampIndex - 1 + RATE_WINDOW) % RATE_WINDOW]
        val oldest = timestamps[(timestampIndex - timestampCount + RATE_WINDOW) % RATE_WINDOW]
        val span = newest - oldest
        return if (span > 0) (timestampCount - 1) * 1e9f / span else 0f
    }

    private fun noise(): Float {
        if (magnitudeCount < 2) return 0f
        var mean = 0f
        for (i in 0 until magnitudeCount) mean += magnitudes[i]
        mean /= magnitudeCount
        var variance = 0f
        for (i in 0 until magnitudeCount) {
            val d = magnitudes[i] - mean
            variance += d * d
        }
        return sqrt(variance / (magnitudeCount - 1))
    }

    private companion object {
        const val RATE_WINDOW = 200
        const val NOISE_WINDOW = 100
        const val MAX_ANGLE = 1.2f
    }
}
