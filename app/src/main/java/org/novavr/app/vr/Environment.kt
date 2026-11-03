/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.opengl.GLES30

/**
 * Minimal surroundings: a floor grid that fades out with distance. It gives
 * the brain a stable spatial reference (reducing discomfort) for the cost of
 * a single 124-vertex line draw.
 */
internal class Environment {

    private var program = 0
    private var vbo = 0
    private var vao = 0
    private var vertexCount = 0
    private var uViewProjection = 0

    fun init() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uViewProjection = GLES30.glGetUniformLocation(program, "uViewProjection")

        val vertices = ArrayList<Float>()
        for (i in -EXTENT..EXTENT) {
            val v = i.toFloat()
            // Line parallel to Z
            vertices += listOf(v, FLOOR_Y, -EXTENT.toFloat(), v, FLOOR_Y, EXTENT.toFloat())
            // Line parallel to X
            vertices += listOf(-EXTENT.toFloat(), FLOOR_Y, v, EXTENT.toFloat(), FLOOR_Y, v)
        }
        vertexCount = vertices.size / 3
        vbo = GlUtil.createVertexBuffer(vertices.toFloatArray())

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GlUtil.checkError("Environment.init")
    }

    fun draw(viewProjection: FloatArray) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uViewProjection, 1, false, viewProjection, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GlUtil.deleteBuffer(vbo)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GlUtil.deleteProgram(program)
        vbo = 0; vao = 0; program = 0
    }

    private companion object {
        /** Grid half-size in metres (1 m cells). */
        const val EXTENT = 15

        /** Floor height relative to the eyes for a seated user. */
        const val FLOOR_Y = -1.2f

        const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec3 aPosition;
            uniform mat4 uViewProjection;
            out float vDistance;
            void main() {
                vDistance = length(aPosition.xz);
                gl_Position = uViewProjection * vec4(aPosition, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in float vDistance;
            out vec4 fragColor;
            void main() {
                float fade = 1.0 - smoothstep(3.0, 14.0, vDistance);
                fragColor = vec4(0.42, 0.55, 1.0, 0.28 * fade);
            }
        """
    }
}
