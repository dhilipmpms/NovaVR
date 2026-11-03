package org.novavr.app.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentRegionTest {
    @Test
    fun fullScreenAppMapsOneToOne() {
        val r = ContentRegion.resolve(2712, 1220, 2712, 1220, intArrayOf(0, 0, 2712, 1220))
        assertEquals(1832f to 1130f, r.toDisplay(1832f, 1130f))
    }

    @Test
    fun letterboxedAppUsesWindowOrigin() {
        // Portrait-only app letterboxed in a landscape display.
        val r = ContentRegion.resolve(549, 1220, 2712, 1220, intArrayOf(1081, 0, 1630, 1220))
        assertEquals(1081, r.left)
        assertEquals(1081f + 100f to 50f, r.toDisplay(100f, 50f))
    }

    @Test
    fun mismatchedWindowSizeIsIgnored() {
        // Seen on Motorola: Accessibility reported app-space bounds (91 %).
        val r = ContentRegion.resolve(2712, 1220, 2712, 1220, intArrayOf(0, 0, 2473, 1112))
        assertEquals(2712, r.contentWidth)
        assertEquals(1220, r.contentHeight)
        assertEquals(1832f to 1130f, r.toDisplay(1832f, 1130f))
    }

    @Test
    fun withoutWindowTheRegionIsCentred() {
        val r = ContentRegion.resolve(1000, 1220, 2712, 1220, null)
        assertEquals(856, r.left)
        assertEquals(0, r.top)
    }
}
