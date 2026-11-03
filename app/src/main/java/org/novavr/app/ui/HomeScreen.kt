/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.novavr.app.BuildConfig
import org.novavr.app.R
import org.novavr.app.settings.DisplayMode
import org.novavr.app.settings.VRSettings
import org.novavr.app.ui.theme.NovaColors
import org.novavr.app.utils.DeviceInfo
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    settings: VRSettings,
    deviceInfo: DeviceInfo,
    onStartVr: () -> Unit,
    onDisplayModeChange: (DisplayMode) -> Unit,
    onScreenSizeChange: (Float) -> Unit,
    onScreenDistanceChange: (Float) -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 720.dp
        val primary: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Branding()
                StartVrCard(deviceInfo, onStartVr)
                SectionCard("Display mode") {
                    DisplayModeSelector(
                        selected = settings.displayMode,
                        portraitAspect = deviceInfo.portraitAspect,
                        onSelect = onDisplayModeChange,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NavTile(
                        title = "Calibration",
                        subtitle = "Align headset & test sensors",
                        icon = R.drawable.ic_tune,
                        onClick = onOpenCalibration,
                        modifier = Modifier.weight(1f),
                    )
                    NavTile(
                        title = "Settings",
                        subtitle = "Rendering & comfort",
                        icon = R.drawable.ic_settings,
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        val secondary: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                QuickSettings(settings, onScreenSizeChange, onScreenDistanceChange)
                DeviceCard(deviceInfo)
                Text(
                    "NovaVR ${BuildConfig.VERSION_NAME} · GPL-3.0 · Works offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
        }

        if (twoPane) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) { primary() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) { secondary() }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                primary()
                secondary()
            }
        }
    }
}

@Composable
private fun Branding() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .background(NovaColors.AccentDim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_vr), contentDescription = null, tint = NovaColors.Accent)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("NovaVR", style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun StartVrCard(deviceInfo: DeviceInfo, onStartVr: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onStartVr,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(containerColor = NovaColors.Accent),
        ) {
            Icon(painterResource(R.drawable.ic_vr), contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("Start VR", style = MaterialTheme.typography.titleMedium)
        }
        if (!deviceInfo.isVrCapable) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = NovaColors.Warning.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, NovaColors.Warning.copy(alpha = 0.4f)),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = NovaColors.Warning,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !deviceInfo.glEs3Supported -> "OpenGL ES 3.0 is not supported on this device."
                            else -> "Gyroscope or accelerometer missing: head tracking will not work."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Text(
                "Place the phone in your headset with the screen divider centred.",
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun QuickSettings(
    settings: VRSettings,
    onScreenSizeChange: (Float) -> Unit,
    onScreenDistanceChange: (Float) -> Unit,
) {
    SectionCard("Basic settings") {
        LabeledSlider(
            label = "Screen size",
            valueLabel = "${settings.screenDiagonalInches.roundToInt()}\"",
            value = settings.screenDiagonalInches,
            range = VRSettings.DIAGONAL_RANGE,
            onValueChange = { onScreenSizeChange(it.roundToInt().toFloat()) },
        )
        LabeledSlider(
            label = "Screen distance",
            valueLabel = "%.1f m".format(settings.screenDistanceMeters),
            value = settings.screenDistanceMeters,
            range = VRSettings.DISTANCE_RANGE,
            onValueChange = { onScreenDistanceChange((it * 10).roundToInt() / 10f) },
        )
    }
}

@Composable
private fun DeviceCard(info: DeviceInfo) {
    SectionCard("Connected device") {
        InfoRow("Device", info.deviceName)
        InfoRow("Android", "${info.androidVersion} (API ${info.sdkInt})")
        InfoRow("Display", "${info.resolutionLabel} · ${info.aspectLabel}")
        InfoRow(
            "Refresh rate",
            info.supportedRefreshRatesHz.takeIf { it.isNotEmpty() }
                ?.joinToString(" / ") { "${it.roundToInt()}" }?.plus(" Hz")
                ?: "${info.refreshRateHz.roundToInt()} Hz",
        )
        InfoRow("Panel", "%.1f\" · %.0f dpi".format(info.diagonalInches, info.xdpi))
        InfoRow(
            "OpenGL ES",
            info.glEsVersion,
            if (info.glEs3Supported) NovaColors.TextPrimary else NovaColors.Error,
        )
        InfoRow(
            "Gyroscope",
            if (info.hasGyroscope) info.gyroscopeMaxRateHz?.let { "Available · up to $it Hz" } ?: "Available"
            else "Not found",
            if (info.hasGyroscope) NovaColors.Success else NovaColors.Error,
        )
        InfoRow(
            "Accelerometer",
            if (info.hasAccelerometer) "Available" else "Not found",
            if (info.hasAccelerometer) NovaColors.Success else NovaColors.Error,
        )
        InfoRow(
            "VR ready",
            if (info.isVrCapable) "Yes" else "No",
            if (info.isVrCapable) NovaColors.Success else NovaColors.Warning,
        )
    }
}
