/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.novavr.app.MainActivity
import org.novavr.app.R
import org.novavr.app.input.accessibility.NovaAccessibilityService
import org.novavr.app.novaApp
import org.novavr.app.settings.DisplayMode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Foreground service (type `mediaProjection`) that owns a screen-mirroring
 * session:
 *
 *  MediaProjection ─► VirtualDisplay ─► Surface ─► SurfaceTexture ─► GL (VR)
 *
 * Android 14+ rules followed here:
 *  - `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` is called
 *    *before* `getMediaProjection()`.
 *  - The consent result is single use: one projection, one VirtualDisplay.
 *    Size changes use [VirtualDisplay.resize], never a second display.
 *  - A [MediaProjection.Callback] is registered before capturing, and
 *    `onStop` (user tapped "Stop sharing", screen locked, another projection
 *    started...) tears everything down.
 *
 * The VR overlay can be hidden while the projection stays alive (so the user
 * can interact with the shared app); the VirtualDisplay then has no surface
 * and produces no frames.
 *
 * Mirroring requires Android 14 ([isSupported]); on older versions the
 * service refuses to start.
 */
class ScreenMirrorService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var source: MirrorSource? = null
    private var overlay: VROverlayWindow? = null
    private var stopping = false

    /** Size of the captured region as reported by the system (unscaled). */
    private var contentWidth = 0
    private var contentHeight = 0
    private var contentVisible = true

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by the system or user")
            stopMirroring()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            Log.d(TAG, "Captured content resized to ${width}x$height")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) onContentSize(width, height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Log.d(TAG, "Captured content visible=$isVisible")
            contentVisible = isVisible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overlay?.setContentVisible(isVisible)
            MirrorStatus.update { it.copy(contentVisible = isVisible) }
        }
    }

    /** Hide VR when the screen turns off; there is nothing to look at. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) hideVr()
        }
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("NovaVR-Capture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopMirroring()
            ACTION_SHOW_VR -> showVr()  // SDK guarded above
            ACTION_HIDE_VR -> hideVr()
            else -> if (projection == null) stopSelf()
        }
        // A projection cannot be recreated without fresh user consent.
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun handleStart(intent: Intent) {
        if (projection != null) {
            showVr()
            return
        }
        stopping = false
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

        // Must be in the foreground with the mediaProjection type *before*
        // obtaining the projection.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(vrVisible = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = data?.let { runCatching { mpm.getMediaProjection(resultCode, it) }.getOrNull() }
        if (mediaProjection == null) {
            fail("Screen capture permission was not granted.")
            return
        }
        if (!canShowOverlay(this)) {
            mediaProjection.stop()
            fail("NovaVR needs “Display over other apps” (or its Accessibility service) to show VR over the shared app.")
            return
        }
        projection = mediaProjection
        mediaProjection.registerCallback(projectionCallback, mainHandler)

        // Placeholder until onCapturedContentResize reports the real region
        // (it is called right after capture starts): the display in its
        // current rotation.
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = resources.displayMetrics
        val naturalW = display.mode.physicalWidth
        val naturalH = display.mode.physicalHeight
        val rotated = display.rotation == android.view.Surface.ROTATION_90 ||
            display.rotation == android.view.Surface.ROTATION_270
        contentWidth = if (rotated) naturalH else naturalW
        contentHeight = if (rotated) naturalW else naturalH
        val mirrorSource = MirrorSource(captureHandler)
        val (w, h) = scaledSize(contentWidth, contentHeight)
        mirrorSource.setBufferSize(w, h)
        source = mirrorSource

        virtualDisplay = try {
            mediaProjection.createVirtualDisplay(
                "NovaVR-Mirror",
                w,
                h,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mirrorSource.surface,
                null,
                captureHandler,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            null
        }
        if (virtualDisplay == null) {
            fail("Unable to start screen capture.")
            return
        }

        MirrorStatus.set(
            MirrorState.Active(contentWidth, contentHeight, contentVisible = true, vrVisible = false),
        )
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Accessibility switched on/off mid-session: re-host the VR window so
        // it is a trusted overlay whenever possible (see VROverlayWindow).
        scope.launch {
            NovaAccessibilityService.instance.collect { a11y ->
                val window = overlay ?: return@collect
                if (window.isShowing && window.isTrustedOverlay != (a11y != null)) {
                    window.dismiss()
                    showVr()
                }
            }
        }

        // Resolution / follow-orientation preferences may change mid-session.
        scope.launch {
            novaApp.settings.settings
                .map { it.mirrorResolutionScale }
                .distinctUntilChanged()
                .collect { applyVirtualDisplaySize() }
        }
        showVr()
    }

    // VR overlay ---------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun showVr() {
        val mirrorSource = source ?: return
        if (overlay?.isShowing == true) return
        if (!canShowOverlay(this)) return
        val window = overlay ?: VROverlayWindow(
            serviceContext = this,
            settingsRepository = novaApp.settings,
            source = mirrorSource,
            onHideRequested = ::hideVr,
            onStopRequested = ::stopMirroring,
        ).also { overlay = it }
        virtualDisplay?.surface = mirrorSource.surface
        window.setContentVisible(contentVisible)
        window.show()
        MirrorStatus.update { it.copy(vrVisible = true) }
        updateNotification(vrVisible = true)
    }

    private fun hideVr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val window = overlay ?: return
        if (!window.isShowing) return
        window.dismiss()
        // Nobody consumes frames while hidden: stop producing them.
        virtualDisplay?.surface = null
        MirrorStatus.update { it.copy(vrVisible = false) }
        updateNotification(vrVisible = false)
    }

    // Capture size -------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun onContentSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        contentWidth = width
        contentHeight = height
        applyVirtualDisplaySize()
        MirrorStatus.update { it.copy(contentWidth = width, contentHeight = height) }

        val settings = novaApp.settings
        if (settings.current.followContentOrientation) {
            val mode = if (height > width) DisplayMode.PORTRAIT else DisplayMode.LANDSCAPE
            settings.update { it.copy(displayMode = mode) }
        }
    }

    /**
     * Sizes the VirtualDisplay to the captured content (same aspect ratio, so
     * nothing is letterboxed inside the buffer) times the resolution scale.
     */
    private fun applyVirtualDisplaySize() {
        val vd = virtualDisplay ?: return
        val mirrorSource = source ?: return
        val (w, h) = scaledSize(contentWidth, contentHeight)
        if (w == mirrorSource.bufferWidth && h == mirrorSource.bufferHeight) return
        mirrorSource.setBufferSize(w, h)
        vd.resize(w, h, resources.displayMetrics.densityDpi)
        Log.d(TAG, "VirtualDisplay resized to ${w}x$h")
    }

    private fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = novaApp.settings.current.mirrorResolutionScale
        // Even dimensions keep video encoders/compositors happy.
        val w = max(2, (width * scale).roundToInt() / 2 * 2)
        val h = max(2, (height * scale).roundToInt() / 2 * 2)
        return w to h
    }

    // Teardown -----------------------------------------------------------------

    /**
     * Ordered cleanup: GL users first (overlay), then the producer
     * (VirtualDisplay), then the stream (SurfaceTexture), then the projection.
     */
    private fun stopMirroring() {
        if (stopping) return
        stopping = true
        scope.coroutineContext.cancelChildren()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overlay?.dismiss()
        overlay = null
        virtualDisplay?.release()
        virtualDisplay = null
        source?.release()
        source = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        if (projection != null) runCatching { unregisterReceiver(screenOffReceiver) }
        projection = null
        if (MirrorStatus.state.value !is MirrorState.Failed) MirrorStatus.set(MirrorState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        MirrorStatus.set(MirrorState.Failed(message))
        stopMirroring()
    }

    override fun onDestroy() {
        stopMirroring()
        scope.cancel()
        captureThread.quitSafely()
        super.onDestroy()
    }

    // Notification -------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen mirroring",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while NovaVR mirrors the screen into VR" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(vrVisible: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleVr = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenMirrorService::class.java).setAction(if (vrVisible) ACTION_HIDE_VR else ACTION_SHOW_VR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vr)
            .setContentTitle("NovaVR is mirroring your screen")
            .setContentText(if (vrVisible) "VR view is on" else "VR view is hidden — tap Show VR to return")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (vrVisible) "Hide VR" else "Show VR", toggleVr)
            .addAction(0, "Stop mirroring", stop)
            .build()
    }

    private fun updateNotification(vrVisible: Boolean) {
        if (projection == null) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(vrVisible))
    }

    companion object {
        private const val TAG = "NovaVR-Mirror"
        private const val CHANNEL_ID = "mirroring"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_START = "org.novavr.app.mirror.START"
        private const val ACTION_STOP = "org.novavr.app.mirror.STOP"
        private const val ACTION_SHOW_VR = "org.novavr.app.mirror.SHOW_VR"
        private const val ACTION_HIDE_VR = "org.novavr.app.mirror.HIDE_VR"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** VR can be drawn over other apps: overlay permission or Accessibility service. */
        fun canShowOverlay(context: Context): Boolean =
            Settings.canDrawOverlays(context) || NovaAccessibilityService.instance.value != null

        /** Mirroring needs single-app capture and its callbacks (Android 14). */
        val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenMirrorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenMirrorService::class.java).setAction(ACTION_STOP)

        fun showVrIntent(context: Context): Intent =
            Intent(context, ScreenMirrorService::class.java).setAction(ACTION_SHOW_VR)
    }
}

