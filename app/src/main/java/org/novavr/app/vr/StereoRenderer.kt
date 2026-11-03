/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.vr

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import org.novavr.cardboard.CardboardNative
import kotlin.math.roundToInt

/**
 * Side-by-side stereo pipeline:
 *
 *  1. Both eyes are rendered into one offscreen colour+depth buffer
 *     (left half = left eye, right half = right eye) using the per-eye
 *     view and projection matrices supplied by the Cardboard lens model.
 *  2. The Cardboard SDK's OpenGL ES 3.0 distortion renderer warps that
 *     buffer onto the display to cancel the headset's lens distortion.
 *
 * NovaVR does not implement its own lens distortion; it is fully delegated
 * to the SDK.
 *
 * GL thread only.
 */
internal class StereoRenderer {

    fun interface EyeDrawer {
        /**
         * Draws the scene for one eye.
         * @param eyeFromHead per-eye offset (half the lens separation).
         * @param projection per-eye asymmetric projection matrix.
         */
        fun drawEye(eye: Int, eyeFromHead: FloatArray, projection: FloatArray)
    }

    private var viewer = 0L
    private var framebuffer = 0
    private var colorTexture = 0
    private var depthBuffer = 0

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var bufferWidth = 0
    private var bufferHeight = 0

    private val eyeFromHead = Array(2) { FloatArray(16).also { Matrix.setIdentityM(it, 0) } }
    private val projection = Array(2) { FloatArray(16).also { Matrix.setIdentityM(it, 0) } }

    val isReady: Boolean get() = viewer != 0L && framebuffer != 0

    /**
     * (Re)creates the lens distortion model, distortion renderer and eye
     * buffer. Call on surface size change, headset-profile change or render
     * scale change.
     *
     * @return false if the Cardboard viewer could not be created.
     */
    fun configure(width: Int, height: Int, deviceParams: ByteArray, renderScale: Float): Boolean {
        releaseViewer()
        releaseFramebuffer()
        surfaceWidth = width
        surfaceHeight = height

        viewer = CardboardNative.nativeViewerCreate(deviceParams, width, height)
        if (viewer == 0L) {
            Log.e(TAG, "Failed to create Cardboard viewer for ${width}x$height")
            return false
        }
        for (eye in 0..1) {
            CardboardNative.nativeViewerGetEyeFromHeadMatrix(viewer, eye, eyeFromHead[eye])
            CardboardNative.nativeViewerGetProjectionMatrix(viewer, eye, Z_NEAR, Z_FAR, projection[eye])
        }

        // Even width so both eye halves have identical size.
        bufferWidth = ((width * renderScale).roundToInt() / 2 * 2).coerceAtLeast(2)
        bufferHeight = (height * renderScale).roundToInt().coerceAtLeast(1)
        createFramebuffer(bufferWidth, bufferHeight)
        GlUtil.checkError("StereoRenderer.configure")
        return isReady
    }

    /** Renders both eyes via [drawer] and presents them through the lens distortion. */
    fun render(drawer: EyeDrawer) {
        if (!isReady) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        // Pass 1: both eyes into the side-by-side buffer.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        // Keep destination alpha at 1 so the distortion pass sees opaque pixels.
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA,
        )
        GLES30.glViewport(0, 0, bufferWidth, bufferHeight)
        GLES30.glClearColor(CLEAR_R, CLEAR_G, CLEAR_B, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val eyeWidth = bufferWidth / 2
        for (eye in 0..1) {
            GLES30.glViewport(eye * eyeWidth, 0, eyeWidth, bufferHeight)
            drawer.drawEye(eye, eyeFromHead[eye], projection[eye])
        }

        // The depth buffer is not needed after this pass; tell tiled GPUs not
        // to write it back to memory.
        GLES30.glInvalidateFramebuffer(
            GLES30.GL_FRAMEBUFFER, 1, intArrayOf(GLES30.GL_DEPTH_ATTACHMENT), 0,
        )

        // Pass 2: lens distortion onto the window surface.
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        CardboardNative.nativeViewerRender(
            viewer, colorTexture, 0, 0, 0, surfaceWidth, surfaceHeight,
        )
        GlUtil.checkError("StereoRenderer.render")
    }

    /** Frees all GL and native resources. Must run while the context is current. */
    fun release() {
        releaseViewer()
        releaseFramebuffer()
    }

    /**
     * Forgets resources that died with a lost EGL context. The native viewer
     * still owns CPU memory, so it is freed; this must run before any object
     * is created in the new context so its GL deletes cannot hit live names.
     */
    fun abandon() {
        releaseViewer()
        framebuffer = 0; colorTexture = 0; depthBuffer = 0
    }

    private fun createFramebuffer(width: Int, height: Int) {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        colorTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        GLES30.glGenRenderbuffers(1, ids, 0)
        depthBuffer = ids[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthBuffer)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, width, height)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)

        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, colorTexture, 0,
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depthBuffer,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Eye framebuffer incomplete: 0x${Integer.toHexString(status)}")
            releaseFramebuffer()
        }
    }

    private fun releaseViewer() {
        if (viewer != 0L) {
            CardboardNative.nativeViewerDestroy(viewer)
            viewer = 0L
        }
    }

    private fun releaseFramebuffer() {
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (depthBuffer != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depthBuffer), 0)
        GlUtil.deleteTexture(colorTexture)
        framebuffer = 0; depthBuffer = 0; colorTexture = 0
    }

    companion object {
        private const val TAG = "NovaVR-Stereo"
        const val Z_NEAR = 0.1f
        const val Z_FAR = 100f

        // Deep blue-black "void" around the virtual screen.
        private const val CLEAR_R = 0.020f
        private const val CLEAR_G = 0.024f
        private const val CLEAR_B = 0.035f
    }
}
