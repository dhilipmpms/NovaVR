/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.input.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.novavr.app.BuildConfig
import rikka.shizuku.Shizuku

/** Shizuku availability, from least to most ready. */
sealed interface ShizukuStatus {
    data object NotInstalled : ShizukuStatus
    data object NotRunning : ShizukuStatus
    data object Unsupported : ShizukuStatus
    data object PermissionRequired : ShizukuStatus
    data object PermissionDenied : ShizukuStatus
    data object Connecting : ShizukuStatus

    /** User service bound; [uid] is its process uid (2000 = shell). */
    data class Ready(val uid: Int) : ShizukuStatus
    data class Error(val message: String) : ShizukuStatus

    /** Permission granted (the user service may or may not be bound yet). */
    val authorized: Boolean get() = this is Ready || this is Connecting
}

/**
 * Optional Shizuku integration. NovaVR works without it; with it, the mouse can
 * be injected as a real mouse (hover, right click, wheel) instead of touch
 * gestures, and the Accessibility service can be enabled in one tap.
 *
 * Shizuku runs a privileged service started through Wireless debugging (or
 * root, which NovaVR never requires). NovaVR binds a small user service
 * ([InjectorUserService]) only while VR input needs it and unbinds afterwards,
 * so no extra background process lingers.
 */
object ShizukuManager {
    private const val TAG = "NovaVR-Shizuku"
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 0x4E56

    private val _status = MutableStateFlow<ShizukuStatus>(ShizukuStatus.NotRunning)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _injector = MutableStateFlow<IInputInjector?>(null)
    val injector: StateFlow<IInputInjector?> = _injector.asStateFlow()

    private lateinit var appContext: Context
    private val scope = kotlinx.coroutines.MainScope()
    private var initialized = false
    private var wantBound = false
    private var bound = false
    private var deniedThisSession = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        Log.i(TAG, "Shizuku service died")
        bound = false
        _injector.value = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        deniedThisSession = result != PackageManager.PERMISSION_GRANTED
        refresh()
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext.packageName, InjectorUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("input")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val injector = service?.takeIf { it.pingBinder() }?.let { IInputInjector.Stub.asInterface(it) }
            _injector.value = injector
            _status.value = if (injector != null) {
                ShizukuStatus.Ready(runCatching { injector.uid() }.getOrDefault(-1))
            } else {
                ShizukuStatus.Error("User service did not respond")
            }
            Log.i(TAG, "User service connected: ${_status.value}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            _injector.value = null
            refresh()
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun isInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Re-evaluates the status (call when the app returns to the foreground). */
    fun refresh() {
        if (!initialized) return
        _status.value = when {
            !Shizuku.pingBinder() -> if (isInstalled()) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled
            Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> ShizukuStatus.Unsupported
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                if (deniedThisSession || Shizuku.shouldShowRequestPermissionRationale()) {
                    ShizukuStatus.PermissionDenied
                } else {
                    ShizukuStatus.PermissionRequired
                }
            _injector.value != null -> _status.value.takeIf { it is ShizukuStatus.Ready }
                ?: ShizukuStatus.Ready(-1)
            else -> ShizukuStatus.Connecting
        }
        if (_status.value is ShizukuStatus.Connecting && wantBound) bindNow()
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) return refresh()
        deniedThisSession = false
        Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Binds the user service while VR input needs it. */
    fun acquire() {
        wantBound = true
        refresh()
    }

    /** Unbinds when VR input stops; Shizuku then ends the service process. */
    fun release() {
        wantBound = false
        if (bound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
            bound = false
        }
        _injector.value = null
        refresh()
    }

    private fun bindNow() {
        if (bound) return
        bound = true
        runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure {
                bound = false
                _status.value = ShizukuStatus.Error(it.message ?: "Unable to start the Shizuku service")
            }
    }

    /**
     * Enables NovaVR's Accessibility service through the Shizuku user service
     * (shell can write secure settings). Binds temporarily if needed and calls
     * [onResult] on the main thread with an error message or null on success.
     */
    fun enableAccessibility(component: ComponentName, onResult: (String?) -> Unit) {
        val wasWanted = wantBound
        scope.launch {
            if (injector.value == null) acquire()
            // Wait (event-driven) for the user service to connect.
            val bound = withTimeoutOrNull(5_000) { injector.filterNotNull().first() }
            val error = if (bound == null) {
                "Shizuku service did not start"
            } else {
                withInjector { it.enableAccessibilityService(component.flattenToString()) }
            }
            if (!wasWanted) release()
            onResult(error)
        }
    }

    /** Runs [block] with the bound injector, ignoring a dead binder. */
    inline fun <T> withInjector(block: (IInputInjector) -> T): T? {
        val injector = injector.value ?: return null
        return try {
            block(injector)
        } catch (e: android.os.RemoteException) {
            null
        }
    }
}
