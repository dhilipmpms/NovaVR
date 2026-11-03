/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.novavr.app.settings.VRSettings

/**
 * Splits physical mouse/keyboard input between three destinations:
 *
 *  1. **NovaVR's own interface** — keyboard shortcuts (configurable modifier)
 *     and the VR dock below the virtual screen;
 *  2. **system navigation** — Esc/Back, Home, Recents, Notifications, mouse
 *     Back button;
 *  3. **the mirrored app** — clicks, drags, wheel and typing, through the
 *     active [PointerBackend] / [KeyBackend].
 *
 * The mouse arrives as *captured* pointer events (relative motion, system
 * arrow hidden) because the VR host requested pointer capture. The router
 * moves the virtual [cursor] in content pixels and converts it to display
 * pixels through the [ContentRegion] for injection.
 *
 * Main thread only. Never blocks: backends are asynchronous.
 */
class InputRouter(
    val cursor: CursorModel,
    private val dock: List<NovaCommand>,
    private val settings: () -> VRSettings,
    private val onCommand: (NovaCommand) -> Unit,
    /** Display mapping of the mirrored content; null when nothing is mirrored. */
    private val region: () -> ContentRegion?,
) {
    var pointerBackend: PointerBackend? = null
        set(value) {
            if (field !== value) field?.cancel()
            field = value
        }
    var keyBackend: KeyBackend? = null

    /** Fallback for system actions when no key backend can perform them. */
    var systemFallback: ((SystemAction) -> Boolean)? = null

    private var buttonState = 0

    /** Buttons whose press landed on the dock: their release stays with NovaVR. */
    private var dockButtons = 0
    private val consumedKeys = HashSet<Int>()
    private var dragRegion: ContentRegion? = null

    // Pointer ------------------------------------------------------------------

    /** Handles a captured (relative) mouse event. Always consumes it. */
    fun onCapturedPointer(event: MotionEvent): Boolean {
        val s = settings()
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL) * s.scrollSensitivity
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL) * s.scrollSensitivity
                onScroll(v, h)
                return true
            }
        }

        // Relative motion, including batched samples.
        var dx = 0f
        var dy = 0f
        for (i in 0 until event.historySize) {
            dx += event.getHistoricalX(i)
            dy += event.getHistoricalY(i)
        }
        dx += event.x
        dy += event.y
        if (dx != 0f || dy != 0f) {
            cursor.moveBy(dx * s.cursorSensitivity, dy * s.cursorSensitivity)
            cursor.setDockHover(dockIndex())
            if (!cursor.inDock) {
                display(dragRegion ?: region())?.let { (x, y) -> pointerBackend?.move(x, y, buttonState) }
            }
        }

        // Button transitions (works for DOWN/UP and BUTTON_PRESS/RELEASE alike).
        val newState = event.buttonState
        if (newState != buttonState) {
            val pressed = newState and buttonState.inv()
            val released = buttonState and newState.inv()
            for (button in BUTTONS) {
                if (pressed and button != 0) onButton(button, down = true, newState)
                if (released and button != 0) onButton(button, down = false, newState)
            }
            buttonState = newState
            cursor.setButtons(newState)
        }
        return true
    }

    private fun onButton(button: Int, down: Boolean, newState: Int) {
        if (down && android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
            android.util.Log.d(TAG, "press $button cursor=(${cursor.x}, ${cursor.y}) dock=${cursor.inDock}")
        }
        if (down && cursor.inDock) {
            dockButtons = dockButtons or button
            if (button == MouseButtons.PRIMARY) {
                dock.getOrNull(dockIndex())?.let(onCommand)
                cursor.feedback(CursorFeedback.Kind.LEFT_CLICK)
            }
            return
        }
        if (!down && dockButtons and button != 0) {
            dockButtons = dockButtons and button.inv()
            return
        }
        if (down) {
            when (button) {
                MouseButtons.PRIMARY -> cursor.feedback(CursorFeedback.Kind.LEFT_CLICK)
                MouseButtons.SECONDARY -> cursor.feedback(CursorFeedback.Kind.RIGHT_CLICK)
            }
        }
        if (button == MouseButtons.BACK && down) {
            system(SystemAction.BACK)
            return
        }
        if (button == MouseButtons.FORWARD) return

        // Freeze the mapping for the whole press so a drag stays consistent.
        if (down && buttonState == 0) dragRegion = region()
        val point = display(dragRegion ?: region()) ?: return
        val backend = pointerBackend ?: return
        if (android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
            android.util.Log.d(TAG, "button $button ${if (down) "down" else "up"} at $point via ${backend.name}")
        }
        if (down) backend.buttonDown(point.first, point.second, button, newState)
        else backend.buttonUp(point.first, point.second, button, newState)
        if (newState == 0) dragRegion = null
    }

    private fun onScroll(vertical: Float, horizontal: Float) {
        if (vertical == 0f && horizontal == 0f) return
        cursor.feedback(if (vertical >= 0f) CursorFeedback.Kind.SCROLL_UP else CursorFeedback.Kind.SCROLL_DOWN)
        if (cursor.inDock) return
        val (x, y) = display(region()) ?: return
        pointerBackend?.scroll(x, y, vertical, horizontal)
    }

    private fun dockIndex(): Int =
        if (!cursor.inDock || dock.isEmpty()) -1
        else DockLayout.itemAt(
            cursor.x / cursor.contentWidth,
            cursor.contentWidth.toFloat() / cursor.contentHeight,
            dock.size,
        )

    private fun display(r: ContentRegion?): Pair<Float, Float>? {
        r ?: return null
        val sx = r.contentWidth.toFloat() / cursor.contentWidth
        val sy = r.contentHeight.toFloat() / cursor.contentHeight
        return r.toDisplay(cursor.x * sx, cursor.y.coerceAtMost(cursor.contentHeight - 1f) * sy)
    }

    // Keyboard -----------------------------------------------------------------

    /**
     * Handles a key from a physical keyboard. Returns false for keys NovaVR has
     * no use for, so they keep their normal system behaviour (volume, media...).
     */
    fun onKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            // Deliver key-ups for keys whose key-down went to a text field.
            if (consumedKeys.remove(event.keyCode)) {
                keyBackend?.handleKey(event)
                return true
            }
            return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val consumed = routeKeyDown(event)
        if (consumed) consumedKeys += event.keyCode
        return consumed
    }

    private fun routeKeyDown(event: KeyEvent): Boolean {
        val s = settings()
        // 1. NovaVR's own interface.
        Shortcuts.novaCommand(event.keyCode, event.metaState, s.shortcutModifier)?.let {
            if (event.repeatCount == 0) onCommand(it)
            return true
        }
        if (region() == null) return false // nothing mirrored: leave the rest alone

        // 2. System navigation.
        Shortcuts.systemAction(event.keyCode, event.metaState)?.let {
            if (event.repeatCount == 0) system(it)
            return true
        }

        // 3. The mirrored app: text field first, then keyboard navigation.
        when (keyBackend?.handleKey(event)) {
            KeyResult.HANDLED -> return true
            KeyResult.NO_TEXT_FIELD -> return navigationKey(event)
            KeyResult.NOT_HANDLED, null -> Unit
        }
        return false
    }

    /** Without a focused text field, paging keys scroll the content under the cursor. */
    private fun navigationKey(event: KeyEvent): Boolean {
        val shift = event.metaState and KeyEvent.META_SHIFT_ON != 0
        val ticks = when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN -> -PAGE_TICKS
            KeyEvent.KEYCODE_PAGE_UP -> PAGE_TICKS
            KeyEvent.KEYCODE_SPACE -> if (shift) PAGE_TICKS else -PAGE_TICKS
            KeyEvent.KEYCODE_DPAD_DOWN -> -1f
            KeyEvent.KEYCODE_DPAD_UP -> 1f
            else -> return false
        }
        onScroll(ticks, 0f)
        return true
    }

    private fun system(action: SystemAction) {
        val done = keyBackend?.performSystemAction(action) == true
        if (!done) systemFallback?.invoke(action)
    }

    /** Releases any held button (e.g. when VR is hidden mid-drag). */
    fun reset() {
        pointerBackend?.cancel()
        buttonState = 0
        dockButtons = 0
        dragRegion = null
        consumedKeys.clear()
        cursor.setButtons(0)
    }

    private companion object {
        const val TAG = "NovaVR-Input"
        val BUTTONS = intArrayOf(
            MouseButtons.PRIMARY, MouseButtons.SECONDARY, MouseButtons.TERTIARY,
            MouseButtons.BACK, MouseButtons.FORWARD,
        )
        const val PAGE_TICKS = 4f
    }
}
