/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.Matrix
import androidx.core.graphics.createBitmap
import org.novavr.app.input.CursorFeedback
import org.novavr.app.input.CursorSnapshot
import org.novavr.app.input.DockLayout
import org.novavr.app.input.NovaCommand
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the virtual mouse cursor, click / scroll feedback and the NovaVR dock
 * on the virtual screen plane.
 *
 * Everything is positioned in the virtual screen's *content* rectangle (the
 * fitted, letter-boxed area), from the same [CursorSnapshot] for both eyes, so
 * the cursor stays glued to the mirrored pixels whatever the screen size,
 * distance or display mode. It is drawn just in front of the screen surface
 * (no z-fighting) with depth testing off.
 *
 * GL thread only.
 */
internal class CursorRenderer(private val dock: List<NovaCommand>) {

    private var program = 0
    private var vbo = 0
    private var vao = 0
    private var uMvp = 0
    private var uAlpha = 0
    private var uTint = 0
    private var uTexture = 0

    private var arrowTexture = 0
    private var ringTexture = 0
    private var chevronTexture = 0
    private var dockTexture = 0
    private var dockTextureHover = Int.MIN_VALUE

    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    fun init() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uTint = GLES30.glGetUniformLocation(program, "uTint")
        uTexture = GLES30.glGetUniformLocation(program, "uTexture")
        // Quad with its origin at the top-left corner (cursor hotspot), +x right, -y down.
        vbo = GlUtil.createVertexBuffer(
            floatArrayOf(
                0f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                0f, 0f, 0f, 0f,
                1f, 0f, 1f, 0f,
            ),
        )
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        arrowTexture = upload(arrowBitmap())
        ringTexture = upload(ringBitmap())
        chevronTexture = upload(chevronBitmap())
        GlUtil.checkError("CursorRenderer.init")
    }

    /**
     * @param contentW / contentH fitted content size in metres.
     * @param distance distance of the screen plane.
     */
    fun draw(
        viewProjection: FloatArray,
        snapshot: CursorSnapshot,
        nowNanos: Long,
        contentW: Float,
        contentH: Float,
        distance: Float,
    ) {
        val alpha = snapshot.alpha(nowNanos)
        val feedback = snapshot.feedback.filter { nowNanos - it.timeNanos < CursorSnapshot.FEEDBACK_NANOS }
        val showDock = snapshot.enabled && dock.isNotEmpty() && snapshot.v > 1f - DOCK_REVEAL
        if (alpha <= 0f && feedback.isEmpty() && !showDock) return

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(uTexture, 0)
        val z = -distance + Z_OFFSET
        val left = -contentW / 2f
        val top = contentH / 2f
        val unit = min(contentW, contentH)

        if (showDock) {
            ensureDockTexture(snapshot.dockHover)
            val zoneH = contentH * DockLayout.ZONE_FRACTION
            val barH = zoneH * DockLayout.BAR_FRACTION
            val dockW = contentW * DockLayout.widthFraction(contentW / contentH, dock.size)
            val dockAlpha = ((snapshot.v - (1f - DOCK_REVEAL)) / DOCK_REVEAL).coerceIn(0.35f, 1f)
            quad(viewProjection, -dockW / 2f, -contentH / 2f - (zoneH - barH) / 2f, z, dockW, barH,
                dockTexture, dockAlpha, WHITE)
        }

        // Click rings expand and fade; scroll chevrons drift and fade.
        for (f in feedback) {
            val t = ((nowNanos - f.timeNanos).toFloat() / CursorSnapshot.FEEDBACK_NANOS).coerceIn(0f, 1f)
            val cx = left + f.u * contentW
            val cy = top - f.v * contentH
            when (f.kind) {
                CursorFeedback.Kind.LEFT_CLICK, CursorFeedback.Kind.RIGHT_CLICK -> {
                    val size = unit * (0.025f + 0.05f * t)
                    val tint = if (f.kind == CursorFeedback.Kind.LEFT_CLICK) ACCENT else ORANGE
                    quad(viewProjection, cx - size / 2f, cy + size / 2f, z, size, size, ringTexture, 1f - t, tint)
                }
                CursorFeedback.Kind.SCROLL_UP, CursorFeedback.Kind.SCROLL_DOWN -> {
                    val up = f.kind == CursorFeedback.Kind.SCROLL_UP
                    val size = unit * 0.03f
                    val offset = unit * (0.035f + 0.02f * t) * (if (up) 1f else -1f)
                    quad(viewProjection, cx - size / 2f, cy + offset + size / 2f, z, size,
                        if (up) size else -size, chevronTexture, 1f - t, ACCENT)
                }
            }
        }

        if (alpha > 0f) {
            val size = max(unit * CURSOR_FRACTION, distance * MIN_ANGULAR_SIZE)
            val cx = left + snapshot.u * contentW
            val cy = top - snapshot.v * contentH
            quad(viewProjection, cx, cy, z, size, size, arrowTexture, alpha,
                if (snapshot.buttonsDown != 0) PRESSED else WHITE)
        }

        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Quad with its top-left corner at (x, y); a negative [h] flips it vertically. */
    private fun quad(
        vp: FloatArray, x: Float, y: Float, z: Float, w: Float, h: Float,
        texture: Int, alpha: Float, tint: FloatArray,
    ) {
        Matrix.setIdentityM(model, 0)
        if (h < 0f) {
            Matrix.translateM(model, 0, x, y + h, z)
            Matrix.scaleM(model, 0, w, h, 1f)
        } else {
            Matrix.translateM(model, 0, x, y, z)
            Matrix.scaleM(model, 0, w, h, 1f)
        }
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uAlpha, alpha)
        GLES30.glUniform3f(uTint, tint[0], tint[1], tint[2])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        // The flipped chevron has reversed winding; draw both faces.
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun ensureDockTexture(hover: Int) {
        if (dockTexture != 0 && dockTextureHover == hover) return
        GlUtil.deleteTexture(dockTexture)
        dockTexture = upload(dockBitmap(hover))
        dockTextureHover = hover
    }

    private fun upload(bitmap: Bitmap): Int = GlUtil.createTexture(bitmap).also { bitmap.recycle() }

    fun release() {
        listOf(arrowTexture, ringTexture, chevronTexture, dockTexture).forEach(GlUtil::deleteTexture)
        arrowTexture = 0; ringTexture = 0; chevronTexture = 0; dockTexture = 0
        dockTextureHover = Int.MIN_VALUE
        GlUtil.deleteBuffer(vbo)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GlUtil.deleteProgram(program)
        vbo = 0; vao = 0; program = 0
    }

    // Textures ------------------------------------------------------------------

    private fun arrowBitmap(): Bitmap {
        val s = 128
        val b = createBitmap(s, s)
        val c = Canvas(b)
        val path = Path().apply {
            moveTo(6f, 4f); lineTo(6f, 100f); lineTo(30f, 78f); lineTo(46f, 116f)
            lineTo(62f, 109f); lineTo(46f, 72f); lineTo(80f, 72f); close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.BLACK; strokeJoin = Paint.Join.ROUND
        })
        return b
    }

    private fun ringBitmap(): Bitmap {
        val s = 128
        val b = createBitmap(s, s)
        Canvas(b).drawCircle(s / 2f, s / 2f, s * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = s * 0.08f; color = Color.WHITE
        })
        return b
    }

    private fun chevronBitmap(): Bitmap {
        val s = 64
        val b = createBitmap(s, s)
        val p = Path().apply { moveTo(10f, 44f); lineTo(32f, 20f); lineTo(54f, 44f) }
        Canvas(b).drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 9f; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
        })
        return b
    }

    private fun dockBitmap(hover: Int): Bitmap {
        val h = 140
        val cell = (h * DockLayout.CELL_ASPECT).toInt()
        val b = createBitmap(cell * dock.size, h)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6141821.toInt() }
        c.drawRoundRect(RectF(0f, 0f, b.width.toFloat(), h.toFloat()), 36f, 36f, bg)
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B3766.toInt() }
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 54f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFB8C0D0.toInt(); textAlign = Paint.Align.CENTER; textSize = 22f
        }
        dock.forEachIndexed { i, cmd ->
            val x0 = i * cell.toFloat()
            if (i == hover) c.drawRoundRect(RectF(x0 + 8f, 8f, x0 + cell - 8f, h - 8f), 28f, 28f, hl)
            val (g, l) = dockGlyph(cmd)
            c.drawText(g, x0 + cell / 2f, 74f, glyph)
            c.drawText(l, x0 + cell / 2f, 118f, label)
        }
        return b
    }

    private fun dockGlyph(cmd: NovaCommand): Pair<String, String> = when (cmd) {
        NovaCommand.STOP_MIRRORING -> "■" to "Stop"
        NovaCommand.HIDE_VR -> "✕" to "Hide VR"
        NovaCommand.TOGGLE_MODE -> "⇄" to "Mode"
        NovaCommand.RECENTER -> "◎" to "Recenter"
        NovaCommand.SMALLER -> "−" to "Smaller"
        NovaCommand.BIGGER -> "+" to "Bigger"
        NovaCommand.CLOSER -> "▲" to "Closer"
        NovaCommand.FARTHER -> "▼" to "Farther"
        NovaCommand.TOGGLE_CURSOR -> "↖" to "Cursor"
    }

    private companion object {
        const val Z_OFFSET = 0.003f
        const val CURSOR_FRACTION = 0.045f

        /** Never smaller than ~0.9° so it stays visible on a far screen. */
        const val MIN_ANGULAR_SIZE = 0.016f

        /** Dock fades in when the cursor is within the bottom 8 % of the screen. */
        const val DOCK_REVEAL = 0.08f

        val WHITE = floatArrayOf(1f, 1f, 1f)
        val PRESSED = floatArrayOf(0.75f, 0.82f, 1f)
        val ACCENT = floatArrayOf(0.42f, 0.55f, 1f)
        val ORANGE = floatArrayOf(1f, 0.62f, 0.3f)

        const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aUv;
            uniform mat4 uMvp;
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            uniform vec3 uTint;
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uTexture, vUv);
                if (c.a <= 0.0) discard;
                // Bitmaps are premultiplied: un-premultiply, then tint.
                fragColor = vec4(c.rgb / c.a * uTint, c.a * uAlpha);
            }
        """
    }
}
