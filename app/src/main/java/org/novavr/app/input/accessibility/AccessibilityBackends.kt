/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.novavr.app.input.KeyBackend
import org.novavr.app.input.KeyResult
import org.novavr.app.input.MouseButtons
import org.novavr.app.input.PointerBackend
import org.novavr.app.input.SystemAction
import org.novavr.app.input.TextEdits
import org.novavr.app.input.TextState
import org.novavr.app.settings.RightClickAction
import kotlin.math.abs

/**
 * Mouse → touch emulation with [AccessibilityService.dispatchGesture].
 *
 * The primary button is streamed as one *live* touch stroke: touch-down when
 * the button goes down, continued stroke segments while the mouse moves, lift
 * when it goes up. Apps therefore see real press timing (tap, long-press,
 * drag, swipe) instead of a synthesised tap after the fact. Segments are
 * dispatched one at a time (a new gesture would cancel the running one), each
 * lasting as long as the real time that elapsed, so the replay lags the mouse
 * by about one segment.
 *
 * The wheel becomes short drags that end with a brief hold, so lists scroll by
 * a predictable amount without flinging.
 */
class AccessibilityPointerBackend(
    private val service: AccessibilityService,
    private val rightClick: () -> RightClickAction,
    private val bounds: () -> android.graphics.RectF,
) : PointerBackend {

    override val name = "Accessibility"
    override val nativeMouse = false

    private val handler = Handler(Looper.getMainLooper())

    // Live primary-button stroke.
    private var stroke: GestureDescription.StrokeDescription? = null
    private var strokeActive = false
    private var inFlight = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var pendingX = 0f
    private var pendingY = 0f
    private var pendingMove = false
    private var pendingUp = false

    // Wheel.
    private var scrollInFlight = false
    private var pendingScrollV = 0f
    private var pendingScrollH = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    override fun buttonDown(x: Float, y: Float, button: Int, buttonState: Int) {
        when (button) {
            MouseButtons.PRIMARY -> startStroke(x, y)
            MouseButtons.SECONDARY -> when (rightClick()) {
                RightClickAction.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                RightClickAction.LONG_PRESS -> dispatchSimple(point(x, y), LONG_PRESS_MS)
            }
            MouseButtons.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            // Middle button: no touch equivalent; intentionally unused.
        }
    }

    override fun move(x: Float, y: Float, buttonState: Int) {
        if (!strokeActive) return // no hover in touch emulation
        pendingX = x; pendingY = y; pendingMove = true
        pump()
    }

    override fun buttonUp(x: Float, y: Float, button: Int, buttonState: Int) {
        if (button != MouseButtons.PRIMARY || !strokeActive) return
        pendingX = x; pendingY = y; pendingUp = true
        pump()
    }

    override fun cancel() {
        strokeActive = false
        stroke = null
        pendingMove = false
        pendingUp = false
        pendingScrollV = 0f
        pendingScrollH = 0f
    }

    private fun startStroke(x: Float, y: Float) {
        val (cx, cy) = clamp(x, y)
        cancel()
        val s = GestureDescription.StrokeDescription(point(cx, cy), 0, 1, true)
        strokeActive = true
        lastX = cx; lastY = cy; lastTime = SystemClock.uptimeMillis()
        dispatchStroke(s)
    }

    /** Dispatches the next stroke segment once the previous one has completed. */
    private fun pump() {
        if (inFlight || !strokeActive) return
        val current = stroke ?: return
        if (!pendingMove && !pendingUp) return
        val end = pendingUp
        val (x, y) = clamp(pendingX, pendingY)
        pendingMove = false
        pendingUp = false
        val now = SystemClock.uptimeMillis()
        val duration = (now - lastTime).coerceIn(1, MAX_SEGMENT_MS)
        val path = Path().apply { moveTo(lastX, lastY); lineTo(x, y) }
        lastX = x; lastY = y; lastTime = now
        val next = try {
            current.continueStroke(path, 0, duration, !end)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "continueStroke failed", e)
            cancel()
            return
        }
        if (end) strokeActive = false
        dispatchStroke(next)
    }

    private fun dispatchStroke(s: GestureDescription.StrokeDescription) {
        inFlight = true
        stroke = s
        val ok = service.dispatchGesture(
            GestureDescription.Builder().addStroke(s).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "stroke completed (continues=${s.willContinue()})")
                    inFlight = false
                    if (!s.willContinue()) stroke = null
                    pump()
                    pumpScroll()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "stroke cancelled by the system")
                    inFlight = false
                    cancel()
                    pumpScroll()
                }
            },
            handler,
        )
        if (!ok) {
            Log.w(TAG, "dispatchGesture rejected")
            inFlight = false
            cancel()
        }
    }

    override fun scroll(x: Float, y: Float, vertical: Float, horizontal: Float) {
        pendingScrollV += vertical
        pendingScrollH += horizontal
        scrollX = x; scrollY = y
        pumpScroll()
    }

    private fun pumpScroll() {
        if (scrollInFlight || inFlight || strokeActive) return
        if (abs(pendingScrollV) < 0.01f && abs(pendingScrollH) < 0.01f) return
        val b = bounds()
        val (x, y) = clamp(scrollX, scrollY)
        // Wheel up (positive) shows earlier content: the finger moves down.
        val maxDy = b.height() * 0.4f
        val maxDx = b.width() * 0.4f
        val dy = (pendingScrollV * SCROLL_STEP_PX).coerceIn(-maxDy, maxDy)
        val dx = (pendingScrollH * SCROLL_STEP_PX).coerceIn(-maxDx, maxDx)
        pendingScrollV = 0f
        pendingScrollH = 0f
        // Keep the stroke inside the content: start on the opposite side if needed.
        val startY = (y - dy / 2f).coerceIn(b.top + 1f, b.bottom - 1f)
        val endY = (startY + dy).coerceIn(b.top + 1f, b.bottom - 1f)
        val startX = (x - dx / 2f).coerceIn(b.left + 1f, b.right - 1f)
        val endX = (startX + dx).coerceIn(b.left + 1f, b.right - 1f)

        val movePath = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val first = GestureDescription.StrokeDescription(movePath, 0, SCROLL_MOVE_MS, true)
        scrollInFlight = true
        val ok = service.dispatchGesture(
            GestureDescription.Builder().addStroke(first).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    // Hold still before lifting so the list does not fling.
                    val hold = first.continueStroke(point(endX, endY), 0, SCROLL_HOLD_MS, false)
                    val ok2 = service.dispatchGesture(
                        GestureDescription.Builder().addStroke(hold).build(),
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) = done()
                            override fun onCancelled(gestureDescription: GestureDescription?) = done()
                        },
                        handler,
                    )
                    if (!ok2) done()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) = done()
            },
            handler,
        )
        if (!ok) scrollInFlight = false
    }

    private fun done() {
        scrollInFlight = false
        pump()
        pumpScroll()
    }

    private fun dispatchSimple(path: Path, durationMs: Long) {
        service.dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
            null,
            null,
        )
    }

    private fun clamp(x: Float, y: Float): Pair<Float, Float> {
        val b = bounds()
        return x.coerceIn(b.left, b.right - 1f) to y.coerceIn(b.top, b.bottom - 1f)
    }

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private companion object {
        const val TAG = "NovaVR-A11yPointer"
        const val LONG_PRESS_MS = 650L
        const val MAX_SEGMENT_MS = 500L
        const val SCROLL_STEP_PX = 110f
        const val SCROLL_MOVE_MS = 90L
        const val SCROLL_HOLD_MS = 60L
    }
}

