/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.utils

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Snapshot of the device capabilities relevant to VR. Everything is queried
 * at runtime; nothing is hard-coded for a specific phone.
 */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    /** Long and short side of the physical panel in pixels. */
    val displayLongPx: Int,
    val displayShortPx: Int,
    val xdpi: Float,
    val ydpi: Float,
    val refreshRateHz: Float,
    val supportedRefreshRatesHz: List<Float>,
    val glEsVersion: String,
    val glEs3Supported: Boolean,
    val hasGyroscope: Boolean,
    val hasAccelerometer: Boolean,
    val gyroscopeName: String?,
    val gyroscopeMaxRateHz: Int?,
    val sustainedPerformanceSupported: Boolean,
) {
    val deviceName: String
        get() = if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "${manufacturer.replaceFirstChar { it.uppercase() }} $model"

    /** Short / long side, e.g. 0.45 for a 20:9 phone. */
    val portraitAspect: Float get() = displayShortPx.toFloat() / displayLongPx

    /** Long / short side, e.g. 2.22 for a 20:9 phone. */
    val landscapeAspect: Float get() = displayLongPx.toFloat() / displayShortPx

    val resolutionLabel: String get() = "$displayLongPx × $displayShortPx"

    /** Panel diagonal estimated from the reported pixel density. */
    val diagonalInches: Float
        get() {
            val w = displayLongPx / max(xdpi, 1f)
            val h = displayShortPx / max(ydpi, 1f)
            return hypot(w, h)
        }

    val aspectLabel: String
        get() {
            // Express as N:9, which is how phone panels are usually marketed.
            val ratio = landscapeAspect * 9f
            return "${(ratio * 10).roundToInt() / 10f}:9".replace(".0:", ":")
        }

    /** True when the essential VR requirements are met. */
    val isVrCapable: Boolean get() = glEs3Supported && hasGyroscope && hasAccelerometer

    companion object {
        fun query(context: Context): DeviceInfo {
            val app = context.applicationContext
            val display = app.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.mode
            val physW = mode?.physicalWidth ?: 0
            val physH = mode?.physicalHeight ?: 0
            val metrics = app.resources.displayMetrics

            val refreshRates = display?.supportedModes
                ?.filter { it.physicalWidth == physW && it.physicalHeight == physH }
                ?.map { it.refreshRate }
                ?.distinct()
                ?.sorted()
                .orEmpty()

            val am = app.getSystemService(ActivityManager::class.java)
            val glVersionInt = am.deviceConfigurationInfo.reqGlEsVersion
            val glMajor = glVersionInt shr 16
            val glMinor = glVersionInt and 0xFFFF

            val sm = app.getSystemService(SensorManager::class.java)
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val pm = app.getSystemService(PowerManager::class.java)

            return DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                displayLongPx = max(physW, physH).takeIf { it > 0 }
                    ?: max(metrics.widthPixels, metrics.heightPixels),
                displayShortPx = min(physW, physH).takeIf { it > 0 }
                    ?: min(metrics.widthPixels, metrics.heightPixels),
                xdpi = metrics.xdpi,
                ydpi = metrics.ydpi,
                refreshRateHz = display?.refreshRate ?: 60f,
                supportedRefreshRatesHz = refreshRates,
                glEsVersion = "$glMajor.$glMinor",
                glEs3Supported = glMajor >= 3,
                hasGyroscope = gyro != null,
                hasAccelerometer = accel != null,
                gyroscopeName = gyro?.name,
                gyroscopeMaxRateHz = gyro?.minDelay?.takeIf { it > 0 }?.let { 1_000_000 / it },
                sustainedPerformanceSupported = pm.isSustainedPerformanceModeSupported,
            )
        }
    }
}
