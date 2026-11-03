/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/** Physical pointing devices and keyboards Android currently recognises. */
data class ConnectedInput(
    val mice: List<String> = emptyList(),
    val keyboards: List<String> = emptyList(),
) {
    val hasMouse: Boolean get() = mice.isNotEmpty()
    val hasKeyboard: Boolean get() = keyboards.isNotEmpty()
}

/**
 * Watches input devices through [InputManager.InputDeviceListener]
 * (event-driven, no polling). Bluetooth and USB OTG devices both appear here
 * once Android has bound them.
 */
class InputDeviceMonitor(context: Context, scope: CoroutineScope) {
    private val inputManager = context.applicationContext.getSystemService(InputManager::class.java)

    val devices: StateFlow<ConnectedInput> = callbackFlow {
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { trySend(query()) }
            override fun onInputDeviceRemoved(deviceId: Int) { trySend(query()) }
            override fun onInputDeviceChanged(deviceId: Int) { trySend(query()) }
        }
        inputManager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        trySend(query())
        awaitClose { inputManager.unregisterInputDeviceListener(listener) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), query())

    fun query(): ConnectedInput {
        val all = ArrayList<InputDevice>()
        for (id in inputManager.inputDeviceIds) {
            val device = inputManager.getInputDevice(id) ?: continue
            if (!device.isVirtual && device.isExternal && device.isEnabled) all += device
        }
        return ConnectedInput(
            mice = all.filter { isPointer(it) }.map { it.name }.distinct(),
            keyboards = all.filter(::isTypingKeyboard).map { it.name }.distinct(),
        )
    }

    companion object {
        /**
         * Mice and touchpads. While pointer capture is active Android re-labels
         * a captured mouse as SOURCE_MOUSE_RELATIVE, so that counts too —
         * otherwise capturing would make the mouse "disappear" and release the
         * capture again in a loop.
         */
        fun isPointer(device: InputDevice): Boolean =
            device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                device.supportsSource(InputDevice.SOURCE_TOUCHPAD)

        /**
         * A keyboard you can type on. Android 15+ classifies keyboards
         * heuristically and may report a new one as NON_ALPHABETIC until it
         * has seen letters typed, so the presence of letter keys decides.
         */
        fun isTypingKeyboard(device: InputDevice): Boolean =
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC ||
                (device.supportsSource(InputDevice.SOURCE_KEYBOARD) &&
                    device.hasKeys(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z).all { it })

        /** True for key events coming from a physical external keyboard. */
        fun isExternalKeyboard(device: InputDevice?): Boolean =
            device != null && !device.isVirtual && device.isExternal &&
                device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE
    }
}
