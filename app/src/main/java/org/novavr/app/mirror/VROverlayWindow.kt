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
 * Requires the "Display over other apps" permission. Main thread only.
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
    private val context: Context =
        serviceContext.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val windowManager = context.getSystemService(WindowManager::class.java)

    private val lifecycleOwner = OverlayLifecycleOwner()
    private var root: OverlayRoot? = null
    private var session: VRSession? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var contentVisibleState by mutableStateOf(true)

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
        val params = createLayoutParams()
        val vrSession = VRSession(
            context = context,
            settingsRepository = settingsRepository,
            calibrationMode = false,
            mirrorSource = source,
            displayProvider = { context.display },
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
                NovaVRTheme {
                    VROverlay(
                        glView = vrSession.glView,
                        state = vrSession.state,
                        settings = settings,
                        calibrationMode = false,
                        mirror = MirrorOverlayInfo(contentVisible = contentVisibleState),
                        controls = controls,
                    )
                }
            }
        }
        val view = OverlayRoot(context, onBack = onHideRequested).apply {
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
    }

    /** Removes the window. GL resources are released before the view goes away. */
    fun dismiss() {
        val view = root ?: return
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
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

    /** Root view handling Back for a window that has no Activity. */
    private class OverlayRoot(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
        private val backCallback = OnBackInvokedCallback { onBack() }

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
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
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
