/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.ui

import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.novavr.app.R
import org.novavr.app.sensors.GyroTestAnalyzer
import org.novavr.app.sensors.GyroTestSnapshot
import org.novavr.app.sensors.SensorManagerHelper
import org.novavr.app.settings.DisplayMode
import org.novavr.app.settings.VRSettings
import org.novavr.app.ui.theme.NovaColors
import org.novavr.app.utils.DeviceInfo
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun CalibrationScreen(
    settings: VRSettings,
    deviceInfo: DeviceInfo,
    profileLensSeparationMm: Float?,
    onUpdate: ((VRSettings) -> VRSettings) -> Unit,
    onStartEyeTest: () -> Unit,
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
            ScreenTitleBar("Calibration", onBack)
            AlignmentInstructions()
            EyeVerification(onStartEyeTest)
            ScreenFit(settings, deviceInfo, profileLensSeparationMm, onUpdate)
            GyroscopeTest(deviceInfo)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AlignmentInstructions() {
    SectionCard("1 · Headset alignment") {
        val steps = listOf(
            "Open the headset tray and remove any phone case that prevents the phone from lying flat.",
            "Start VR, then insert the phone screen-first so the white centre divider lines up with the " +
                "middle of the headset (the nose bridge).",
            "Push the phone against the bottom of the tray so it sits level; the divider should look vertical.",
            "Adjust the headset straps so the lenses sit directly in front of your eyes and the image is sharp.",
            "Look straight ahead and long-press the screen (or press the headset button and hold) to recenter.",
        )
        steps.forEachIndexed { index, text -> NumberedStep(index + 1, text) }
    }
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(24.dp)
                .background(NovaColors.AccentDim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = NovaColors.TextPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EyeVerification(onStartEyeTest: () -> Unit) {
    SectionCard("2 · Left / right eye check") {
        Text(
            "Starts VR with a marker fixed in front of each eye: a red “LEFT EYE” crosshair for the left " +
                "lens and a blue “RIGHT EYE” crosshair for the right lens.",
            style = MaterialTheme.typography.bodyMedium,
        )
        CheckItem("Close your right eye: you should see only the red LEFT EYE marker.")
        CheckItem("Close your left eye: you should see only the blue RIGHT EYE marker.")
        CheckItem("With both eyes open the two crosshairs should merge into one, without double vision.")
        Text(
            "If the markers do not merge, adjust the lens separation below or re-seat the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
        Button(
            onClick = onStartEyeTest,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(painterResource(R.drawable.ic_vr), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start eye check in VR")
        }
    }
}

@Composable
private fun CheckItem(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = NovaColors.Accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ScreenFit(
    settings: VRSettings,
    deviceInfo: DeviceInfo,
    profileLensSeparationMm: Float?,
    onUpdate: ((VRSettings) -> VRSettings) -> Unit,
) {
    SectionCard("3 · Screen size & distance") {
        val aspect = if (settings.displayMode == DisplayMode.PORTRAIT) {
            deviceInfo.portraitAspect
        } else {
            deviceInfo.landscapeAspect
        }
        val diagonalM = settings.screenDiagonalInches * 0.0254f
        val heightM = diagonalM / sqrt(1f + aspect * aspect)
        val widthM = heightM * aspect
        val hFov = Math.toDegrees(2.0 * atan(widthM / 2.0 / settings.screenDistanceMeters)).roundToInt()
        val vFov = Math.toDegrees(2.0 * atan(heightM / 2.0 / settings.screenDistanceMeters)).roundToInt()

        ScreenFitDiagram(widthM, settings.screenDistanceMeters)
        Text(
            "The screen fills $hFov° × $vFov° of your view " +
                "(%.2f × %.2f m). Aim for the whole screen visible without moving your head; ".format(widthM, heightM) +
                "about 40–60° wide is comfortable.",
            style = MaterialTheme.typography.bodySmall,
            color = if (hFov > 80) NovaColors.Warning else NovaColors.TextSecondary,
        )
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
        val lens = settings.lensSeparationMm ?: profileLensSeparationMm ?: VRSettings.DEFAULT_LENS_SEPARATION_MM
        LabeledSlider(
            label = "Lens separation",
            valueLabel = "%.1f mm".format(lens) + if (settings.lensSeparationMm == null) " (profile)" else "",
            value = lens,
            range = VRSettings.LENS_SEPARATION_RANGE,
            onValueChange = { v -> onUpdate { it.copy(lensSeparationMm = (v * 2).roundToInt() / 2f) } },
        )
        if (settings.lensSeparationMm != null) {
            OutlinedButton(
                onClick = { onUpdate { it.copy(lensSeparationMm = null) } },
                shape = MaterialTheme.shapes.large,
            ) { Text("Use headset profile value") }
        }
    }
}

/** Top-down sketch: the viewer, the virtual screen and its angular width. */
@Composable
private fun ScreenFitDiagram(widthM: Float, distanceM: Float) {
    val accent = NovaColors.Accent
    val outline = NovaColors.Outline
    val text = NovaColors.TextSecondary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val maxDepth = 6.2f
        val scale = size.height / maxDepth
        val viewer = Offset(size.width / 2f, size.height - 6f)
        val screenY = viewer.y - distanceM * scale
        val half = (widthM / 2f) * scale
        // Comfortable 60° guide cone
        val guide = size.height * 0.577f
        drawLine(outline, viewer, Offset(viewer.x - guide, 0f), strokeWidth = 2f)
        drawLine(outline, viewer, Offset(viewer.x + guide, 0f), strokeWidth = 2f)
        drawLine(accent.copy(alpha = 0.5f), viewer, Offset(viewer.x - half, screenY), strokeWidth = 2f)
        drawLine(accent.copy(alpha = 0.5f), viewer, Offset(viewer.x + half, screenY), strokeWidth = 2f)
        drawLine(accent, Offset(viewer.x - half, screenY), Offset(viewer.x + half, screenY), strokeWidth = 8f)
        drawCircle(text, radius = 7f, center = viewer)
        drawCircle(text, radius = 7f, center = viewer, style = Stroke(width = 2f))
    }
}

@Composable
private fun GyroscopeTest(deviceInfo: DeviceInfo) {
    SectionCard("4 · Gyroscope responsiveness") {
        if (!deviceInfo.hasGyroscope) {
            Text(
                "No gyroscope found. Head tracking needs a gyroscope; rotation will not follow your head.",
                style = MaterialTheme.typography.bodyMedium,
                color = NovaColors.Error,
            )
            return@SectionCard
        }
        val context = LocalContext.current
        val helper = remember { SensorManagerHelper(context) }
        val analyzer = remember { GyroTestAnalyzer() }
        var running by remember { mutableStateOf(false) }
        var snapshot by remember { mutableStateOf(GyroTestSnapshot()) }

        // Stop sampling when the app goes to the background.
        LifecycleStartEffect(Unit) {
            onStopOrDispose { running = false }
        }

        if (running) {
            LaunchedEffect(Unit) {
                analyzer.reset()
                // Sensors are registered only for the lifetime of this effect.
                launch {
                    helper.motionSamples(SensorManager.SENSOR_DELAY_FASTEST).collect(analyzer::process)
                }
                while (isActive) {
                    withFrameNanos { }
                    snapshot = analyzer.snapshot()
                }
            }
        }

        Text(
            "Hold the phone in your hand and rotate it briskly around each axis: turn left/right, " +
                "tilt forward/back and roll sideways.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OrientationPad(snapshot, Modifier.size(120.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val labels = listOf("X · tilt", "Y · turn", "Z · roll")
                labels.forEachIndexed { i, label -> AxisBar(label, snapshot.angularVelocity[i], snapshot.axesPassed[i]) }
            }
        }

        InfoRow("Sample rate", if (snapshot.sampleRateHz > 0) "${snapshot.sampleRateHz.roundToInt()} Hz" else "–")
        InfoRow("Noise (hold still)", if (snapshot.sampleCount > 0) "%.4f rad/s".format(snapshot.noise) else "–")
        InfoRow("Gravity", if (snapshot.gravity > 0) "%.2f m/s²".format(snapshot.gravity) else "–")

        val (statusText, statusColor) = when {
            !running && snapshot.sampleCount == 0L -> "Not started" to NovaColors.TextSecondary
            snapshot.passed -> "Responsive — ready for VR" to NovaColors.Success
            snapshot.sampleCount > 0 && snapshot.sampleRateHz in 1f..GyroTestSnapshot.MIN_RATE_HZ ->
                "Low sample rate: tracking may lag" to NovaColors.Warning
            else -> "Rotate around every axis…" to NovaColors.TextSecondary
        }
        InfoRow("Result", statusText, statusColor)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { running = !running }, shape = MaterialTheme.shapes.large) {
                Text(if (running) "Stop test" else "Start test")
            }
            OutlinedButton(
                onClick = { analyzer.resetOrientation(); snapshot = analyzer.snapshot() },
                enabled = running,
                shape = MaterialTheme.shapes.large,
            ) { Text("Center dot") }
        }
    }
}

/** A dot that follows the integrated phone rotation. */
@Composable
private fun OrientationPad(snapshot: GyroTestSnapshot, modifier: Modifier = Modifier) {
    val accent = NovaColors.Accent
    val outline = NovaColors.Outline
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = NovaColors.SurfaceHigh,
        border = BorderStroke(1.dp, NovaColors.Outline),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            drawLine(outline, Offset(c.x, 0f), Offset(c.x, size.height), strokeWidth = 2f)
            drawLine(outline, Offset(0f, c.y), Offset(size.width, c.y), strokeWidth = 2f)
            drawCircle(outline, radius = size.minDimension * 0.3f, center = c, style = Stroke(2f))
            // Map ±1.2 rad (≈ ±70°) to the pad edges.
            val dx = -snapshot.yaw / 1.2f * size.width / 2f
            val dy = -snapshot.pitch / 1.2f * size.height / 2f
            drawCircle(accent, radius = size.minDimension * 0.07f, center = Offset(c.x + dx, c.y + dy))
        }
    }
}

@Composable
private fun AxisBar(label: String, value: Float, passed: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (passed) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = "Axis responded",
                    tint = NovaColors.Success,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        val fraction = (abs(value) / 6f).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(NovaColors.SurfaceHigh, CircleShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(if (passed) NovaColors.Success else NovaColors.Accent, CircleShape),
            )
        }
    }
}
