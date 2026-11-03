/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input.shizuku

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import org.novavr.app.input.MouseButtons
import org.novavr.app.input.PointerBackend
import org.novavr.app.input.SystemAction

/**
 * Real mouse events injected through the Shizuku user service: hover, primary
 * and secondary buttons (apps see a true right click), live drags and wheel
 * scrolling with `AXIS_VSCROLL` / `AXIS_HSCROLL`. Calls are one-way binder
 * transactions on the main thread; injection itself is asynchronous.
 */
class ShizukuPointerBackend : PointerBackend {
    override val name = "Shizuku"
    override val nativeMouse = true

    private var downTime = 0L
    private var buttons = 0
    private var lastHoverMs = 0L

    override fun buttonDown(x: Float, y: Float, button: Int, buttonState: Int) {
        // Back/Forward buttons are system navigation, handled by the router.
        if (button == MouseButtons.BACK || button == MouseButtons.FORWARD) return
        val first = buttons == 0
        buttons = buttonState
        if (first) {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, x, y, buttonState, 0)
        }
        send(MotionEvent.ACTION_BUTTON_PRESS, x, y, buttonState, button)
    }

    override fun move(x: Float, y: Float, buttonState: Int) {
        if (buttonState == 0) {
            // Hover at most every 8 ms: smooth for apps, light on IPC.
            val now = SystemClock.uptimeMillis()
            if (now - lastHoverMs < HOVER_INTERVAL_MS) return
            lastHoverMs = now
            send(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0)
        } else {
            send(MotionEvent.ACTION_MOVE, x, y, buttonState, 0)
        }
    }

    override fun buttonUp(x: Float, y: Float, button: Int, buttonState: Int) {
        if (buttons == 0 || button == MouseButtons.BACK || button == MouseButtons.FORWARD) return
        send(MotionEvent.ACTION_BUTTON_RELEASE, x, y, buttonState, button)
        buttons = buttonState
        if (buttonState == 0) {
            send(MotionEvent.ACTION_UP, x, y, 0, 0)
            send(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0)
        }
    }

    override fun scroll(x: Float, y: Float, vertical: Float, horizontal: Float) {
        ShizukuManager.withInjector {
            it.injectMouse(MotionEvent.ACTION_SCROLL, x, y, buttons, 0, vertical, horizontal, 0)
        }
    }

    override fun cancel() {
        buttons = 0
    }

    private fun send(action: Int, x: Float, y: Float, buttonState: Int, actionButton: Int) {
        ShizukuManager.withInjector {
            it.injectMouse(action, x, y, buttonState, actionButton, 0f, 0f, downTime)
        }
    }

    private companion object {
        const val HOVER_INTERVAL_MS = 8L
    }
}

/**
 * System navigation through Shizuku key injection. Only keys the system
 * handles *before* window dispatch work this way (Home, Recents); Back and
 * typing would be delivered to the focused window, which is NovaVR's overlay.
 */
object ShizukuSystemKeys {
    fun perform(action: SystemAction): Boolean {
        val keyCode = when (action) {
            SystemAction.HOME -> KeyEvent.KEYCODE_HOME
            SystemAction.RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
            else -> return false
        }
        return ShizukuManager.withInjector {
            it.injectKey(KeyEvent.ACTION_DOWN, keyCode, 0) && it.injectKey(KeyEvent.ACTION_UP, keyCode, 0)
        } == true
    }
}
