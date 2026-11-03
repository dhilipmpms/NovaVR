/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.novavr.app.BuildConfig
import org.novavr.app.settings.VRSettings
import org.novavr.app.ui.theme.NovaColors
import org.novavr.app.utils.DeviceInfo
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: VRSettings,
    deviceInfo: DeviceInfo,
    profileLensSeparationMm: Float?,
    onUpdate: ((VRSettings) -> VRSettings) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ScreenTitleBar("Settings", onBack)

            SectionCard("Display mode") {
                DisplayModeSelector(
                    selected = settings.displayMode,
                    portraitAspect = deviceInfo.portraitAspect,
                    onSelect = { mode -> onUpdate { it.copy(displayMode = mode) } },
                )
                Text(
                    "Proportions follow this phone's display (${deviceInfo.aspectLabel}). " +
                        "In VR, tap the screen to switch modes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                )
            }

            SectionCard("Virtual screen") {
                LabeledSlider(
                    label = "Screen size (diagonal)",
                    valueLabel = "${settings.screenDiagonalInches.roundToInt()}\"",
                    value = settings.screenDiagonalInches,
                    range = VRSettings.DIAGONAL_RANGE,
                    onValueChange = { v -> onUpdate { it.copy(screenDiagonalInches = v.roundToInt().toFloat()) } },
                )
                LabeledSlider(
                    label = "Screen distance",
                    valueLabel = "%.1f m".format(settings.screenDistanceMeters),
                    value = settings.screenDistanceMeters,
                    range = VRSettings.DISTANCE_RANGE,
                    onValueChange = { v -> onUpdate { it.copy(screenDistanceMeters = (v * 10).roundToInt() / 10f) } },
                )
                SwitchRow(
                    title = "Show environment",
                    subtitle = "Floor grid for spatial reference and comfort",
                    checked = settings.showEnvironment,
                    onCheckedChange = { v -> onUpdate { it.copy(showEnvironment = v) } },
                )
            }

            SectionCard("Rendering") {
                LabeledSlider(
                    label = "Render resolution",
                    valueLabel = "${(settings.renderScale * 100).roundToInt()}%",
                    value = settings.renderScale,
                    range = VRSettings.RENDER_SCALE_RANGE,
                    steps = 9,
                    onValueChange = { v -> onUpdate { it.copy(renderScale = (v * 20).roundToInt() / 20f) } },
                )
                Text(
                    "Lower values reduce GPU load and heat; 100% is sharpest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                )
                val maxHz = deviceInfo.supportedRefreshRatesHz.maxOrNull()?.roundToInt()
                SwitchRow(
                    title = "High refresh rate",
                    subtitle = if (maxHz != null && maxHz > 60) {
                        "Render at up to $maxHz Hz for smoother motion (uses more battery)"
                    } else {
                        "This display supports 60 Hz only"
                    },
                    checked = settings.preferHighRefreshRate,
                    onCheckedChange = { v -> onUpdate { it.copy(preferHighRefreshRate = v) } },
                )
            }

            SectionCard("Screen mirroring") {
                LabeledSlider(
                    label = "Capture resolution",
                    valueLabel = when {
                        settings.mirrorResolutionScale >= 0.99f -> "Full"
                        settings.mirrorResolutionScale >= 0.74f -> "High (75%)"
                        else -> "Balanced (50%)"
                    },
                    value = settings.mirrorResolutionScale,
                    range = VRSettings.MIRROR_SCALE_RANGE,
                    steps = 1,
                    onValueChange = { v -> onUpdate { it.copy(mirrorResolutionScale = (v * 4).roundToInt() / 4f) } },
                )
                Text(
                    "The headset shows the virtual screen with far fewer pixels than the phone panel, " +
                        "so lower capture resolutions look almost identical and save battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NovaColors.TextSecondary,
                )
                SwitchRow(
                    title = "Follow content orientation",
                    subtitle = "Switch Portrait / Landscape when the shared app rotates",
                    checked = settings.followContentOrientation,
                    onCheckedChange = { v -> onUpdate { it.copy(followContentOrientation = v) } },
                )
            }

            InputSettingsSection(settings = settings, onUpdate = onUpdate)

            SectionCard("Headset") {
                val custom = settings.lensSeparationMm != null
                val profileValue = profileLensSeparationMm ?: VRSettings.DEFAULT_LENS_SEPARATION_MM
                SwitchRow(
                    title = "Custom lens separation",
                    subtitle = if (custom) {
                        "Match the distance between your headset's lens centres"
                    } else {
                        "Using headset profile: %.1f mm".format(profileValue)
                    },
                    checked = custom,
                    onCheckedChange = { v ->
                        onUpdate { it.copy(lensSeparationMm = if (v) profileValue else null) }
                    },
                )
                if (custom) {
                    LabeledSlider(
                        label = "Lens separation",
                        valueLabel = "%.1f mm".format(settings.lensSeparationMm),
                        value = settings.lensSeparationMm,
                        range = VRSettings.LENS_SEPARATION_RANGE,
                        onValueChange = { v -> onUpdate { it.copy(lensSeparationMm = (v * 2).roundToInt() / 2f) } },
                    )
                }
            }

            SectionCard("About") {
                InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("License", "GPL-3.0")
                InfoRow("VR framework", "Google Cardboard SDK")
                InfoRow("Network access", "None")
            }

            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) { Text("Reset to defaults") }

            Text("", modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}
