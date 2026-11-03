/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.novavr.app.input.Shortcuts
import org.novavr.app.input.accessibility.NovaAccessibilityService
import org.novavr.app.input.shizuku.ShizukuManager
import org.novavr.app.input.shizuku.ShizukuStatus
import org.novavr.app.novaApp
import org.novavr.app.settings.InputMode
import org.novavr.app.settings.RightClickAction
import org.novavr.app.settings.ShortcutModifier
import org.novavr.app.settings.VRSettings
import org.novavr.app.ui.theme.NovaColors
import kotlin.math.roundToInt

/** "Input" section of the Settings screen (0.3). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InputSettingsSection(settings: VRSettings, onUpdate: ((VRSettings) -> VRSettings) -> Unit) {
    val context = LocalContext.current
    val devices by context.novaApp.inputDevices.devices.collectAsStateWithLifecycle()
    val a11y by NovaAccessibilityService.instance.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { ShizukuManager.init(context) }
    val shizuku by ShizukuManager.status.collectAsStateWithLifecycle()
    var shizukuMessage by remember { mutableStateOf<String?>(null) }
    // Permissions may change in system settings while we are away.
    LifecycleResumeEffect(Unit) {
        ShizukuManager.refresh()
        onPauseOrDispose { }
    }

    SectionCard("Input devices") {
        InfoRow("Mouse", devices.mice.joinToString().ifEmpty { "Not connected" },
            if (devices.hasMouse) NovaColors.Success else NovaColors.TextSecondary)
        InfoRow("Keyboard", devices.keyboards.joinToString().ifEmpty { "Not connected" },
            if (devices.hasKeyboard) NovaColors.Success else NovaColors.TextSecondary)
        Text(
            "Pair a Bluetooth mouse/keyboard in Android settings, or connect them with a USB OTG adapter. " +
                "NovaVR uses whatever Android recognises.",
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )
    }

    SectionCard("Input routing") {
        Text("Interaction mode", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InputMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.inputMode == mode,
                    onClick = { onUpdate { it.copy(inputMode = mode) } },
                    label = {
                        Text(
                            when (mode) {
                                InputMode.AUTO -> "Auto"
                                InputMode.ACCESSIBILITY -> "Accessibility"
                                InputMode.SHIZUKU -> "Shizuku"
                                InputMode.VR_ONLY -> "VR only"
                            },
                        )
                    },
                )
            }
        }
        Text(
            when (settings.inputMode) {
                InputMode.AUTO -> "Uses Shizuku for the mouse when authorised, otherwise Accessibility. " +
                    "Typing always uses Accessibility."
                InputMode.ACCESSIBILITY -> "Clicks, drags and scrolling become touch gestures; typing edits the " +
                    "focused text field."
                InputMode.SHIZUKU -> "Real mouse events (hover, right click, wheel). Typing still needs Accessibility."
                InputMode.VR_ONLY -> "Cursor and NovaVR shortcuts only. Nothing is sent to other apps."
            },
            style = MaterialTheme.typography.bodySmall,
            color = NovaColors.TextSecondary,
        )

        val a11yEnabled = a11y != null
        InfoRow(
            "Accessibility service",
            if (a11yEnabled) "On" else "Off",
            if (a11yEnabled) NovaColors.Success else NovaColors.Warning,
        )
        if (!a11yEnabled) {
            Text(
                "Required to click and type in the mirrored app. Settings → Accessibility → NovaVR mouse & " +
                    "keyboard. If the switch is greyed out: App info → ⋮ → Allow restricted settings.",
                style = MaterialTheme.typography.bodySmall,
                color = NovaColors.TextSecondary,
            )
            OutlinedButton(
                onClick = { context.startActivity(NovaAccessibilityService.settingsIntent()) },
                shape = MaterialTheme.shapes.large,
            ) { Text("Open Accessibility settings") }
        }

        InfoRow(
            "Shizuku (optional)",
            when (shizuku) {
                ShizukuStatus.NotInstalled -> "Not installed"
                ShizukuStatus.NotRunning -> "Not running"
                ShizukuStatus.Unsupported -> "Update Shizuku"
                ShizukuStatus.PermissionRequired -> "Permission needed"
                ShizukuStatus.PermissionDenied -> "Permission denied"
                ShizukuStatus.Connecting -> "Authorised"
                is ShizukuStatus.Ready -> "Connected"
                is ShizukuStatus.Error -> "Error"
            },
            if (shizuku.authorized) NovaColors.Success else NovaColors.TextSecondary,
        )
        when (val s = shizuku) {
            ShizukuStatus.NotInstalled -> HintText(
                "Shizuku runs a helper with adb-shell rights (never root), started via Wireless debugging. " +
                    "With it the mouse behaves like a real mouse. Install it from its official source to use it.",
            )
            ShizukuStatus.NotRunning -> HintText("Open the Shizuku app and start it (Wireless debugging).")
            ShizukuStatus.PermissionRequired, ShizukuStatus.PermissionDenied -> {
                if (s == ShizukuStatus.PermissionDenied) {
                    HintText("Permission was denied. You can allow it again, or keep using Accessibility.")
                }
                OutlinedButton(onClick = ShizukuManager::requestPermission, shape = MaterialTheme.shapes.large) {
                    Text("Allow NovaVR in Shizuku")
                }
            }
            is ShizukuStatus.Error -> HintText(s.message)
            else -> Unit
        }
        if (shizuku.authorized && !a11yEnabled) {
            OutlinedButton(
                onClick = {
                    shizukuMessage = "Enabling…"
                    ShizukuManager.enableAccessibility(NovaAccessibilityService.component(context)) { error ->
                        shizukuMessage = error ?: "Accessibility service enabled"
                    }
                },
                shape = MaterialTheme.shapes.large,
            ) { Text("Enable Accessibility via Shizuku") }
        }
        shizukuMessage?.let { HintText(it) }
    }

    SectionCard("Mouse cursor") {
        SwitchRow(
            title = "Show cursor in VR",
            subtitle = null,
            checked = settings.cursorVisible,
            onCheckedChange = { v -> onUpdate { it.copy(cursorVisible = v) } },
        )
        LabeledSlider(
            label = "Cursor sensitivity",
            valueLabel = "%.2f×".format(settings.cursorSensitivity),
            value = settings.cursorSensitivity,
            range = VRSettings.SENSITIVITY_RANGE,
            onValueChange = { v -> onUpdate { it.copy(cursorSensitivity = (v * 20).roundToInt() / 20f) } },
        )
        LabeledSlider(
            label = "Scroll sensitivity",
            valueLabel = "%.2f×".format(settings.scrollSensitivity),
            value = settings.scrollSensitivity,
            range = VRSettings.SENSITIVITY_RANGE,
            onValueChange = { v -> onUpdate { it.copy(scrollSensitivity = (v * 20).roundToInt() / 20f) } },
        )
        Text("Hide cursor when idle", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VRSettings.AUTO_HIDE_CHOICES.forEach { seconds ->
                FilterChip(
                    selected = settings.cursorAutoHideSeconds == seconds,
                    onClick = { onUpdate { it.copy(cursorAutoHideSeconds = seconds) } },
                    label = { Text(if (seconds == 0) "Never" else "$seconds s") },
                )
            }
        }
        Text("Right click (Accessibility mode)", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RightClickAction.entries.forEach { action ->
                FilterChip(
                    selected = settings.rightClickAction == action,
                    onClick = { onUpdate { it.copy(rightClickAction = action) } },
                    label = { Text(if (action == RightClickAction.BACK) "Back" else "Long press") },
                )
            }
        }
    }

    SectionCard("Keyboard shortcuts") {
        Text("NovaVR shortcut modifier", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutModifier.entries.forEach { mod ->
                FilterChip(
                    selected = settings.shortcutModifier == mod,
                    onClick = { onUpdate { it.copy(shortcutModifier = mod) } },
                    label = { Text(if (mod == ShortcutModifier.DISABLED) "Off" else Shortcuts.modifierLabel(mod)) },
                )
            }
        }
        if (settings.shortcutModifier != ShortcutModifier.DISABLED) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Shortcuts.table(settings.shortcutModifier).forEach { (keys, cmd) -> InfoRow(cmd.label, keys) }
            }
        }
        Text("System keys", style = MaterialTheme.typography.bodyLarge)
        InfoRow("Back", "Esc · mouse back button · Meta+Backspace")
        InfoRow("Home", "Meta+Enter")
        InfoRow("Recent apps", "Alt+Tab · Meta+Tab")
        InfoRow("Notifications", "Meta+N")
        InfoRow("Clipboard", "Ctrl+A / C / X / V in text fields")
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = NovaColors.TextSecondary)
}
