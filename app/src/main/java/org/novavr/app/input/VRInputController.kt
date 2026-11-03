/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.novavr.app.input.accessibility.AccessibilityKeyBackend
import org.novavr.app.input.accessibility.AccessibilityPointerBackend
import org.novavr.app.input.accessibility.NovaAccessibilityService
import org.novavr.app.input.shizuku.ShizukuManager
import org.novavr.app.input.shizuku.ShizukuPointerBackend
import org.novavr.app.input.shizuku.ShizukuStatus
import org.novavr.app.input.shizuku.ShizukuSystemKeys
import org.novavr.app.mirror.MirrorState
import org.novavr.app.mirror.MirrorStatus
import org.novavr.app.novaApp
import org.novavr.app.settings.DisplayMode
import org.novavr.app.settings.InputMode
import org.novavr.app.settings.VRSettings
import org.novavr.app.utils.DeviceInfo

/** What the VR input pipeline is currently able to do (shown in VR and Settings). */
data class InputSessionStatus(
    val mouseConnected: Boolean = false,
    val keyboardConnected: Boolean = false,
    val pointerCaptured: Boolean = false,
    /** Backend delivering clicks to the mirrored app, or null. */
    val pointerBackend: String? = null,
    /** Backend delivering typing to the mirrored app, or null. */
    val keyBackend: String? = null,
    val passThrough: Boolean = false,
) {
    /** One-line summary for the VR status bar, or null when nothing is connected. */
    fun summary(mirroring: Boolean): String? {
        if (!mouseConnected && !keyboardConnected) return null
        if (!mirroring) return if (mouseConnected) "Mouse: cursor" else "Keyboard: shortcuts"
        return when {
            mouseConnected && pointerBackend == null -> "Mouse: VR only — enable Accessibility to click"
            keyboardConnected && keyBackend == null && !mouseConnected -> "Keyboard: shortcuts only"
            else -> buildString {
                if (mouseConnected) append("Mouse: $pointerBackend")
                if (keyboardConnected) {
                    if (isNotEmpty()) append("  ·  ")
                    append("Typing: ${keyBackend ?: "off"}")
                }
            }
        }
    }
}

/**
 * Connects physical mouse/keyboard input to a VR host window.
 *
 * Responsibilities:
 *  - request **pointer capture** while a mouse is connected and the host window
 *    has focus: the system arrow disappears (it would otherwise float in one
 *    eye) and relative mouse motion drives the virtual cursor;
 *  - pick the backends for the configured [InputMode] from what is available
 *    (Accessibility service connected, Shizuku authorised);
 *  - tell the host to make its window **pass-through** (not touchable) while a
 *    pointer backend is active, so injected clicks reach the mirrored app
 *    underneath instead of NovaVR's own overlay;
 *  - map the cursor onto the display via the shared app's window bounds.
 */
