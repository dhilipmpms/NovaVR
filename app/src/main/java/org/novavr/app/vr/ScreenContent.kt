/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import org.novavr.app.settings.DisplayMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Procedurally generated content for the virtual screen.
 *
 * Version 0.1.0 has no screen mirroring, so the virtual screen shows a
 * calibration test pattern that makes aspect-ratio, sharpness and stereo
 * problems easy to spot:
 *  - the centre circle must look perfectly round (aspect ratio check),
 *  - the grid must look square and straight (distortion check),
 *  - corner markers must all be visible (screen size check).
 *
 * In a future version this class is replaced by a texture fed from
 * MediaProjection / a SurfaceTexture, without changing [VirtualScreen].
 */
internal object ScreenContent {

    /** Longest texture side; plenty for the angular size of a Cardboard view. */
    private const val LONG_SIDE_PX = 2048

    private const val COLOR_BG_TOP = 0xFF151A26.toInt()
    private const val COLOR_BG_BOTTOM = 0xFF0C0F16.toInt()
    private const val COLOR_GRID = 0xFF232C3D.toInt()
    private const val COLOR_GRID_MAJOR = 0xFF33415C.toInt()
    private const val COLOR_ACCENT = 0xFF6C8CFF.toInt()
    private const val COLOR_TEXT = 0xFFE8ECF4.toInt()
    private const val COLOR_TEXT_DIM = 0xFF8A94A8.toInt()

    /**
     * @param aspect width / height of the virtual screen.
     * @param sourceLongPx / sourceShortPx physical display size, shown as info.
     */
    fun createTestPattern(
        mode: DisplayMode,
        aspect: Float,
        sourceLongPx: Int,
        sourceShortPx: Int,
    ): Bitmap {
        val width: Int
        val height: Int
        if (aspect >= 1f) {
            width = LONG_SIDE_PX
            height = (LONG_SIDE_PX / aspect).roundToInt()
        } else {
            height = LONG_SIDE_PX
            width = (LONG_SIDE_PX * aspect).roundToInt()
        }
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)

        // Background
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, COLOR_BG_TOP, COLOR_BG_BOTTOM, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bg)

        // Square grid anchored at the centre.
        val cell = unit / 8f
        val gridPaint = Paint().apply { color = COLOR_GRID; strokeWidth = max(2f, unit / 600f) }
        val majorPaint = Paint().apply { color = COLOR_GRID_MAJOR; strokeWidth = max(3f, unit / 350f) }
        val cx = w / 2f
        val cy = h / 2f
        var i = 0
        while (cx + i * cell <= w) {
            val p = if (i == 0) majorPaint else gridPaint
            canvas.drawLine(cx + i * cell, 0f, cx + i * cell, h, p)
            canvas.drawLine(cx - i * cell, 0f, cx - i * cell, h, p)
            i++
        }
        i = 0
        while (cy + i * cell <= h) {
            val p = if (i == 0) majorPaint else gridPaint
            canvas.drawLine(0f, cy + i * cell, w, cy + i * cell, p)
            canvas.drawLine(0f, cy - i * cell, w, cy - i * cell, p)
            i++
        }

        // Aspect-ratio reference circle: must look round in VR.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = COLOR_ACCENT
            strokeWidth = unit / 90f
        }
        canvas.drawCircle(cx, cy, unit * 0.3f, ring)

        // Corner markers.
        val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = COLOR_TEXT
            strokeWidth = unit / 110f
            strokeCap = Paint.Cap.ROUND
        }
        val m = unit * 0.05f
        val l = unit * 0.09f
        for ((x, sx) in listOf(m to 1f, w - m to -1f)) {
            for ((y, sy) in listOf(m to 1f, h - m to -1f)) {
                canvas.drawLine(x, y, x + sx * l, y, corner)
                canvas.drawLine(x, y, x, y + sy * l, corner)
            }
        }

        // Text
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textAlign = Paint.Align.CENTER
            textSize = unit * 0.11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_DIM
            textAlign = Paint.Align.CENTER
            textSize = unit * 0.042f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val modeLabel = if (mode == DisplayMode.PORTRAIT) "Portrait" else "Landscape"
        val res = if (mode == DisplayMode.PORTRAIT) "$sourceShortPx × $sourceLongPx" else "$sourceLongPx × $sourceShortPx"
        canvas.drawText("NovaVR", cx, cy - unit * 0.42f, title)
        canvas.drawText("$modeLabel  ·  $res", cx, cy - unit * 0.42f + body.textSize * 1.6f, body)
        canvas.drawText("Tap: switch mode   ·   Hold: recenter", cx, cy + unit * 0.43f, body)

        // Colour bars (RGB + CMY + greys) for chroma and contrast checks.
        val bars = intArrayOf(
            Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW,
            Color.WHITE, 0xFF808080.toInt(), 0xFF303030.toInt(),
        )
        val barW = unit * 0.06f
        val barH = unit * 0.035f
        val startX = cx - barW * bars.size / 2f
        val barY = cy + unit * 0.47f
        val barPaint = Paint()
        bars.forEachIndexed { index, c ->
            barPaint.color = c
            canvas.drawRect(startX + index * barW, barY, startX + (index + 1) * barW, barY + barH, barPaint)
        }
        return bitmap
    }

    /**
     * Head-locked calibration marker for one eye: a crosshair and a label.
     * The left eye gets a warm colour, the right eye a cool one, so the user
     * can verify each eye sees only its own image.
     */
    fun createEyeMarker(isLeft: Boolean): Bitmap {
        val size = 512
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val color = if (isLeft) 0xFFFF6B5A.toInt() else 0xFF4FD1FF.toInt()
        val c = size / 2f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(c, c, size * 0.18f, stroke)
        canvas.drawLine(c - size * 0.3f, c, c - size * 0.06f, c, stroke)
        canvas.drawLine(c + size * 0.06f, c, c + size * 0.3f, c, stroke)
        canvas.drawLine(c, c - size * 0.3f, c, c - size * 0.06f, stroke)
        canvas.drawLine(c, c + size * 0.06f, c, c + size * 0.3f, stroke)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = size * 0.1f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(if (isLeft) "LEFT EYE" else "RIGHT EYE", c, size * 0.9f, text)
        return bitmap
    }
}
