/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input

import kotlin.math.min

/**
 * Geometry of the NovaVR dock, shared by the renderer (drawing) and the router
 * (hit testing) so they can never disagree. The dock sits directly below the
 * content and is reached by moving the cursor past the bottom edge.
 */
object DockLayout {
    /** Height of the dock zone as a fraction of the content height. */
    const val ZONE_FRACTION = 0.16f

    /** Visible dock bar height as a fraction of the zone. */
    const val BAR_FRACTION = 0.84f

    /** Dock bar width as a fraction of the content width. */
    fun widthFraction(contentAspect: Float, items: Int): Float {
        if (items == 0 || contentAspect <= 0f) return 0f
        val barHeight = ZONE_FRACTION * BAR_FRACTION // in content heights
        return min(1f, barHeight * items * CELL_ASPECT / contentAspect)
    }

    /** Dock item under normalised content x [u], or -1. */
    fun itemAt(u: Float, contentAspect: Float, items: Int): Int {
        val w = widthFraction(contentAspect, items)
        if (w <= 0f) return -1
        val rel = (u - (0.5f - w / 2f)) / w
        if (rel < 0f || rel >= 1f) return -1
        return (rel * items).toInt().coerceIn(0, items - 1)
    }

    /** Width / height of one dock cell. */
    const val CELL_ASPECT = 1.14f
}
