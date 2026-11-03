/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input

import kotlin.math.roundToInt

/**
 * Where the mirrored content lives on the physical display.
 *
 * Single-app capture reports only the *size* of the captured region. For a
 * full-screen app that region is the whole display; for a letterboxed app it is
 * a sub-rectangle. The bounds of the shared app's window (from Accessibility)
 * pin it down exactly; without them the region is assumed to be centred.
 */
data class ContentRegion(
    val contentWidth: Int,
    val contentHeight: Int,
    val left: Int,
    val top: Int,
) {
    /** Content pixel → display pixel (what input injection needs). */
    fun toDisplay(x: Float, y: Float): Pair<Float, Float> = (left + x) to (top + y)

    companion object {
        private const val SIZE_TOLERANCE = 0.02f

        /**
         * @param windowBounds bounds of the shared app's window on the display
         *   (left, top, right, bottom), if known and matching the content size.
         */
        fun resolve(
            contentWidth: Int,
            contentHeight: Int,
            displayWidth: Int,
            displayHeight: Int,
            windowBounds: IntArray? = null,
        ): ContentRegion {
            if (windowBounds != null) {
                val w = windowBounds[2] - windowBounds[0]
                val h = windowBounds[3] - windowBounds[1]
                // Use the window only to locate the region, and only if its size
                // matches the captured region. The captured size (from
                // MediaProjection) is authoritative: Accessibility can report
                // app-space bounds, e.g. for apps running with an OEM
                // resolution-downscaling compat mode (seen on Motorola), which
                // would misplace every click.
                if (w > 0 && h > 0 && contentWidth > 0 && contentHeight > 0) {
                    val dw = kotlin.math.abs(w - contentWidth).toFloat() / contentWidth
                    val dh = kotlin.math.abs(h - contentHeight).toFloat() / contentHeight
                    if (dw < SIZE_TOLERANCE && dh < SIZE_TOLERANCE) {
                        return ContentRegion(contentWidth, contentHeight, windowBounds[0], windowBounds[1])
                    }
                }
            }
            val w = contentWidth.coerceAtMost(displayWidth)
            val h = contentHeight.coerceAtMost(displayHeight)
            return ContentRegion(
                contentWidth = w,
                contentHeight = h,
                left = ((displayWidth - w) / 2f).roundToInt().coerceAtLeast(0),
                top = ((displayHeight - h) / 2f).roundToInt().coerceAtLeast(0),
            )
        }
    }
}
