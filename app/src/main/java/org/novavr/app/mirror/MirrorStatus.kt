/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.mirror

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface MirrorState {
    data object Idle : MirrorState

    /**
     * @property contentWidth / contentHeight size of the captured region.
     * @property contentVisible false when the shared app is hidden (Android
     *   then delivers no new frames for it).
     * @property vrVisible whether the VR overlay is currently shown.
     */
    data class Active(
        val contentWidth: Int,
        val contentHeight: Int,
        val contentVisible: Boolean,
        val vrVisible: Boolean,
    ) : MirrorState

    data class Failed(val message: String) : MirrorState
}

/** Process-wide mirroring state, published by [ScreenMirrorService]. */
object MirrorStatus {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    internal fun set(state: MirrorState) {
        _state.value = state
    }

    /** Dismisses a [MirrorState.Failed] message. */
    fun clearError() {
        if (_state.value is MirrorState.Failed) _state.value = MirrorState.Idle
    }

    internal fun update(transform: (MirrorState.Active) -> MirrorState.Active) {
        val current = _state.value
        if (current is MirrorState.Active) _state.value = transform(current)
    }
}
