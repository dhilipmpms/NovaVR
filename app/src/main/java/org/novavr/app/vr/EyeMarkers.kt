/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.opengl.GLES30
import android.opengl.Matrix
import org.novavr.cardboard.CardboardNative

/**
 * Calibration overlay: a head-locked crosshair with a different label and
 * colour per eye ("LEFT EYE" / "RIGHT EYE"). Closing one eye must hide the
 * other eye's marker; if both markers are seen by one eye the headset or the
 * phone is misaligned.
 */
internal class EyeMarkers {

    private var program = 0
    private var vbo = 0
    private var vao = 0
    private val textures = IntArray(2)
    private var uMvp = 0
    private var uTexture = 0

    private val model = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        Matrix.translateM(it, 0, 0f, 0f, -DISTANCE)
        Matrix.scaleM(it, 0, SIZE, SIZE, 1f)
    }
    private val mvp = FloatArray(16)

    fun init() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uTexture = GLES30.glGetUniformLocation(program, "uTexture")
        vbo = GlUtil.createVertexBuffer(
            floatArrayOf(
                -0.5f, -0.5f, 0f, 1f,
                0.5f, -0.5f, 1f, 1f,
                -0.5f, 0.5f, 0f, 0f,
                0.5f, 0.5f, 1f, 0f,
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

        for (eye in 0..1) {
            val bitmap = ScreenContent.createEyeMarker(isLeft = eye == CardboardNative.EYE_LEFT)
            textures[eye] = GlUtil.createTexture(bitmap)
            bitmap.recycle()
        }
        GlUtil.checkError("EyeMarkers.init")
    }

    /** @param eyeProjection projection * eyeFromHead (no head rotation: head-locked). */
    fun draw(eye: Int, eyeProjection: FloatArray) {
        Matrix.multiplyMM(mvp, 0, eyeProjection, 0, model, 0)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[eye])
        GLES30.glUniform1i(uTexture, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        textures.forEachIndexed { i, t -> GlUtil.deleteTexture(t); textures[i] = 0 }
        GlUtil.deleteBuffer(vbo)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GlUtil.deleteProgram(program)
        vbo = 0; vao = 0; program = 0
    }

    private companion object {
        const val DISTANCE = 1.5f
        const val SIZE = 0.35f

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
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uTexture, vUv);
                // Bitmaps are premultiplied; convert back for standard blending.
                fragColor = c.a > 0.0 ? vec4(c.rgb / c.a, c.a) : vec4(0.0);
            }
        """
    }
}
