/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer

/** One motion sample. Values are in sensor units (rad/s for gyro, m/s² for accel). */
data class MotionSample(
    val type: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val timestampNs: Long,
)

/**
 * Kotlin-side access to the Android SensorManager.
 *
 * In VR the head pose comes from the Cardboard SDK's native sensor fusion
 * (see [org.novavr.app.vr.HeadTracker]), which reads the same gyroscope and
 * accelerometer through the NDK. This helper is used outside VR, e.g. by the
 * calibration screen's gyroscope responsiveness test.
 *
 * Sensors are only registered while a [motionSamples] flow is being collected,
 * so nothing is processed when no screen needs the data.
 */
class SensorManagerHelper(context: Context) {

    private val sensorManager: SensorManager =
        context.applicationContext.getSystemService(SensorManager::class.java)

    val gyroscope: Sensor? get() = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val accelerometer: Sensor? get() = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val hasHeadTrackingSensors: Boolean get() = gyroscope != null && accelerometer != null

    /**
     * Cold flow of gyroscope and accelerometer samples. Listeners are
     * registered on collection and unregistered when collection stops.
     * A bounded buffer keeps every sample for normal collectors (the gyro
     * test integrates them) while a stalled collector drops the oldest.
     */
    fun motionSamples(samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME): Flow<MotionSample> =
        callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(
                        MotionSample(
                            type = event.sensor.type,
                            x = event.values[0],
                            y = event.values[1],
                            z = event.values[2],
                            timestampNs = event.timestamp,
                        ),
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            listOfNotNull(gyroscope, accelerometer).forEach {
                sensorManager.registerListener(listener, it, samplingPeriodUs)
            }
            awaitClose { sensorManager.unregisterListener(listener) }
        }.buffer(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
