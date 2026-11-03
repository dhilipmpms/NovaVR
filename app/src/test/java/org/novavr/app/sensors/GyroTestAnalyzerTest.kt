package org.novavr.app.sensors

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroTestAnalyzerTest {

    private fun gyro(x: Float, y: Float, z: Float, tNs: Long) =
        MotionSample(Sensor.TYPE_GYROSCOPE, x, y, z, tNs)

    @Test
    fun measuresSampleRate() {
        val a = GyroTestAnalyzer()
        val periodNs = 5_000_000L // 200 Hz
        repeat(100) { a.process(gyro(0f, 0f, 0f, 1_000_000_000L + it * periodNs)) }
        assertEquals(200f, a.snapshot().sampleRateHz, 0.5f)
    }

    @Test
    fun integratesRotation() {
        val a = GyroTestAnalyzer()
        val periodNs = 10_000_000L // 100 Hz, 0.01 s
        // 0.5 rad/s around Y for 1 s -> 0.5 rad yaw.
        repeat(101) { a.process(gyro(0f, 0.5f, 0f, it * periodNs + 1)) }
        assertEquals(0.5f, a.snapshot().yaw, 1e-3f)
        assertEquals(0f, a.snapshot().pitch, 1e-6f)
    }

    @Test
    fun passesOnlyWhenEveryAxisRespondsAtSufficientRate() {
        val a = GyroTestAnalyzer()
        val periodNs = 2_500_000L // 400 Hz
        var t = 1L
        repeat(50) { a.process(gyro(2f, 0f, 0f, t)); t += periodNs }
        repeat(50) { a.process(gyro(0f, 2f, 0f, t)); t += periodNs }
        assertFalse(a.snapshot().passed)
        repeat(50) { a.process(gyro(0f, 0f, 2f, t)); t += periodNs }
        val s = a.snapshot()
        assertTrue(s.axesPassed.all { it })
        assertTrue(s.passed)
    }

    @Test
    fun lowRateFails() {
        val a = GyroTestAnalyzer()
        val periodNs = 20_000_000L // 50 Hz
        var t = 1L
        repeat(60) { a.process(gyro(2f, 2f, 2f, t)); t += periodNs }
        assertFalse(a.snapshot().passed)
    }

    @Test
    fun noiseIsZeroForConstantSignal() {
        val a = GyroTestAnalyzer()
        repeat(50) { a.process(gyro(0.01f, 0f, 0f, it * 5_000_000L + 1)) }
        assertEquals(0f, a.snapshot().noise, 1e-6f)
    }

    @Test
    fun accelerometerReportsGravity() {
        val a = GyroTestAnalyzer()
        a.process(MotionSample(Sensor.TYPE_ACCELEROMETER, 0f, 0f, 9.81f, 1))
        assertEquals(9.81f, a.snapshot().gravity, 1e-4f)
    }
}