class VRInputController(
    private val context: Context,
    private val mirroring: Boolean,
    dock: List<NovaCommand>,
    onCommand: (NovaCommand) -> Unit,
    private val captureView: () -> View?,
    private val onPassThroughChanged: (Boolean) -> Unit,
) {
    private val app = context.novaApp
    private val settingsRepository = app.settings
    private val deviceInfo = DeviceInfo.query(context)
    private val scope = MainScope()

    val cursor = CursorModel()
    private val router = InputRouter(
        cursor = cursor,
        dock = dock,
        settings = { settingsRepository.current },
        onCommand = onCommand,
        region = ::currentRegion,
    )

    private val _status = MutableStateFlow(InputSessionStatus())
    val status: StateFlow<InputSessionStatus> = _status.asStateFlow()

    private var hasFocus = false
    private var started = false
    private var shizukuAcquired = false
    private var cachedRegion: ContentRegion? = null
    private var cachedRegionAt = 0L
    private var cachedRegionLast: ContentRegion? = null
    private var a11yService: NovaAccessibilityService? = null

    fun start() {
        if (started) return
        started = true
        ShizukuManager.init(context)
        scope.launch {
            combine(
                app.inputDevices.devices,
                settingsRepository.settings,
                NovaAccessibilityService.instance,
                ShizukuManager.status,
                ShizukuManager.injector,
            ) { devices, settings, a11y, shizuku, injector ->
                Inputs(devices, settings, a11y, shizuku, injector != null)
            }.collect(::apply)
        }
        if (mirroring) {
            scope.launch {
                MirrorStatus.state.collect { invalidateRegion() }
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        router.reset()
        captureView()?.takeIf { it.hasPointerCapture() }?.releasePointerCapture()
        if (shizukuAcquired) {
            ShizukuManager.release()
            shizukuAcquired = false
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    fun onWindowFocusChanged(focused: Boolean) {
        hasFocus = focused
        if (!focused) router.reset()
        updateCapture()
    }

    fun onPointerCaptureChanged(captured: Boolean) {
        if (!captured) router.reset()
        _status.value = _status.value.copy(pointerCaptured = captured)
    }

    fun onCapturedPointer(event: MotionEvent): Boolean = router.onCapturedPointer(event)

    /** Keys from physical external keyboards only; on-screen / system keys pass through. */
    fun onKey(event: KeyEvent): Boolean =
        InputDeviceMonitor.isExternalKeyboard(event.device) && router.onKey(event)

    // State ----------------------------------------------------------------------

    private data class Inputs(
        val devices: ConnectedInput,
        val settings: VRSettings,
        val a11y: NovaAccessibilityService?,
        val shizuku: ShizukuStatus,
        val shizukuBound: Boolean,
    )

    private fun apply(inputs: Inputs) {
        val (devices, settings, a11y, shizuku, shizukuBound) = inputs
        a11yService = a11y
        cursor.configure(enabled = settings.cursorVisible && devices.hasMouse, autoHideSeconds = settings.cursorAutoHideSeconds)
        if (!mirroring) cursorForPattern(settings.displayMode)

        // Shizuku's user service is only bound while VR input may use it.
        val wantShizuku = mirroring && devices.hasMouse && shizuku.authorized &&
            (settings.inputMode == InputMode.AUTO || settings.inputMode == InputMode.SHIZUKU)
        if (wantShizuku && !shizukuAcquired) {
            ShizukuManager.acquire(); shizukuAcquired = true
        } else if (!wantShizuku && shizukuAcquired) {
            ShizukuManager.release(); shizukuAcquired = false
        }

        val a11yPointer = a11y?.let { svc ->
            AccessibilityPointerBackend(svc, { settingsRepository.current.rightClickAction }, ::regionBounds)
        }
        val shizukuPointer = if (shizukuBound) ShizukuPointerBackend() else null
        val pointer: PointerBackend? = if (!mirroring) null else when (settings.inputMode) {
            InputMode.AUTO -> shizukuPointer ?: a11yPointer
            InputMode.SHIZUKU -> shizukuPointer
            InputMode.ACCESSIBILITY -> a11yPointer
            InputMode.VR_ONLY -> null
        }
        val keys: KeyBackend? = if (!mirroring || settings.inputMode == InputMode.VR_ONLY) null
        else a11y?.let { AccessibilityKeyBackend(it) }

        // Keep the existing backend instance if its kind did not change (mid-drag safety).
        if (router.pointerBackend?.name != pointer?.name) router.pointerBackend = pointer
        if (router.keyBackend?.name != keys?.name) router.keyBackend = keys
        router.systemFallback = if (shizukuBound) ShizukuSystemKeys::perform else null

        val passThrough = mirroring && devices.hasMouse && pointer != null
        val previous = _status.value
        _status.value = previous.copy(
            mouseConnected = devices.hasMouse,
            keyboardConnected = devices.hasKeyboard,
            pointerBackend = pointer?.name,
            keyBackend = keys?.name,
            passThrough = passThrough,
        )
        if (previous.passThrough != passThrough) onPassThroughChanged(passThrough)
        Log.d(TAG, "input: ${_status.value}")
        updateCapture()
    }

    private fun updateCapture() {
        val view = captureView() ?: return
        val want = started && hasFocus && _status.value.mouseConnected
        if (want && !view.hasPointerCapture()) {
            view.requestPointerCapture()
        } else if (!want && view.hasPointerCapture()) {
            view.releasePointerCapture()
        }
    }

    // Mapping --------------------------------------------------------------------

    private fun cursorForPattern(mode: DisplayMode) {
        val long = deviceInfo.displayLongPx
        val short = deviceInfo.displayShortPx
        if (mode == DisplayMode.PORTRAIT) cursor.setContentSize(short, long) else cursor.setContentSize(long, short)
    }

    private fun invalidateRegion() {
        cachedRegion = null
        currentRegion()
    }

    /**
     * Display mapping of the mirrored content. Cached for a second: the window
     * bounds query is an IPC call, cheap enough per click, not per mouse move.
     */
    private fun currentRegion(): ContentRegion? {
        if (!mirroring) return null
        val active = MirrorStatus.state.value as? MirrorState.Active ?: return null
        val now = SystemClock.uptimeMillis()
        cachedRegion?.let { if (now - cachedRegionAt < REGION_TTL_MS) return it }
        val (displayW, displayH) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
                .let { it.width() to it.height() }
        } else {
            context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
        val region = ContentRegion.resolve(
            contentWidth = active.contentWidth,
            contentHeight = active.contentHeight,
            displayWidth = displayW,
            displayHeight = displayH,
            windowBounds = runCatching { a11yService?.appWindowBounds() }.getOrNull(),
        )
        if (region != cachedRegionLast) Log.d(TAG, "region: $region (window=${a11yService != null})")
        cachedRegionLast = region
        cursor.setContentSize(region.contentWidth, region.contentHeight)
        cachedRegion = region
        cachedRegionAt = now
        return region
    }

    private fun regionBounds(): RectF {
        val r = currentRegion() ?: return RectF(0f, 0f, 1f, 1f)
        return RectF(
            r.left.toFloat(), r.top.toFloat(),
            (r.left + r.contentWidth).toFloat(), (r.top + r.contentHeight).toFloat(),
        )
    }

    private companion object {
        const val TAG = "NovaVR-Input"
        const val REGION_TTL_MS = 1_000L
    }
}
