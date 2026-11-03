/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.ui

import android.opengl.GLSurfaceView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.novavr.app.R
import org.novavr.app.settings.VRSettings
import org.novavr.app.ui.theme.NovaColors
import org.novavr.app.vr.VRSessionState
import kotlin.math.roundToInt

/** Actions available from the VR overlay. Null actions are not shown. */
class VRControls(
    val onExit: () -> Unit,
    val exitDescription: String,
    val onToggleMode: () -> Unit,
    val onRecenter: () -> Unit,
    val onAdjustSize: (steps: Int) -> Unit,
    val onAdjustDistance: (steps: Int) -> Unit,
    val onStartMirroring: (() -> Unit)? = null,
    val onStopMirroring: (() -> Unit)? = null,
)

/** Mirroring status shown inside VR (null when not mirroring). */
class MirrorOverlayInfo(val contentVisible: Boolean)

/**
 * VR viewport plus a minimal Cardboard-style overlay: a centre divider (used to
 * line up the headset) and small buttons near the top edge.
 *
 * Gestures on the screen (the Cardboard trigger button taps the screen):
 *  - Tap:        switch Portrait / Landscape
 *  - Long press: recenter the view
 *
 * Status messages are drawn once per eye so they can be read in the headset.
 */
@Composable
fun VROverlay(
    glView: GLSurfaceView,
    state: VRSessionState,
    settings: VRSettings,
    calibrationMode: Boolean,
    mirror: MirrorOverlayInfo?,
    controls: VRControls,
    inputLine: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    var showTuning by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())

        // Gesture layer above the GL surface.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(controls) {
                    detectTapGestures(
                        onTap = { controls.onToggleMode() },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            controls.onRecenter()
                        },
                    )
                },
        )

        // Centre divider: aligns the phone with the headset's nose bridge.
        Box(
            Modifier
                .align(Alignment.Center)
                .width(2.dp)
                .fillMaxHeight(0.8f)
                .background(Color.White.copy(alpha = 0.35f)),
        )

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OverlayButton(R.drawable.ic_close, controls.exitDescription, controls.onExit)
                controls.onStopMirroring?.let { OverlayButton(R.drawable.ic_stop, "Stop screen mirroring", it) }
                controls.onStartMirroring?.let { OverlayButton(R.drawable.ic_cast, "Start screen mirroring", it) }
                OverlayButton(R.drawable.ic_swap, "Switch display mode", controls.onToggleMode)
                OverlayButton(R.drawable.ic_recenter, "Recenter view", controls.onRecenter)
                OverlayButton(R.drawable.ic_tune, "Screen size and distance") { showTuning = !showTuning }
            }
            if (showTuning) {
                Row(
                    Modifier
                        .padding(top = 6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StepControl(
                        label = "Size ${settings.screenDiagonalInches.roundToInt()}\"",
                        onMinus = { controls.onAdjustSize(-1) },
                        onPlus = { controls.onAdjustSize(1) },
                    )
                    StepControl(
                        label = "Distance %.2f m".format(settings.screenDistanceMeters),
                        onMinus = { controls.onAdjustDistance(-1) },
                        onPlus = { controls.onAdjustDistance(1) },
                    )
                }
            }
        }

        val message = when {
            mirror != null && !mirror.contentVisible ->
                "The shared app is hidden.\nOpen it again, or tap ✕ and use the notification."
            mirror != null && !state.hasCapturedFrame -> "Waiting for the shared screen…"
            mirror != null && state.captureBlank ->
                "The shared screen is black.\nIf you chose “Entire screen”, NovaVR hides itself from the " +
                    "capture to avoid an endless mirror. Tap ■ and start again with “A single app”."
            else -> null
        }
        if (message != null) {
            // One copy per eye.
            Row(Modifier.fillMaxSize()) {
                repeat(2) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                                .padding(12.dp),
                        )
                    }
                }
            }
        }

        Text(
            text = buildString {
                if (calibrationMode) append("Calibration  ·  ")
                if (mirror != null) append("Mirroring  ·  ")
                if (state.fps > 0f) append("${state.fps.roundToInt()} fps")
                if (mirror != null && state.hasCapturedFrame) append("  ·  capture ${state.captureFps.roundToInt()} fps")
                if (inputLine != null) append("  ·  $inputLine")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )

        val error = state.errorMessage
            ?: if (state.portraitSurface) "Rotate the device to landscape to use the headset." else null
        if (error != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(NovaColors.Surface, MaterialTheme.shapes.medium)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("VR is not available", style = MaterialTheme.typography.titleMedium)
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Button(onClick = controls.onExit) { Text("Close") }
            }
        }
    }
}

@Composable
private fun StepControl(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = onMinus) { Text("−", color = Color.White) }
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White)
        TextButton(onClick = onPlus) { Text("+", color = Color.White) }
    }
}

@Composable
private fun OverlayButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.4f),
            contentColor = Color.White.copy(alpha = 0.8f),
        ),
        modifier = Modifier.padding(horizontal = 5.dp).size(40.dp),
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
