/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Delivers pointer input to the mirrored app. Coordinates are display pixels.
 * Implementations: Accessibility gestures (touch emulation) and Shizuku
 * (native mouse events). All calls happen on the main thread and must not block.
 */
interface PointerBackend {
    val name: String

    /** Native mouse semantics (hover, real right click, wheel). */
    val nativeMouse: Boolean

    fun buttonDown(x: Float, y: Float, button: Int, buttonState: Int)
    fun move(x: Float, y: Float, buttonState: Int)
    fun buttonUp(x: Float, y: Float, button: Int, buttonState: Int)

    /** Wheel ticks (positive = up / left), already scaled by sensitivity. */
    fun scroll(x: Float, y: Float, vertical: Float, horizontal: Float)

    /** Cancels any in-progress press (e.g. VR hidden mid-drag). */
    fun cancel()
}

/** Typing and system navigation for the mirrored app. */
interface KeyBackend {
    val name: String

    /** Returns true when the key was delivered (and must not propagate). */
    fun handleKey(event: KeyEvent): KeyResult

    fun performSystemAction(action: SystemAction): Boolean
}

enum class KeyResult {
    /** Delivered to a text field / handled. */
    HANDLED,

    /** No editable field has focus; the router may use the key for navigation. */
    NO_TEXT_FIELD,

    /** Not something this backend handles. */
    NOT_HANDLED,
}

/** Primary/secondary buttons as MotionEvent button flags. */
object MouseButtons {
    const val PRIMARY = MotionEvent.BUTTON_PRIMARY
    const val SECONDARY = MotionEvent.BUTTON_SECONDARY
    const val TERTIARY = MotionEvent.BUTTON_TERTIARY
    const val BACK = MotionEvent.BUTTON_BACK
    const val FORWARD = MotionEvent.BUTTON_FORWARD
}
