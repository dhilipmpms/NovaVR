/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.opengl.GLES30
import android.opengl.Matrix
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A flat, textured rectangle floating in front of the user.
 *
 * Size is specified as a diagonal (like a TV) plus an aspect ratio, so the
 * content proportions are always preserved: the texture and the quad share
 * the same aspect ratio. A thin bezel and anti-aliased rounded corners are
 * produced analytically in the fragment shader (no MSAA needed).
 *
 * GL thread only. Call [init] after context creation and [release] before the
 * context is destroyed.
 */
internal class VirtualScreen {

    private var program = 0
    private var vbo = 0
    private var vao = 0
    private var texture = 0

    private var uMvp = 0
    private var uContentSize = 0
    private var uQuadSize = 0
    private var uCornerRadius = 0
    private var uTexture = 0

    /** Content size in metres. */
    var widthMeters = 1f
        private set
    var heightMeters = 1f
        private set
    private var distanceMeters = 2f
    private var bezelMeters = 0.02f

    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    fun init() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uContentSize = GLES30.glGetUniformLocation(program, "uContentSize")
        uQuadSize = GLES30.glGetUniformLocation(program, "uQuadSize")
        uCornerRadius = GLES30.glGetUniformLocation(program, "uCornerRadius")
        uTexture = GLES30.glGetUniformLocation(program, "uTexture")

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

    /** Replaces the content texture. Takes ownership of [textureId]. */
    fun setTexture(textureId: Int) {
        GlUtil.deleteTexture(texture)
        texture = textureId
    }

    /**
     * @param diagonalInches virtual screen diagonal.
     * @param aspect width / height.
     * @param distance distance in metres along -Z.
     */
    fun setGeometry(diagonalInches: Float, aspect: Float, distance: Float) {
        val diagonal = diagonalInches * METERS_PER_INCH
        heightMeters = diagonal / sqrt(1f + aspect * aspect)
        widthMeters = heightMeters * aspect
        distanceMeters = distance
        bezelMeters = min(widthMeters, heightMeters) * 0.015f
        val quadW = widthMeters + 2 * bezelMeters
        val quadH = heightMeters + 2 * bezelMeters
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0f, -distanceMeters)
        Matrix.scaleM(model, 0, quadW, quadH, 1f)
    }

    fun draw(viewProjection: FloatArray) {
        if (texture == 0) return
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform2f(uContentSize, widthMeters, heightMeters)
        GLES30.glUniform2f(uQuadSize, widthMeters + 2 * bezelMeters, heightMeters + 2 * bezelMeters)
        GLES30.glUniform1f(uCornerRadius, bezelMeters * 2.5f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uTexture, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GlUtil.deleteTexture(texture)
        GlUtil.deleteBuffer(vbo)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GlUtil.deleteProgram(program)
        texture = 0; vbo = 0; vao = 0; program = 0
    }

    private companion object {
        const val METERS_PER_INCH = 0.0254f

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

        // Signed-distance rounded rectangle gives a crisp, anti-aliased edge.
        const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTexture;
            uniform vec2 uContentSize;
            uniform vec2 uQuadSize;
            uniform float uCornerRadius;
            out vec4 fragColor;

            float roundedBox(vec2 p, vec2 halfSize, float r) {
                vec2 q = abs(p) - halfSize + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }

            void main() {
                vec2 p = (vUv - 0.5) * uQuadSize;           // metres, centred
                float d = roundedBox(p, uQuadSize * 0.5, uCornerRadius);
                float aa = fwidth(d);
                float alpha = 1.0 - smoothstep(-aa, aa, d);
                if (alpha <= 0.0) discard;

                vec2 contentUv = p / uContentSize + 0.5;
                vec3 bezel = vec3(0.07, 0.08, 0.10);
                // Sample unconditionally: mip selection needs derivatives,
                // which are undefined inside non-uniform branches.
                // Bitmaps are uploaded top row first, hence the V flip.
                vec3 tex = texture(uTexture, vec2(contentUv.x, 1.0 - contentUv.y)).rgb;
                vec2 inside = step(vec2(0.0), contentUv) * step(contentUv, vec2(1.0));
                vec3 color = mix(bezel, tex, inside.x * inside.y);
                fragColor = vec4(color, alpha);
            }
        """
    }
}
