package org.novavr.app.vr

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentFitTest {

    private fun fit(w: Float, h: Float, aspect: Float) = FloatArray(2).also {
        VirtualScreen.fitContent(w, h, aspect, it)
    }

    @Test
    fun matchingAspectFillsScreen() {
        val r = fit(2.22f, 1f, 2.22f)
        assertEquals(2.22f, r[0], 1e-5f)
        assertEquals(1f, r[1], 1e-5f)
    }

    @Test
    fun landscapeContentInPortraitScreenIsLetterboxed() {
        // 9:20 portrait screen, 20:9 landscape content.
        val r = fit(0.45f, 1f, 20f / 9f)
        assertEquals(0.45f, r[0], 1e-5f)              // full width
        assertEquals(0.45f / (20f / 9f), r[1], 1e-5f) // reduced height
        assertEquals(20f / 9f, r[0] / r[1], 1e-4f)    // aspect preserved
    }

    @Test
    fun portraitContentInLandscapeScreenIsPillarboxed() {
        val r = fit(2.22f, 1f, 9f / 20f)
        assertEquals(1f, r[1], 1e-5f)                 // full height
        assertEquals(9f / 20f, r[0] / r[1], 1e-4f)    // aspect preserved
    }

    @Test
    fun invalidAspectFallsBackToScreen() {
        val r = fit(2f, 1f, 0f)
        assertEquals(2f, r[0], 1e-5f)
        assertEquals(1f, r[1], 1e-5f)
    }
}
