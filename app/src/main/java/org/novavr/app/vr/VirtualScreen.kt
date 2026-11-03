/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Texture shown on the virtual screen.
 *
 * @property target `GL_TEXTURE_2D` (generated test pattern) or
 *   `GL_TEXTURE_EXTERNAL_OES` (captured frames from a SurfaceTexture).
 * @property texMatrix maps content UV (origin bottom-left) to texture UV.
 *   For a SurfaceTexture this is its transform matrix.
 * @property aspect width / height of the content.
 */
internal class ScreenTexture(
    var target: Int = GLES30.GL_TEXTURE_2D,
    var id: Int = 0,
    val texMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) },
    var aspect: Float = 1f,
) {
    val isValid: Boolean get() = id != 0

    companion object {
        /** Bitmaps are uploaded top row first, so V must be flipped. */
        fun setBitmapFlip(m: FloatArray) {
            Matrix.setIdentityM(m, 0)
            m[5] = -1f
            m[13] = 1f
        }
    }
}

/**
 * A flat rectangle floating in front of the user.
 *
 * The screen shape is a diagonal (like a TV) plus an aspect ratio (the
 * Portrait / Landscape mode). The content is fitted inside it preserving its
 * own aspect ratio (letter- or pillar-boxed, never stretched). A thin bezel
 * and anti-aliased rounded corners are produced analytically in the fragment
 * shader.
 *
 * Sampling uses a 4-tap rotated-grid filter sized by screen-space derivatives.
 * Captured frames arrive as external OES textures, which cannot have mipmaps;
 * the filter avoids shimmering when a high-resolution capture is shown small.
 *
 * GL thread only. Call [init] after context creation and [release] before the
 * context is destroyed.
 */
internal class VirtualScreen {

    private class Program(val id: Int) {
        val uMvp = GLES30.glGetUniformLocation(id, "uMvp")
        val uTexMatrix = GLES30.glGetUniformLocation(id, "uTexMatrix")
        val uQuadSize = GLES30.glGetUniformLocation(id, "uQuadSize")
        val uScreenSize = GLES30.glGetUniformLocation(id, "uScreenSize")
        val uContentSize = GLES30.glGetUniformLocation(id, "uContentSize")
        val uCornerRadius = GLES30.glGetUniformLocation(id, "uCornerRadius")
        val uTexture = GLES30.glGetUniformLocation(id, "uTexture")
    }

    private var program2d: Program? = null
    private var programExternal: Program? = null
    private var vbo = 0
    private var vao = 0

    /** Screen (inside the bezel) size in metres. */
    var widthMeters = 1f
        private set
    var heightMeters = 1f
        private set
    private var distanceMeters = 2f
    private var bezelMeters = 0.02f

    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val fitted = FloatArray(2)

