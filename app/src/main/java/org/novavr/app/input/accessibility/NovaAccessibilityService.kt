/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NovaVR's Accessibility service: the least-privileged way to act on other apps.
 *
 * Used only while the user mirrors an app in VR with a mouse/keyboard:
 *  - [android.accessibilityservice.AccessibilityService.dispatchGesture] turns
 *    mouse clicks, drags and wheel scrolls into touch gestures;
 *  - node actions (set text / selection, copy, paste...) implement typing into
 *    the focused text field of the shared app;
 *  - global actions implement Back / Home / Recents / Notifications.
 *
 * It does not log, store or transmit anything. It subscribes to no events: all
 * queries are made on demand when the user presses a key.
 */
class NovaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _instance.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (_instance.value === this) _instance.value = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Application windows of other apps, largest first. */
    private fun foreignAppWindows(): List<AccessibilityWindowInfo> =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .filter { it.root?.packageName?.toString() != packageName }
            .sortedByDescending { w -> Rect().also { w.getBoundsInScreen(it) }.let { it.width() * it.height() } }

    /** Screen bounds (l, t, r, b) of the shared app's main window, if visible. */
    fun appWindowBounds(): IntArray? {
        val w = foreignAppWindows().firstOrNull() ?: return null
        val r = Rect().also { w.getBoundsInScreen(it) }
        return intArrayOf(r.left, r.top, r.right, r.bottom)
    }

    /** The editable view with input focus in the shared app, if any. */
    fun focusedEditable(): AccessibilityNodeInfo? {
        for (window in foreignAppWindows()) {
            val focus = window.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (focus.isEditable) return focus
        }
        return null
    }

    companion object {
        private val _instance = MutableStateFlow<NovaAccessibilityService?>(null)

        /** The connected service, or null when disabled / not yet bound. */
        val instance: StateFlow<NovaAccessibilityService?> = _instance.asStateFlow()

        fun component(context: Context) = ComponentName(context, NovaAccessibilityService::class.java)

        /** Whether the user switched the service on in system settings. */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = component(context)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }

        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
