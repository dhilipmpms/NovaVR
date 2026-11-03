/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input

/**
 * Text field state as seen through Accessibility: the full text plus the
 * selection (start == end is a caret).
 */
data class TextState(val text: String, val selStart: Int, val selEnd: Int) {
    val selMin: Int get() = minOf(selStart, selEnd)
    val selMax: Int get() = maxOf(selStart, selEnd)

    fun normalized(): TextState {
        val len = text.length
        // Fields that report no selection get a caret at the end.
        val s = if (selStart in 0..len) selStart else len
        val e = if (selEnd in 0..len) selEnd else s
        return copy(selStart = s, selEnd = e)
    }
}

/**
 * Pure keyboard-editing operations. Accessibility can only *replace* a field's
 * text and set its selection, so each key is turned into a new [TextState]
 * that is then applied with `ACTION_SET_TEXT` + `ACTION_SET_SELECTION`.
 */
object TextEdits {

    fun insert(state: TextState, input: String): TextState {
        val s = state.normalized()
        val text = s.text.substring(0, s.selMin) + input + s.text.substring(s.selMax)
        val caret = s.selMin + input.length
        return TextState(text, caret, caret)
    }

    /** Backspace; with [word] deletes to the previous word boundary (Ctrl+Backspace). */
    fun backspace(state: TextState, word: Boolean = false): TextState {
        val s = state.normalized()
        if (s.selStart != s.selEnd) return deleteSelection(s)
        if (s.selMin == 0) return s
        val from = if (word) previousWordBoundary(s.text, s.selMin) else s.selMin - 1
        return TextState(s.text.removeRange(from, s.selMin), from, from)
    }

    /** Forward delete; with [word] deletes to the next word boundary (Ctrl+Delete). */
    fun delete(state: TextState, word: Boolean = false): TextState {
        val s = state.normalized()
        if (s.selStart != s.selEnd) return deleteSelection(s)
        if (s.selMax >= s.text.length) return s
        val to = if (word) nextWordBoundary(s.text, s.selMax) else s.selMax + 1
        return TextState(s.text.removeRange(s.selMax, to), s.selMax, s.selMax)
    }

    fun deleteSelection(state: TextState): TextState {
        val s = state.normalized()
        return TextState(s.text.removeRange(s.selMin, s.selMax), s.selMin, s.selMin)
    }

    /**
     * Moves the caret. [extend] keeps the selection anchor (Shift); [word]
     * jumps by words (Ctrl). [delta] is -1 (left) or +1 (right).
     */
    fun moveHorizontal(state: TextState, delta: Int, extend: Boolean, word: Boolean): TextState {
        val s = state.normalized()
        if (!extend && s.selStart != s.selEnd) {
            val pos = if (delta < 0) s.selMin else s.selMax
            return TextState(s.text, pos, pos)
        }
        val from = s.selEnd
        val to = when {
            word && delta < 0 -> previousWordBoundary(s.text, from)
            word -> nextWordBoundary(s.text, from)
            else -> (from + delta).coerceIn(0, s.text.length)
        }
        return if (extend) TextState(s.text, s.selStart, to) else TextState(s.text, to, to)
    }

    /** Home / End within the current line; [document] for Ctrl+Home / Ctrl+End. */
    fun moveToLineEdge(state: TextState, end: Boolean, extend: Boolean, document: Boolean): TextState {
        val s = state.normalized()
        val from = s.selEnd
        val to = when {
            document && end -> s.text.length
            document -> 0
            end -> s.text.indexOf('\n', from).let { if (it < 0) s.text.length else it }
            else -> s.text.lastIndexOf('\n', (from - 1).coerceAtLeast(0)).let {
                if (it < 0 || from == 0) 0 else it + 1
            }
        }
        return if (extend) TextState(s.text, s.selStart, to) else TextState(s.text, to, to)
    }

    fun selectAll(state: TextState): TextState = TextState(state.text, 0, state.text.length)

    internal fun previousWordBoundary(text: String, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i > 0 && !text[i - 1].isLetterOrDigit()) i--
        while (i > 0 && text[i - 1].isLetterOrDigit()) i--
        return i
    }

    internal fun nextWordBoundary(text: String, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i < text.length && !text[i].isLetterOrDigit()) i++
        while (i < text.length && text[i].isLetterOrDigit()) i++
        return i
    }
}
