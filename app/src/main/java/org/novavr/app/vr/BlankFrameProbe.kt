/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Detects a captured stream that stays completely black.
 *
 * That happens when the user shares the *entire screen*: NovaVR's own VR
 * overlay is the top window, and because it is FLAG_SECURE Android blacks it
 * out in the capture (this is what prevents an endless mirror-in-a-mirror).
 * The probe lets the UI explain the situation instead of showing a silent
 * black screen.
 *
 * Cost: every [INTERVAL_NS] the captured texture is drawn into a 16×16 target
 * and read back through a pixel-buffer object. The read is asynchronous and
 * collected at the next probe, so the GPU pipeline never stalls.
 *
 * GL thread only.
 */
internal class BlankFrameProbe {

    private var program = 0
    private var uTexMatrix = 0
    private var framebuffer = 0
    private var colorTexture = 0
    private var pixelBuffer = 0
    private var vao = 0
    private var readPending = false
    private var lastProbeNs = 0L
    private var blankStreak = 0

    /** True after [BLANK_PROBES_REQUIRED] consecutive all-black probes. */
    var isBlank = false
        private set

    fun init() {
        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        val ids = IntArray(1)

        GLES30.glGenTextures(1, ids, 0)
        colorTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTexture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, SIZE, SIZE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, colorTexture, 0,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        GLES30.glGenBuffers(1, ids, 0)
        pixelBuffer = ids[0]
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pixelBuffer)
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, SIZE * SIZE * 4, null, GLES30.GL_STREAM_READ)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

        // Attribute-less full-screen triangle (positions from gl_VertexID).
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
        GlUtil.checkError("BlankFrameProbe.init")
    }

    /** Call once per frame with the current captured texture. */
    fun update(nowNs: Long, texture: ScreenTexture) {
        if (program == 0 || !texture.isValid) return
        if (nowNs - lastProbeNs < INTERVAL_NS) return
        lastProbeNs = nowNs

        if (readPending) collect()

        // Downsample the captured frame into the 16×16 target.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, SIZE, SIZE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texture.texMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture.id)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)

        // Asynchronous read into the PBO; collected at the next probe.
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pixelBuffer)
        GLES30.glReadPixels(0, 0, SIZE, SIZE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        readPending = true
    }

    private fun collect() {
        readPending = false
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pixelBuffer)
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, SIZE * SIZE * 4, GLES30.GL_MAP_READ_BIT,
        ) as? ByteBuffer
        if (mapped != null) {
            mapped.order(ByteOrder.nativeOrder())
            var maxChannel = 0
            for (i in 0 until SIZE * SIZE * 4) {
                if (i % 4 == 3) continue // alpha
                maxChannel = maxOf(maxChannel, mapped.get(i).toInt() and 0xFF)
            }
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            blankStreak = if (maxChannel <= BLACK_THRESHOLD) blankStreak + 1 else 0
            isBlank = blankStreak >= BLANK_PROBES_REQUIRED
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    fun release() {
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GlUtil.deleteTexture(colorTexture)
        GlUtil.deleteBuffer(pixelBuffer)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GlUtil.deleteProgram(program)
        framebuffer = 0; colorTexture = 0; pixelBuffer = 0; vao = 0; program = 0
        readPending = false
    }

    private companion object {
        const val SIZE = 16
        const val INTERVAL_NS = 2_000_000_000L
        const val BLANK_PROBES_REQUIRED = 3
        const val BLACK_THRESHOLD = 4

        const val VERTEX_SHADER = """#version 300 es
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vUv;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTexMatrix;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTexture, (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy);
            }
        """
    }
}
