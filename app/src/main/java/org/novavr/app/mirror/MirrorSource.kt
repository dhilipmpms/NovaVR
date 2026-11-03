/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.mirror

import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-copy bridge from a MediaProjection VirtualDisplay to OpenGL.
 *
 * The VirtualDisplay renders into [surface]; the frames land in a
 * [SurfaceTexture] which the VR renderer samples directly as an external OES
 * texture. No pixel ever touches the CPU.
 *
 * The SurfaceTexture is created *detached* so that it outlives any particular
 * EGL context: the capture pipeline belongs to the service, while the VR
 * renderer [attach]es / [detach]es as its GL context comes and goes.
 *
 * Threading: [attach], [detach] and [latchLatest] run on the GL thread;
 * [setBufferSize] on the service's main thread; frame callbacks on [handler].
 */
class MirrorSource(handler: Handler) {

    private val surfaceTexture = SurfaceTexture(false)
    val surface = Surface(surfaceTexture)

    private val pendingFrames = AtomicInteger(0)
    private var attached = false

    /** Size of the frames currently produced (== VirtualDisplay size). */
    @Volatile
    var bufferWidth = 1
        private set

    @Volatile
    var bufferHeight = 1
        private set

    /** True once at least one frame has been latched into the texture. */
    @Volatile
    var hasFrame = false
        private set

    /** Total frames latched, used for the capture frame-rate readout. */
    @Volatile
    var latchedFrames = 0L
        private set

    @Volatile
    private var released = false

    init {
        surfaceTexture.setOnFrameAvailableListener({ pendingFrames.incrementAndGet() }, handler)
    }

    val aspect: Float get() = bufferWidth.toFloat() / bufferHeight.coerceAtLeast(1)

    fun setBufferSize(width: Int, height: Int) {
        bufferWidth = width
        bufferHeight = height
        surfaceTexture.setDefaultBufferSize(width, height)
    }

    /** GL thread: binds the stream to [textureId] in the current context. */
    fun attach(textureId: Int) {
        if (released || attached) return
        surfaceTexture.attachToGLContext(textureId)
        attached = true
    }

    /** GL thread: unbinds from the current context (before it is destroyed). */
    fun detach() {
        if (!attached) return
        attached = false
        if (!released) surfaceTexture.detachFromGLContext()
    }

    /**
     * GL thread: latches the newest captured frame, dropping any older queued
     * ones (lowest latency), and writes the texture transform to [outMatrix].
     * Returns true when a frame is available to draw.
     */
    fun latchLatest(outMatrix: FloatArray): Boolean {
        if (!attached || released) return false
        val pending = pendingFrames.getAndSet(0)
        if (pending > 0) {
            // Each call acquires the next queued buffer and releases the
            // previous one; the last call leaves the newest frame bound.
            repeat(pending) { surfaceTexture.updateTexImage() }
            latchedFrames += pending
            hasFrame = true
        }
        if (hasFrame) surfaceTexture.getTransformMatrix(outMatrix)
        return hasFrame
    }

    /** Service thread, after the renderer has detached. */
    fun release() {
        if (released) return
        released = true
        surface.release()
        surfaceTexture.release()
    }
}
