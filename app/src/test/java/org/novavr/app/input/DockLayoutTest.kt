package org.novavr.app.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockLayoutTest {
    @Test
    fun widthNeverExceedsContent() {
        assertTrue(DockLayout.widthFraction(0.45f, 8) <= 1f)
        assertTrue(DockLayout.widthFraction(2.22f, 8) < 1f)
    }

    @Test
    fun hitTestingCoversItemsInOrder() {
        val aspect = 2.22f
        val w = DockLayout.widthFraction(aspect, 8)
        val left = 0.5f - w / 2f
        assertEquals(-1, DockLayout.itemAt(left - 0.01f, aspect, 8))
        assertEquals(0, DockLayout.itemAt(left + 0.001f, aspect, 8))
        assertEquals(7, DockLayout.itemAt(left + w - 0.001f, aspect, 8))
        assertEquals(-1, DockLayout.itemAt(left + w + 0.01f, aspect, 8))
    }
}
