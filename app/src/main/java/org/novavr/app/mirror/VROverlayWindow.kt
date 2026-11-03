/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.mirror

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Display
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.novavr.app.BuildConfig
import org.novavr.app.input.NovaCommand
import org.novavr.app.input.VRInputController
import org.novavr.app.input.accessibility.NovaAccessibilityService
import org.novavr.app.settings.VRSettingsRepository
import org.novavr.app.ui.MirrorOverlayInfo
import org.novavr.app.ui.VRControls
import org.novavr.app.ui.VROverlay
import org.novavr.app.ui.theme.NovaVRTheme
import org.novavr.app.vr.VRSession

/**
 * Full-screen VR window drawn *over* the app being mirrored.
 *
 * Why an overlay instead of an Activity: Android only renders an app while it
 * is visible. If NovaVR's VR activity covered the shared app, the app would be
 * stopped and the capture would freeze. An overlay window does not pause or
 * stop the activity below it, so the shared app keeps running and drawing
 * while NovaVR displays it in VR.
 *
 * Feedback-loop protection: the window is [WindowManager.LayoutParams.FLAG_SECURE].
 * With single-app capture the overlay is never part of the captured content
 * anyway; if the user shares the entire screen instead, the overlay appears
 * black in the capture rather than recursively containing itself.
 *
 * Window type (decided each time the window is shown):
 *  - **Accessibility overlay** (`TYPE_ACCESSIBILITY_OVERLAY`, added through
 *    NovaVR's Accessibility service) when that service is enabled. It is a
 *    *trusted* overlay: since Android 12, touches that pass through an opaque
 *    overlay of another app are dropped as "untrusted" — except for
 *    accessibility overlays. Mouse clicks injected into the shared app only
 *    work with this window type.
 *  - **Application overlay** (`TYPE_APPLICATION_OVERLAY`, "Display over other
 *    apps") otherwise: VR viewing works, but clicks cannot pass through.
 *
 * Main thread only.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class VROverlayWindow(
    serviceContext: Context,
    private val settingsRepository: VRSettingsRepository,
    private val source: MirrorSource,
    private val onHideRequested: () -> Unit,
    private val onStopRequested: () -> Unit,
) {
    private val display: Display = serviceContext.getSystemService(android.hardware.display.DisplayManager::class.java)
        .getDisplay(Display.DEFAULT_DISPLAY)
    private val overlayContext: Context by lazy {
        serviceContext.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    // Chosen in show(): accessibility service context or overlay window context.
    private var context: Context = serviceContext
    private var windowManager: WindowManager = serviceContext.getSystemService(WindowManager::class.java)
    private var windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** True when hosted as a trusted accessibility overlay. */
    var isTrustedOverlay = false
        private set

    private val lifecycleOwner = OverlayLifecycleOwner()
    private var root: OverlayRoot? = null
    private var session: VRSession? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var contentVisibleState by mutableStateOf(true)
    private var input: VRInputController? = null

    val isShowing: Boolean get() = root != null

    /**
     * Debug builds only: `adb shell setprop debug.novavr.insecure_overlay 1`
     * lets screenshots / screen recordings see the VR overlay for testing.
     * Never honoured in release builds.
     */
    private val secure: Boolean = !(BuildConfig.DEBUG && debugProperty("debug.novavr.insecure_overlay") == "1")

    fun setContentVisible(visible: Boolean) {
        contentVisibleState = visible
    }

    fun show() {
        if (root != null) return
        val a11y = NovaAccessibilityService.instance.value
        if (a11y != null) {
            context = a11y
            windowManager = a11y.getSystemService(WindowManager::class.java)
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            context = overlayContext
            windowManager = overlayContext.getSystemService(WindowManager::class.java)
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        isTrustedOverlay = a11y != null
        val params = createLayoutParams()
        lateinit var vrSession: VRSession
        val inputController = VRInputController(
            context = context,
            mirroring = true,
            dock = MIRROR_DOCK,
            onCommand = { command -> onCommand(command, vrSession) },
            captureView = { root },
            onPassThroughChanged = ::setPassThrough,
        )
        vrSession = VRSession(
            context = context,
            settingsRepository = settingsRepository,
            calibrationMode = false,
            mirrorSource = source,
            cursorSource = { inputController.cursor.snapshot },
            dock = MIRROR_DOCK,
            displayProvider = { display },
            onPreferredDisplayMode = { modeId -> updatePreferredMode(modeId) },
        )
        val controls = VRControls(
            onExit = onHideRequested,
            exitDescription = "Hide VR (mirroring continues)",
            onToggleMode = vrSession::toggleDisplayMode,
            onRecenter = vrSession::recenter,
            onAdjustSize = vrSession::adjustScreenSize,
            onAdjustDistance = vrSession::adjustScreenDistance,
            onStopMirroring = onStopRequested,
        )
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                val settings by settingsRepository.settings.collectAsState()
                val inputStatus by inputController.status.collectAsState()
                NovaVRTheme {
                    VROverlay(
                        glView = vrSession.glView,
                        state = vrSession.state,
                        settings = settings,
                        calibrationMode = false,
                        mirror = MirrorOverlayInfo(contentVisible = contentVisibleState),
                        controls = controls,
                        inputLine = inputStatus.summary(mirroring = true),
                    )
                }
            }
        }
        val view = OverlayRoot(context, inputController, onBack = onHideRequested).apply {
            addView(composeView)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }
        // Keep the captured pixels out of screenshots / other captures too.
        vrSession.glView.setSecure(secure)

        lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        windowManager.addView(view, params)
        view.windowInsetsController?.hide(WindowInsets.Type.systemBars())
        vrSession.resume()

        root = view
        session = vrSession
        layoutParams = params
        input = inputController
        inputController.start()
    }

    private fun onCommand(command: NovaCommand, vrSession: VRSession) {
        when (command) {
            NovaCommand.RECENTER -> vrSession.recenter()
            NovaCommand.TOGGLE_MODE -> vrSession.toggleDisplayMode()
            NovaCommand.HIDE_VR -> onHideRequested()
            NovaCommand.STOP_MIRRORING -> onStopRequested()
            NovaCommand.CLOSER -> vrSession.adjustScreenDistance(-1)
            NovaCommand.FARTHER -> vrSession.adjustScreenDistance(1)
            NovaCommand.BIGGER -> vrSession.adjustScreenSize(1)
            NovaCommand.SMALLER -> vrSession.adjustScreenSize(-1)
            NovaCommand.TOGGLE_CURSOR -> settingsRepository.update { it.copy(cursorVisible = !it.cursorVisible) }
        }
    }

    /**
     * While a mouse drives the shared app, the overlay must not catch touches:
     * clicks injected at the cursor position (Accessibility gestures or Shizuku
     * events) are hit-tested like real touches and would otherwise land on
     * NovaVR itself. Pointer capture and keys still reach the overlay because
     * they follow window focus, not touchability.
     */
    private fun setPassThrough(passThrough: Boolean) {
        val view = root ?: return
        val params = layoutParams ?: return
        val flags = if (passThrough) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (flags == params.flags) return
        params.flags = flags
        windowManager.updateViewLayout(view, params)
    }

    /** Removes the window. GL resources are released before the view goes away. */
    fun dismiss() {
        val view = root ?: return
        input?.destroy()
        input = null
        session?.destroy() // blocks until the GL thread has released its objects
        session = null
        lifecycleOwner.moveTo(Lifecycle.State.CREATED)
        windowManager.removeViewImmediate(view)
        root = null
        layoutParams = null
    }

    private fun updatePreferredMode(modeId: Int) {
        val view = root ?: return
        val params = layoutParams ?: return
        if (params.preferredDisplayModeId == modeId) return
        params.preferredDisplayModeId = modeId
        windowManager.updateViewLayout(view, params)
    }

    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        windowType,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            if (secure) WindowManager.LayoutParams.FLAG_SECURE else 0,
        PixelFormat.OPAQUE,
    ).apply {
        title = "NovaVR"
        // The phone sits sideways in the headset. An overlay's orientation
        // request is honoured on phones, so the display stays landscape even
        // if the shared app prefers portrait (it is then letterboxed).
        screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    @android.annotation.SuppressLint("PrivateApi")
    private fun debugProperty(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, key) as String
    }.getOrNull()

    /**
     * Root view: owns pointer capture and receives keys (window focus), and
     * handles Back for a window that has no Activity.
     */
    private class OverlayRoot(
        context: Context,
        private val input: VRInputController,
        private val onBack: () -> Unit,
    ) : FrameLayout(context) {
        private val backCallback = OnBackInvokedCallback { onBack() }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnCapturedPointerListener { _, event -> input.onCapturedPointer(event) }
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            // Captured pointer events go to the focused view.
            if (hasWindowFocus) requestFocus()
            input.onWindowFocusChanged(hasWindowFocus)
        }

        override fun onPointerCaptureChange(hasCapture: Boolean) {
            super.onPointerCaptureChange(hasCapture)
            input.onPointerCaptureChanged(hasCapture)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback,
            )
        }

        override fun onDetachedFromWindow() {
            findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(backCallback)
            super.onDetachedFromWindow()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // Physical keyboards first: NovaVR shortcuts, system keys, typing.
            if (input.onKey(event)) return true
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private companion object {
        val MIRROR_DOCK = listOf(
            NovaCommand.STOP_MIRRORING, NovaCommand.HIDE_VR, NovaCommand.TOGGLE_MODE, NovaCommand.RECENTER,
            NovaCommand.SMALLER, NovaCommand.BIGGER, NovaCommand.CLOSER, NovaCommand.FARTHER,
        )
    }

    /** Minimal lifecycle + saved-state owner so Compose can run in a service window. */
    private class OverlayLifecycleOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this).apply { performRestore(null) }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
