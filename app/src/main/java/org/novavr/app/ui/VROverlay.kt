/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.novavr.app.ui.theme.NovaColors

/**
 * VR viewport plus a minimal Cardboard-style overlay: a centre divider (used to
 * line up the headset) and three small buttons near the top edge.
 *
 * Gestures on the screen (the Cardboard trigger button taps the screen):
 *  - Tap:        switch Portrait / Landscape
 *  - Long press: recenter the view
 */
@Composable
fun VROverlay(
    glView: GLSurfaceView,
    calibrationMode: Boolean,
    fps: Float,
    errorMessage: String?,
    onExit: () -> Unit,
    onToggleMode: () -> Unit,
    onRecenter: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    BackHandler(onBack = onExit)

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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onToggleMode() },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRecenter()
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

        Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OverlayButton(R.drawable.ic_close, "Exit VR", onExit)
            OverlayButton(R.drawable.ic_swap, "Switch display mode", onToggleMode)
            OverlayButton(R.drawable.ic_recenter, "Recenter view", onRecenter)
        }

        Text(
            text = buildString {
                if (calibrationMode) append("Calibration  ·  ")
                append(if (fps > 0f) "${fps.toInt()} fps" else "")
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )

        if (errorMessage != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .background(NovaColors.Surface, MaterialTheme.shapes.medium)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("VR could not start", style = MaterialTheme.typography.titleMedium)
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NovaColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Button(onClick = onExit) { Text("Back") }
            }
        }
    }
}

@Composable
private fun OverlayButton(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.4f),
            contentColor = Color.White.copy(alpha = 0.8f),
        ),
        modifier = Modifier.padding(horizontal = 6.dp).size(40.dp),
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
