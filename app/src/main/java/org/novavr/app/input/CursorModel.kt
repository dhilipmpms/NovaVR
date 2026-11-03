/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input

import android.os.SystemClock

/** Visual feedback event drawn at the cursor in VR. */
data class CursorFeedback(
    val kind: Kind,
    /** Normalised content position (0..1, origin top-left). */
    val u: Float,
    val v: Float,
    val timeNanos: Long,
) {
    enum class Kind { LEFT_CLICK, RIGHT_CLICK, SCROLL_UP, SCROLL_DOWN }
}

/**
 * Immutable snapshot of the virtual cursor, published by the input thread and
 * read once per frame by the GL thread (so both eyes always draw the same
 * cursor state).
 *
 * @property u / v normalised position over the mirrored content; v may exceed
 *   1 when the cursor is in the dock area below the screen.
 */
data class CursorSnapshot(
    val u: Float = 0.5f,
    val v: Float = 0.5f,
    val enabled: Boolean = false,
    val lastActivityNanos: Long = 0L,
    val autoHideNanos: Long = 0L,
    val buttonsDown: Int = 0,
    val feedback: List<CursorFeedback> = emptyList(),
    /** Highlighted dock item index, or -1. */
    val dockHover: Int = -1,
) {
    /** 0..1 opacity at [now], fading out after the inactivity timeout. */
    fun alpha(now: Long): Float {
        if (!enabled) return 0f
        if (autoHideNanos <= 0L || buttonsDown != 0) return 1f
        val idle = now - lastActivityNanos - autoHideNanos
        if (idle <= 0) return 1f
        return (1f - idle / FADE_NANOS.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        const val FADE_NANOS = 300_000_000L
        const val FEEDBACK_NANOS = 350_000_000L
    }
}

/**
 * Cursor state in content pixels. Main-thread writer; readers use [snapshot].
 * The dock zone extends below the content by [DockLayout.ZONE_FRACTION] of its height.
 */
class CursorModel {

    @Volatile
    var snapshot = CursorSnapshot()
        private set

    // Starts centred (relative 0.5 of the initial 1×1 size, rescaled on first size).
    var x = 0.5f
        private set
    var y = 0.5f
        private set
    private var width = 1
    private var height = 1

    private val dockFraction = DockLayout.ZONE_FRACTION

    /** Updates the content size, keeping the cursor at the same relative spot. */
    fun setContentSize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        if (newWidth == width && newHeight == height) return
        val u = if (width > 0) x / width else 0.5f
        val v = if (height > 0) y / height else 0.5f
        width = newWidth
        height = newHeight
        x = u * width
        y = v * height
        publish()
    }

    val contentWidth: Int get() = width
    val contentHeight: Int get() = height

    /** True when the cursor is below the content, over the NovaVR dock. */
    val inDock: Boolean get() = y > height

    fun center() {
        x = width / 2f
        y = height / 2f
        touch()
    }

    fun moveBy(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, width - 1f)
        y = (y + dy).coerceIn(0f, height * (1f + dockFraction))
        touch()
    }

    fun configure(enabled: Boolean, autoHideSeconds: Int) {
        val nanos = autoHideSeconds * 1_000_000_000L
        val s = snapshot
        if (s.enabled == enabled && s.autoHideNanos == nanos) return
        snapshot = s.copy(enabled = enabled, autoHideNanos = nanos)
    }

    fun setButtons(buttons: Int) {
        if (snapshot.buttonsDown != buttons) {
            snapshot = snapshot.copy(buttonsDown = buttons)
            touch()
        }
    }

    fun setDockHover(index: Int) {
        if (snapshot.dockHover != index) snapshot = snapshot.copy(dockHover = index)
    }

    fun feedback(kind: CursorFeedback.Kind) {
        val now = SystemClock.elapsedRealtimeNanos()
        val live = snapshot.feedback.filter { now - it.timeNanos < CursorSnapshot.FEEDBACK_NANOS }
        snapshot = snapshot.copy(
            feedback = (live + CursorFeedback(kind, x / width, y / height, now)).takeLast(6),
            lastActivityNanos = now,
        )
    }

    /** Marks activity (cursor reappears) without moving. */
    fun touch() {
        publish(SystemClock.elapsedRealtimeNanos())
    }

    private fun publish(now: Long = snapshot.lastActivityNanos) {
        snapshot = snapshot.copy(u = x / width, v = y / height, lastActivityNanos = now)
    }
}
