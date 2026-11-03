/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.MainScope
import org.novavr.app.input.InputDeviceMonitor
import org.novavr.app.settings.VRSettingsRepository

/** Process-wide singletons. Intentionally tiny: no DI framework needed yet. */
class NovaVRApplication : Application() {
    private val appScope = MainScope()
    val settings: VRSettingsRepository by lazy { VRSettingsRepository(this) }

    /** Connected mice / keyboards; listens only while someone observes it. */
    val inputDevices: InputDeviceMonitor by lazy { InputDeviceMonitor(this, appScope) }
}

val Context.novaApp: NovaVRApplication
    get() = applicationContext as NovaVRApplication
