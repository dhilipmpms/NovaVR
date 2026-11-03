/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input.shizuku

import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku *user service*: this class is instantiated by Shizuku in a separate
 * process running as the **shell** user (uid 2000) — the same privileges as
 * `adb shell`, never root.
 *
 * The shell user holds `INJECT_EVENTS`, so it can inject input events with the
 * (hidden) `InputManagerGlobal.injectInputEvent`. This is what `adb shell input`
 * does. Injected pointer events are hit-tested like real ones, so they reach
 * the app under the given coordinates.
 *
 * Nothing here runs in NovaVR's own process. The process exits when NovaVR
 * unbinds (Shizuku calls [destroy]).
 */
class InjectorUserService : IInputInjector.Stub() {

    private val injector: Pair<Any, Method> by lazy { resolveInjector() }
    private val setActionButton: Method? by lazy {
        runCatching { MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType) }.getOrNull()
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun injectMouse(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        actionButton: Int,
        vScroll: Float,
        hScroll: Float,
        downTime: Long,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = if (buttonState != 0) 1f else 0f
                size = 1f
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            },
        )
        val event = MotionEvent.obtain(
            if (downTime > 0) downTime else now, now, action, 1, props, coords,
            0, buttonState, 1f, 1f, VIRTUAL_DEVICE_ID, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            setActionButton?.invoke(event, actionButton)
        }
        return try {
            inject(event)
        } finally {
            event.recycle()
        }
    }

    override fun injectKey(action: Int, keyCode: Int, metaState: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now, now, action, keyCode, 0, metaState, VIRTUAL_DEVICE_ID, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        return inject(event)
    }

    override fun enableAccessibilityService(flattenedComponent: String): String? = try {
        val current = run("settings", "get", "secure", "enabled_accessibility_services").trim()
        val list = current.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        if (flattenedComponent !in list) list += flattenedComponent
        run("settings", "put", "secure", "enabled_accessibility_services", list.joinToString(":"))
        run("settings", "put", "secure", "accessibility_enabled", "1")
        null
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    private fun inject(event: InputEvent): Boolean {
        val (target, method) = injector
        return method.invoke(target, event, INJECT_MODE_ASYNC) as Boolean
    }

    private fun resolveInjector(): Pair<Any, Method> {
        // Android 14+: InputManagerGlobal; older: InputManager.getInstance().
        runCatching {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val instance = cls.getMethod("getInstance").invoke(null)!!
            return instance to cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }
        val cls = Class.forName("android.hardware.input.InputManager")
        val instance = cls.getMethod("getInstance").invoke(null)!!
        return instance to cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }

    private fun run(vararg cmd: String): String {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("${cmd.joinToString(" ")} failed: $out")
        return out
    }

    private companion object {
        const val INJECT_MODE_ASYNC = 0
        val VIRTUAL_DEVICE_ID = KeyCharacterMap.VIRTUAL_KEYBOARD
    }
}
