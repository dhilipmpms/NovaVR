package org.novavr.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VRSettingsTest {

    @Test
    fun sanitizedClampsOutOfRangeValues() {
        val s = VRSettings(
            screenDiagonalInches = 1000f,
            screenDistanceMeters = 0f,
            lensSeparationMm = 10f,
            renderScale = 3f,
        ).sanitized()

        assertEquals(VRSettings.DIAGONAL_RANGE.endInclusive, s.screenDiagonalInches, 0f)
        assertEquals(VRSettings.DISTANCE_RANGE.start, s.screenDistanceMeters, 0f)
        assertEquals(VRSettings.LENS_SEPARATION_RANGE.start, s.lensSeparationMm!!, 0f)
        assertEquals(VRSettings.RENDER_SCALE_RANGE.endInclusive, s.renderScale, 0f)
    }

    @Test
    fun sanitizedKeepsNullLensSeparation() {
        assertNull(VRSettings(lensSeparationMm = null).sanitized().lensSeparationMm)
    }

    @Test
    fun defaultsAreAlreadyValid() {
        assertEquals(VRSettings(), VRSettings().sanitized())
    }

    @Test
    fun displayModeToggles() {
        assertEquals(DisplayMode.LANDSCAPE, DisplayMode.PORTRAIT.toggled())
        assertEquals(DisplayMode.PORTRAIT, DisplayMode.LANDSCAPE.toggled())
    }
}
