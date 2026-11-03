/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.novavr.app.settings.VRSettingsRepository
import org.novavr.app.ui.CalibrationScreen
import org.novavr.app.ui.HomeScreen
import org.novavr.app.ui.SettingsScreen
import org.novavr.app.ui.theme.NovaVRTheme
import org.novavr.app.utils.DeviceInfo
import org.novavr.app.vr.VRActivity
import org.novavr.cardboard.CardboardNative
import org.novavr.cardboard.DeviceParams

private enum class Destination { HOME, SETTINGS, CALIBRATION }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repository = novaApp.settings

        setContent {
            NovaVRTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NovaVRApp(
                        repository = repository,
                        onStartVr = { calibration -> startActivity(VRActivity.intent(this, calibration)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NovaVRApp(repository: VRSettingsRepository, onStartVr: (calibration: Boolean) -> Unit) {
    val settings by repository.settings.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceInfo = remember { DeviceInfo.query(context) }
    val profileLensSeparationMm = remember {
        runCatching {
            CardboardNative.initialize(context)
            DeviceParams.describe(CardboardNative.headsetProfileParams())?.interLensDistanceMm
        }.getOrNull()
    }
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }

    BackHandler(enabled = destination != Destination.HOME) { destination = Destination.HOME }

    Crossfade(targetState = destination, label = "navigation", modifier = Modifier.safeDrawingPadding()) { screen ->
        when (screen) {
            Destination.HOME -> HomeScreen(
                settings = settings,
                deviceInfo = deviceInfo,
                onStartVr = { onStartVr(false) },
                onDisplayModeChange = { mode -> repository.update { it.copy(displayMode = mode) } },
                onScreenSizeChange = { v -> repository.update { it.copy(screenDiagonalInches = v) } },
                onScreenDistanceChange = { v -> repository.update { it.copy(screenDistanceMeters = v) } },
                onOpenCalibration = { destination = Destination.CALIBRATION },
                onOpenSettings = { destination = Destination.SETTINGS },
            )

            Destination.SETTINGS -> SettingsScreen(
                settings = settings,
                deviceInfo = deviceInfo,
                profileLensSeparationMm = profileLensSeparationMm,
                onUpdate = repository::update,
                onReset = repository::resetToDefaults,
                onBack = { destination = Destination.HOME },
            )

            Destination.CALIBRATION -> CalibrationScreen(
                settings = settings,
                deviceInfo = deviceInfo,
                profileLensSeparationMm = profileLensSeparationMm,
                onUpdate = repository::update,
                onStartEyeTest = { onStartVr(true) },
                onBack = { destination = Destination.HOME },
            )
        }
    }
}