/**
 * Typing into the shared app through Accessibility.
 *
 * While NovaVR's VR overlay owns the pointer (pointer capture needs window
 * focus), hardware key events are delivered to NovaVR, not to the app — and
 * keys injected by any means would also go to the focused window, i.e. back to
 * NovaVR. Instead, each key is applied directly to the app's focused text field:
 * the field's text and selection are read, edited ([TextEdits]) and written
 * back with `ACTION_SET_TEXT` / `ACTION_SET_SELECTION`. Clipboard shortcuts map
 * to `ACTION_COPY` / `CUT` / `PASTE`; Tab moves focus; Enter triggers the
 * field's IME action (Android 11+).
 */
class AccessibilityKeyBackend(private val service: NovaAccessibilityService) : KeyBackend {

    override val name = "Accessibility"

    /** Password fields don't expose their text; keep what we typed. */
    private var passwordNodeKey: Int? = null
    private var passwordShadow = ""

    override fun handleKey(event: KeyEvent): KeyResult {
        val node = service.focusedEditable() ?: return KeyResult.NO_TEXT_FIELD
        if (event.action != KeyEvent.ACTION_DOWN) return KeyResult.HANDLED
        val meta = event.metaState
        val ctrl = meta and KeyEvent.META_CTRL_ON != 0
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        val state = readState(node)

        val result: TextState? = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> TextEdits.backspace(state, word = ctrl)
            KeyEvent.KEYCODE_FORWARD_DEL -> TextEdits.delete(state, word = ctrl)
            KeyEvent.KEYCODE_DPAD_LEFT -> TextEdits.moveHorizontal(state, -1, extend = shift, word = ctrl)
            KeyEvent.KEYCODE_DPAD_RIGHT -> TextEdits.moveHorizontal(state, 1, extend = shift, word = ctrl)
            KeyEvent.KEYCODE_MOVE_HOME -> TextEdits.moveToLineEdge(state, end = false, extend = shift, document = ctrl)
            KeyEvent.KEYCODE_MOVE_END -> TextEdits.moveToLineEdge(state, end = true, extend = shift, document = ctrl)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!node.isMultiLine) return KeyResult.NOT_HANDLED
                moveLine(node, down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN, extend = shift)
                return KeyResult.HANDLED
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (node.isMultiLine && !ctrl) {
                    TextEdits.insert(state, "\n")
                } else {
                    imeEnter(node)
                    return KeyResult.HANDLED
                }
            }
            KeyEvent.KEYCODE_TAB -> {
                moveFocus(node, backward = shift)
                return KeyResult.HANDLED
            }
            else -> {
                if (ctrl) return clipboard(node, state, event.keyCode)
                val ch = event.getUnicodeChar(meta)
                if (ch == 0 || ch and KeyCharacterMapDeadKey != 0) return KeyResult.NOT_HANDLED
                TextEdits.insert(state, String(Character.toChars(ch)))
            }
        }
        if (result != null) apply(node, state, result)
        return KeyResult.HANDLED
    }

    override fun performSystemAction(action: SystemAction): Boolean = service.performGlobalAction(
        when (action) {
            SystemAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            SystemAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            SystemAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            SystemAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        },
    )

    private fun readState(node: AccessibilityNodeInfo): TextState {
        val raw = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val text = if (node.isPassword) passwordText(node, raw) else raw
        return TextState(text, node.textSelectionStart, node.textSelectionEnd).normalized()
    }

    private fun passwordText(node: AccessibilityNodeInfo, raw: String): String {
        val key = node.hashCode()
        if (passwordNodeKey != key || raw.length != passwordShadow.length) {
            // Unknown existing content: start from what is visible (usually empty).
            passwordNodeKey = key
            passwordShadow = if (raw.isEmpty()) "" else raw
        }
        return passwordShadow
    }

    private fun apply(node: AccessibilityNodeInfo, before: TextState, after: TextState) {
        if (after.text != before.text) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, after.text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (node.isPassword) passwordShadow = after.text
        }
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, after.selStart)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, after.selEnd)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
    }

    private fun clipboard(node: AccessibilityNodeInfo, state: TextState, keyCode: Int): KeyResult {
        when (keyCode) {
            KeyEvent.KEYCODE_A -> apply(node, state, TextEdits.selectAll(state))
            KeyEvent.KEYCODE_C -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            KeyEvent.KEYCODE_X -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            KeyEvent.KEYCODE_V -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            else -> return KeyResult.NOT_HANDLED
        }
        return KeyResult.HANDLED
    }

    private fun moveLine(node: AccessibilityNodeInfo, down: Boolean, extend: Boolean) {
        val args = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
                AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE,
            )
            putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extend)
        }
        node.performAction(
            if (down) {
                AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
            } else {
                AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
            },
            args,
        )
    }

    private fun imeEnter(node: AccessibilityNodeInfo) {
        val imeEnter = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        } else {
            null
        }
        if (imeEnter != null && node.actionList.any { it.id == imeEnter }) {
            node.performAction(imeEnter)
        } else {
            // No IME action exposed: fall back to inserting a newline.
            val state = readState(node)
            apply(node, state, TextEdits.insert(state, "\n"))
        }
    }

    private fun moveFocus(node: AccessibilityNodeInfo, backward: Boolean) {
        val next = node.focusSearch(if (backward) View.FOCUS_BACKWARD else View.FOCUS_FORWARD)
            ?: return
        next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (next.isEditable) {
            // Put the caret at the end of the newly focused field.
            val len = next.text?.length ?: 0
            next.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
                },
            )
        }
    }

    private companion object {
        const val KeyCharacterMapDeadKey = android.view.KeyCharacterMap.COMBINING_ACCENT
    }
}
