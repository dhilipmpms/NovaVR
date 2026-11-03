/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app

import android.app.Application
import android.content.Context
import org.novavr.app.settings.VRSettingsRepository

/** Process-wide singletons. Intentionally tiny: no DI framework needed yet. */
class NovaVRApplication : Application() {
    val settings: VRSettingsRepository by lazy { VRSettingsRepository(this) }
}

val Context.novaApp: NovaVRApplication
    get() = applicationContext as NovaVRApplication
