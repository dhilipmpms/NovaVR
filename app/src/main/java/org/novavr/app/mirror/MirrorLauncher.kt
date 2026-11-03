/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.mirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Walks the user through everything screen mirroring needs, in order:
 *  1. "Display over other apps" (special access; opens system settings),
 *  2. notification permission (Android 13+, optional: only for the ongoing
 *     notification with Stop / Show VR actions),
 *  3. the MediaProjection consent dialog,
 * then starts [ScreenMirrorService] with the consent result.
 */
class MirrorLauncher internal constructor(private val context: Context) {
    internal var projectionLauncher: ManagedActivityResultLauncher<Intent, ActivityResult>? = null
    internal var notificationLauncher: ManagedActivityResultLauncher<String, Boolean>? = null

    var showOverlayPermissionDialog by mutableStateOf(false)
        internal set
    var message by mutableStateOf<String?>(null)
        internal set
    private var notificationsAsked = false

    fun start() {
        if (!ScreenMirrorService.isSupported) {
            message = "Screen mirroring needs Android 14 or newer (single-app screen sharing)."
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            showOverlayPermissionDialog = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationsAsked = true
            notificationLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
            return // continues in the permission callback
        }
        requestCapture()
    }

    internal fun requestCapture() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        // Default config lets the user pick "A single app" or "Entire screen".
        projectionLauncher?.launch(mpm.createScreenCaptureIntent())
    }

    internal fun openOverlaySettings() {
        showOverlayPermissionDialog = false
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun dismissMessage() {
        message = null
    }
}

/**
 * @param onStarted called after the service was started with a valid
 *   consent (e.g. a VR activity can finish itself so the overlay takes over).
 */
@Composable
fun rememberMirrorLauncher(onStarted: () -> Unit = {}): MirrorLauncher {
    val context = LocalContext.current
    val launcher = remember(context) { MirrorLauncher(context) }
    launcher.projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                context,
                ScreenMirrorService.startIntent(context, result.resultCode, data),
            )
            onStarted()
        } else {
            launcher.message = "Screen sharing was cancelled."
        }
    }
    launcher.notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { launcher.requestCapture() }
    return launcher
}

/** Dialogs owned by a [MirrorLauncher]; place once in the screen hosting it. */
@Composable
fun MirrorLauncherDialogs(launcher: MirrorLauncher) {
    if (launcher.showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { launcher.showOverlayPermissionDialog = false },
            title = { Text("Allow display over other apps") },
            text = {
                Text(
                    "Android only keeps an app running and drawing while it is on screen. To show a " +
                        "shared app in VR without pausing it, NovaVR draws the VR view on top of it.\n\n" +
                        "Enable “Allow display over other apps” for NovaVR, then come back and start " +
                        "mirroring again.",
                )
            },
            confirmButton = { TextButton(onClick = launcher::openOverlaySettings) { Text("Open settings") } },
            dismissButton = {
                TextButton(onClick = { launcher.showOverlayPermissionDialog = false }) { Text("Not now") }
            },
        )
    }
    val message = launcher.message
    if (message != null) {
        AlertDialog(
            onDismissRequest = launcher::dismissMessage,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = launcher::dismissMessage) { Text("OK") } },
        )
    }
}
