package org.novavr.app.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TextEditsTest {
    private fun s(text: String, a: Int, b: Int = a) = TextState(text, a, b)

    @Test
    fun insertReplacesSelection() {
        assertEquals(s("heXlo", 3), TextEdits.insert(s("hello", 2, 3), "X"))
        assertEquals(s("hi!", 3), TextEdits.insert(TextState("hi", -1, -1), "!")) // no selection: append
    }

    @Test
    fun backspaceAndDelete() {
        assertEquals(s("hllo", 1), TextEdits.backspace(s("hello", 2)))
        assertEquals(s("hello", 0), TextEdits.backspace(s("hello", 0)))
        assertEquals(s("helo", 3), TextEdits.delete(s("hello", 3)))
        assertEquals(s("ho", 1), TextEdits.backspace(s("hello", 1, 4)))
    }

    @Test
    fun wordDeletion() {
        assertEquals(s("hello ", 6), TextEdits.backspace(s("hello world", 11), word = true))
        assertEquals(s("hello", 5), TextEdits.delete(s("hello world", 5), word = true))
    }

    @Test
    fun caretMovementAndSelection() {
        assertEquals(s("abc", 1), TextEdits.moveHorizontal(s("abc", 2), -1, extend = false, word = false))
        assertEquals(s("abc", 2, 3), TextEdits.moveHorizontal(s("abc", 2), 1, extend = true, word = false))
        // Collapsing a selection moves to its edge.
        assertEquals(s("abcd", 1), TextEdits.moveHorizontal(s("abcd", 1, 3), -1, extend = false, word = false))
        assertEquals(s("foo bar", 4), TextEdits.moveHorizontal(s("foo bar", 7), -1, extend = false, word = true))
    }

    @Test
    fun homeEndAndSelectAll() {
        val t = "one\ntwo\nthree"
        assertEquals(s(t, 4), TextEdits.moveToLineEdge(s(t, 6), end = false, extend = false, document = false))
        assertEquals(s(t, 7), TextEdits.moveToLineEdge(s(t, 5), end = true, extend = false, document = false))
        assertEquals(s(t, t.length), TextEdits.moveToLineEdge(s(t, 0), end = true, extend = false, document = true))
        assertEquals(s(t, 0, t.length), TextEdits.selectAll(s(t, 2)))
    }
}