    /** @param external also compile the OES program (needed for mirroring). */
    fun init(external: Boolean) {
        program2d = Program(GlUtil.createProgram(VERTEX_SHADER, fragmentShader(external = false)))
        if (external) {
            check(GlUtil.hasExtension("GL_OES_EGL_image_external_essl3")) {
                "This GPU does not support GL_OES_EGL_image_external_essl3 (required for mirroring)"
            }
            programExternal = Program(GlUtil.createProgram(VERTEX_SHADER, fragmentShader(external = true)))
        }

        // Unit quad (triangle strip): position.xy, uv.
        vbo = GlUtil.createVertexBuffer(
            floatArrayOf(
                -0.5f, -0.5f, 0f, 0f,
                0.5f, -0.5f, 1f, 0f,
                -0.5f, 0.5f, 0f, 1f,
                0.5f, 0.5f, 1f, 1f,
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
        GlUtil.checkError("VirtualScreen.init")
    }

    /**
     * @param diagonalInches virtual screen diagonal.
     * @param aspect screen width / height (display mode).
     * @param distance distance in metres along -Z.
     */
    fun setGeometry(diagonalInches: Float, aspect: Float, distance: Float) {
        val diagonal = diagonalInches * METERS_PER_INCH
        heightMeters = diagonal / sqrt(1f + aspect * aspect)
        widthMeters = heightMeters * aspect
        distanceMeters = distance
        bezelMeters = min(widthMeters, heightMeters) * 0.015f
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, -distanceMeters)
        Matrix.scaleM(model, 0, widthMeters + 2 * bezelMeters, heightMeters + 2 * bezelMeters, 1f)
    }

    fun draw(viewProjection: FloatArray, content: ScreenTexture) {
        val program = (if (content.target == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) programExternal else program2d)
            ?: return
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)

        fitContent(widthMeters, heightMeters, content.aspect, fitted)
        val contentW = fitted[0]
        val contentH = fitted[1]

        GLES30.glUseProgram(program.id)
        GLES30.glUniformMatrix4fv(program.uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(program.uTexMatrix, 1, false, content.texMatrix, 0)
        GLES30.glUniform2f(program.uQuadSize, widthMeters + 2 * bezelMeters, heightMeters + 2 * bezelMeters)
        GLES30.glUniform2f(program.uScreenSize, widthMeters, heightMeters)
        GLES30.glUniform2f(program.uContentSize, contentW, contentH)
        GLES30.glUniform1f(program.uCornerRadius, bezelMeters * 2.5f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(content.target, if (content.isValid) content.id else 0)
        GLES30.glUniform1i(program.uTexture, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(content.target, 0)
    }

    fun release() {
        GlUtil.deleteBuffer(vbo)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        program2d?.let { GlUtil.deleteProgram(it.id) }
        programExternal?.let { GlUtil.deleteProgram(it.id) }
        program2d = null; programExternal = null; vbo = 0; vao = 0
    }

    companion object {
        private const val METERS_PER_INCH = 0.0254f

        /**
         * Largest rectangle with [contentAspect] (width / height) that fits in
         * a [screenW] × [screenH] screen, i.e. letter- or pillar-boxing without
         * stretching. Writes `[width, height]` to [out].
         */
        fun fitContent(screenW: Float, screenH: Float, contentAspect: Float, out: FloatArray) {
            val aspect = if (contentAspect > 0f) contentAspect else screenW / screenH
            if (aspect >= screenW / screenH) {
                out[0] = screenW
                out[1] = screenW / aspect
            } else {
                out[1] = screenH
                out[0] = screenH * aspect
            }
        }

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aUv;
            uniform mat4 uMvp;
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
            }
        """

        private fun fragmentShader(external: Boolean): String {
            val header = if (external) {
                "#extension GL_OES_EGL_image_external_essl3 : require\n"
            } else {
                ""
            }
            val sampler = if (external) "samplerExternalOES" else "sampler2D"
            return """#version 300 es
$header
precision mediump float;
in vec2 vUv;
uniform $sampler uTexture;
uniform mat4 uTexMatrix;
uniform vec2 uQuadSize;     // screen + bezel, metres
uniform vec2 uScreenSize;   // screen area, metres
uniform vec2 uContentSize;  // fitted content, metres
uniform float uCornerRadius;
out vec4 fragColor;

float roundedBox(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

vec3 tap(vec2 uv) {
    return texture(uTexture, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
}

void main() {
    vec2 p = (vUv - 0.5) * uQuadSize;               // metres, centred
    float d = roundedBox(p, uQuadSize * 0.5, uCornerRadius);
    float aa = fwidth(d);
    float alpha = 1.0 - smoothstep(-aa, aa, d);
    if (alpha <= 0.0) discard;

    // Content UV, origin bottom-left. Derivatives are taken before any
    // branching so they stay well defined.
    vec2 uv = p / uContentSize + 0.5;
    vec2 dx = dFdx(uv) * 0.25;
    vec2 dy = dFdy(uv) * 0.25;
    vec3 tex = 0.25 * (tap(uv + dx + dy) + tap(uv - dx - dy) +
                       tap(uv + dx - dy) + tap(uv - dx + dy));

    vec2 inContent = step(vec2(0.0), uv) * step(uv, vec2(1.0));
    vec2 inScreen = step(abs(p), uScreenSize * 0.5);
    vec3 bezel = vec3(0.07, 0.08, 0.10);
    vec3 screen = mix(vec3(0.0), tex, inContent.x * inContent.y);
    fragColor = vec4(mix(bezel, screen, inScreen.x * inScreen.y), alpha);
}
"""
        }
    }
}
