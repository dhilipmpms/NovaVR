/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input

import android.view.KeyEvent
import org.novavr.app.settings.ShortcutModifier

/** Actions on NovaVR's own interface, triggered by keyboard shortcuts or the VR dock. */
enum class NovaCommand(val label: String) {
    RECENTER("Recenter view"),
    TOGGLE_MODE("Switch Portrait / Landscape"),
    HIDE_VR("Hide VR (keep mirroring)"),
    STOP_MIRRORING("Stop mirroring"),
    CLOSER("Screen closer"),
    FARTHER("Screen farther"),
    BIGGER("Bigger screen"),
    SMALLER("Smaller screen"),
    TOGGLE_CURSOR("Show / hide cursor"),
}

/** System navigation, performed through Accessibility global actions or Shizuku keys. */
enum class SystemAction { BACK, HOME, RECENTS, NOTIFICATIONS }

/**
 * Keyboard shortcut table. NovaVR shortcuts are `modifier + key`, where the
 * modifier is configurable (Ctrl+Alt by default, or Meta/Search). Everything
 * else is left for the mirrored app or the system.
 */
object Shortcuts {

    /** key code → command (used with the configured modifier). */
    val novaBindings: List<Pair<Int, NovaCommand>> = listOf(
        KeyEvent.KEYCODE_R to NovaCommand.RECENTER,
        KeyEvent.KEYCODE_M to NovaCommand.TOGGLE_MODE,
        KeyEvent.KEYCODE_H to NovaCommand.HIDE_VR,
        KeyEvent.KEYCODE_Q to NovaCommand.STOP_MIRRORING,
        KeyEvent.KEYCODE_DPAD_UP to NovaCommand.CLOSER,
        KeyEvent.KEYCODE_DPAD_DOWN to NovaCommand.FARTHER,
        KeyEvent.KEYCODE_EQUALS to NovaCommand.BIGGER,
        KeyEvent.KEYCODE_PLUS to NovaCommand.BIGGER,
        KeyEvent.KEYCODE_NUMPAD_ADD to NovaCommand.BIGGER,
        KeyEvent.KEYCODE_MINUS to NovaCommand.SMALLER,
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT to NovaCommand.SMALLER,
        KeyEvent.KEYCODE_C to NovaCommand.TOGGLE_CURSOR,
    )

    fun modifierLabel(modifier: ShortcutModifier): String = when (modifier) {
        ShortcutModifier.CTRL_ALT -> "Ctrl+Alt"
        ShortcutModifier.META -> "Meta"
        ShortcutModifier.DISABLED -> "—"
    }

    fun keyLabel(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "↑"
        KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
        KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> "+"
        KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "−"
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }

    /** Distinct (label, command) rows for the settings screen. */
    fun table(modifier: ShortcutModifier): List<Pair<String, NovaCommand>> {
        val prefix = modifierLabel(modifier)
        return novaBindings
            .distinctBy { it.second }
            .map { (key, cmd) -> "$prefix+${keyLabel(key)}" to cmd }
    }

    /**
     * Returns the NovaVR command for a key-down with [metaState], or null.
     * Requires exactly the configured modifier (Shift is ignored so `+` works
     * on layouts where it needs Shift).
     */
    fun novaCommand(keyCode: Int, metaState: Int, modifier: ShortcutModifier): NovaCommand? {
        val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
        val alt = metaState and KeyEvent.META_ALT_ON != 0
        val meta = metaState and KeyEvent.META_META_ON != 0
        val matches = when (modifier) {
            ShortcutModifier.CTRL_ALT -> ctrl && alt && !meta
            ShortcutModifier.META -> meta && !ctrl && !alt
            ShortcutModifier.DISABLED -> false
        }
        if (!matches) return null
        return novaBindings.firstOrNull { it.first == keyCode }?.second
    }

    /**
     * System navigation keys, following common Android keyboard conventions:
     * Esc = Back, Meta+Enter / Home key = Home, Alt/Meta+Tab = Recents,
     * Meta+N = notifications, keyboard Back key = Back.
     */
    fun systemAction(keyCode: Int, metaState: Int): SystemAction? {
        val alt = metaState and KeyEvent.META_ALT_ON != 0
        val meta = metaState and KeyEvent.META_META_ON != 0
        val ctrl = metaState and KeyEvent.META_CTRL_ON != 0
        return when {
            keyCode == KeyEvent.KEYCODE_ESCAPE && !ctrl && !alt && !meta -> SystemAction.BACK
            keyCode == KeyEvent.KEYCODE_BACK -> SystemAction.BACK
            keyCode == KeyEvent.KEYCODE_HOME -> SystemAction.HOME
            keyCode == KeyEvent.KEYCODE_ENTER && meta -> SystemAction.HOME
            keyCode == KeyEvent.KEYCODE_TAB && (alt || meta) -> SystemAction.RECENTS
            keyCode == KeyEvent.KEYCODE_APP_SWITCH -> SystemAction.RECENTS
            keyCode == KeyEvent.KEYCODE_N && meta -> SystemAction.NOTIFICATIONS
            keyCode == KeyEvent.KEYCODE_DEL && meta -> SystemAction.BACK
            else -> null
        }
    }
}
