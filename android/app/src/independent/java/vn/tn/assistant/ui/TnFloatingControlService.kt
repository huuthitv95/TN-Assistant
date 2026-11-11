package vn.tn.assistant.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import vn.tn.assistant.MainActivity
import vn.tn.assistant.R

/** Explicitly enabled utility overlay. It never records audio or owns a credential. */
class TnFloatingControlService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val accountWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var accountBackend: vn.tn.assistant.api.TnVoiceBackendClient? = null
    private var accountRefreshAt = 0L
    private val refreshingAccount = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun refreshAccount() {
        if (android.os.SystemClock.elapsedRealtime() < accountRefreshAt || !refreshingAccount.compareAndSet(false,true)) return
        accountRefreshAt = android.os.SystemClock.elapsedRealtime() + 60000
        val generation = TnFloatingAccountPlan.generation()
        accountWorker.execute {
            try {
                val backend = accountBackend ?: vn.tn.assistant.api.TnVoiceBackendClient(this).also {accountBackend=it}
                TnFloatingAccountPlan.update(this, backend.json("/v1/me"), generation)
            } catch (_: Exception) {
                // No stale entitlement is assumed: the display snapshot expires after five minutes.
            } finally { refreshingAccount.set(false) }
        }
    }
    private lateinit var windows: WindowManager
    private lateinit var preferences: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private var bubble: TnFloatingClusterView? = null
    private var drag: TnFloatingDragController? = null
    private var layout: WindowManager.LayoutParams? = null
    private val microphoneClickGate = ClickGate(800L)
    private val opacitySpring = TnFloatingSpring(1.0)
    private val opacitySystem = TnFloatingSpringSystem()
    private val idleOpacity = Runnable {
        if (bubble != null && preferences.getString(KEY_OPACITY, "auto") == "auto") {
            opacitySystem.target(opacitySpring, .6)
        }
    }

    private fun refreshOpacity(scheduleIdle: Boolean = true) {
        main.removeCallbacks(idleOpacity)
        val mode = preferences.getString(KEY_OPACITY, "auto").orEmpty()
        opacitySystem.target(opacitySpring, TnFloatingGeometry.opacity(mode).toDouble())
        // c2.x0.h / c2.g0 case 8: Auto returns to 60% after 4000ms.
        if (scheduleIdle && mode == "auto" && bubble != null) main.postDelayed(idleOpacity, 4000)
    }
    private val permissionMonitor = object : Runnable {
        override fun run() {
            refreshAccount()
            if (!hasPermission(this@TnFloatingControlService) || !isEnabled(this@TnFloatingControlService) || isHidden(this@TnFloatingControlService)) stopSelf()
            else {
                if (isAppForegroundSuppressed(this@TnFloatingControlService)) detachBubble()
                else if (bubble == null) try { attach() } catch (_: RuntimeException) { stopSelf(); return }
                if (bubble?.accountPlan != TnFloatingAccountPlan.read(this@TnFloatingControlService)) resize(refreshIdle = false)
                bubble?.accountBadge?.let { badge ->
                    val oldKind = badge.contentDescription?.toString()
                    badge.plan = bubble?.accountPlan // Recompute day count and accessibility label.
                    if (oldKind != badge.contentDescription?.toString()) { accountRefreshAt = 0; refreshAccount() }
                }
                main.postDelayed(this, 2000)
            }
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); instance = this
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        preferences = settings(this)
        preferences.registerOnSharedPreferenceChangeListener(this)
        opacitySystem.register(opacitySpring) { bubble?.alpha = it.toFloat().coerceIn(0f, 1f) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { preferences.edit().putBoolean(KEY_ENABLED, false).apply(); stopSelf(); return START_NOT_STICKY }
        if (!hasPermission(this) || !isEnabled(this) || isHidden(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification())
            if (isAppForegroundSuppressed(this)) detachBubble()
            else if (bubble == null) attach() else resize()
        } catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        main.removeCallbacks(permissionMonitor); main.postDelayed(permissionMonitor, 2000)
        return START_STICKY
    }
    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Tiện ích nổi TN", NotificationManager.IMPORTANCE_LOW))
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 42, openIntent(false), pendingFlags)
        val stop = PendingIntent.getService(this, 43, Intent(this, TnFloatingControlService::class.java).setAction(ACTION_STOP), pendingFlags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.tn_floating_microphone)
            .setContentTitle("Tiện ích nổi TN đang bật")
            .setContentText("Chạm micro để mở trợ lý. Kéo vào dấu × để ẩn.")
            .setContentIntent(open).setOngoing(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(0, "TẮT", stop).build()).build()
    }
    private fun openIntent(traffic: Boolean) = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(if (traffic) EXTRA_OPEN_TRAFFIC else EXTRA_OPEN_ASSISTANT, true)
    private fun open() { try { startActivity(openIntent(false)) } catch (_: RuntimeException) {} }
    private fun size() = TnFloatingGeometry.unit(resources.displayMetrics.widthPixels,
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        preferences.getString(KEY_SIZE, "standard").orEmpty())
    private fun suggestionVisible() = preferences.getBoolean(KEY_SUGGESTION_VISIBLE, true)
    private fun attach() {
        val metrics = resources.displayMetrics; val unit = size(); val showHint = suggestionVisible()
        val view = TnFloatingClusterView(this).apply {
            this.unit = unit; suggestionVisible = showHint; accountPlan = TnFloatingAccountPlan.read(this@TnFloatingControlService)
            accountBadge.setOnClickListener { startActivity(openIntent(false).putExtra(EXTRA_OPEN_PLAN, true)) }
            microButton.setOnClickListener {
                // ChatHeadService uses an 800ms action gate for floating taps.
                if (microphoneClickGate.accept(android.os.SystemClock.elapsedRealtime())) {
                    preferences.edit().putBoolean(KEY_SUGGESTION_VISIBLE, false).apply(); open()
                }
            }
            dismiss = { hide() }
        }
        val (width, height) = TnFloatingGeometry.dimensions(unit, showHint, metrics.density, view.accountPlan != null)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        val params = WindowManager.LayoutParams(width, height,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (preferences.getFloat(KEY_X, .879f) * metrics.widthPixels - view.anchorCenterX).toInt()
            y = (preferences.getFloat(KEY_Y, .574f) * metrics.heightPixels - view.anchorCenterY).toInt()
        }
        layout = params; clamp()
        drag = TnFloatingDragController(this, windows, view, params,
            moved = { x, y -> params.x = x; params.y = y; clamp(); updateWindow() },
            savePosition = { persistPosition() }, hide = { hide() })
        view.routeTouch = { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { refreshOpacity(false); drag?.down(event); view.pressAt(event.x, event.y); true }
                MotionEvent.ACTION_MOVE -> { drag?.move(event); true }
                MotionEvent.ACTION_UP -> { view.releasePress(); if (drag?.release(event) != true) view.clickAt(event.x, event.y); refreshOpacity(); true }
                MotionEvent.ACTION_CANCEL -> { view.releasePress(); drag?.release(event, true); refreshOpacity(); true }
                else -> false
            }
        }
        bubble = view
        opacitySystem.snap(opacitySpring, TnFloatingGeometry.opacity(preferences.getString(KEY_OPACITY, "auto").orEmpty()).toDouble())
        updateVisualState(); windows.addView(view, params)
    }
    private fun updateVisualState() {
        bubble?.apply {
            microEnabled = preferences.getBoolean(KEY_MIC_ENABLED, true)
        }
        refreshOpacity()
    }
    private fun clamp() {
        val params = layout ?: return; val metrics = resources.displayMetrics
        params.x = TnFloatingGeometry.clampOrigin(params.x, metrics.widthPixels, params.width)
        params.y = TnFloatingGeometry.clampOrigin(params.y, metrics.heightPixels, params.height)
    }
    private fun updateWindow() {
        val view = bubble ?: return; val params = layout ?: return
        if (!view.isAttachedToWindow) return
        try { windows.updateViewLayout(view, params) } catch (_: RuntimeException) { stopSelf() }
    }
    private fun resize(refreshIdle: Boolean = true) {
        val view = bubble ?: return; val params = layout ?: return
        val centerX = params.x + view.anchorCenterX; val centerY = params.y + view.anchorCenterY
        val unit = size(); val showHint = suggestionVisible(); val density = resources.displayMetrics.density
        view.accountPlan = TnFloatingAccountPlan.read(this)
        val (width, height) = TnFloatingGeometry.dimensions(unit, showHint, density, view.accountPlan != null)
        view.unit = unit; view.suggestionVisible = showHint
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        params.width = width; params.height = height
        params.x = centerX - view.anchorCenterX; params.y = centerY - view.anchorCenterY
        clamp(); drag?.resized()
        // A background account response is not user interaction. Preserve the
        // existing idle-opacity deadline while expanding/updating its badge.
        if (refreshIdle) updateVisualState()
        updateWindow()
    }
    private fun persistPosition() {
        val params = layout ?: return; val metrics = resources.displayMetrics; val view = bubble ?: return
        preferences.edit().putFloat(KEY_X, (params.x + view.anchorCenterX.toFloat()) / metrics.widthPixels)
            .putFloat(KEY_Y, (params.y + view.anchorCenterY.toFloat()) / metrics.heightPixels).apply()
    }
    private fun hide() { persistPosition(); preferences.edit().putBoolean(KEY_HIDDEN, true).apply(); stopSelf() }
    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        when (key) {
            KEY_ENABLED, KEY_HIDDEN -> if (!isEnabled(this) || isHidden(this)) stopSelf()
            KEY_APP_FOREGROUND -> if (isAppForegroundSuppressed(this)) detachBubble()
                else if (hasPermission(this) && isEnabled(this) && !isHidden(this) && bubble == null) {
                    try { attach() } catch (_: RuntimeException) { stopSelf() }
                }
            KEY_SIZE, KEY_SUGGESTION_VISIBLE -> resize()
            "account_plan_display" -> resize(refreshIdle = false)
            KEY_OPACITY, KEY_MIC_ENABLED -> updateVisualState()
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Recreate only overlay windows, preserving saved normalized position.
        // The magnetic target and bottom shade also need the new viewport size.
        if (bubble == null) return
        detachBubble()
        if (hasPermission(this) && isEnabled(this) && !isHidden(this) && !isAppForegroundSuppressed(this)) {
            try { attach() } catch (_: RuntimeException) { stopSelf() }
        }
    }
    private fun detachBubble() {
        main.removeCallbacks(idleOpacity)
        opacitySystem.stopAtCurrent(opacitySpring)
        drag?.dispose(); drag = null
        bubble?.let { try { windows.removeView(it) } catch (_: RuntimeException) {} }
        bubble = null; layout = null
    }
    override fun onDestroy() {
        accountBackend?.cancel(); accountWorker.shutdownNow()
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null); detachBubble(); opacitySystem.dispose()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        stopForeground(true); super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_PLAN = "tn_open_floating_plan"
        const val EXTRA_OPEN_ASSISTANT = "tn_open_floating_assistant"
        const val EXTRA_OPEN_TRAFFIC = "tn_open_floating_traffic"
        const val KEY_ENABLED = "floating_enabled"
        const val KEY_HIDDEN = "floating_hidden"
        private const val KEY_APP_FOREGROUND = "tn_assistant_foreground_suppresses_overlay"
        const val KEY_SIZE = "floating_size"
        const val KEY_OPACITY = "floating_opacity"
        const val KEY_SUGGESTION_VISIBLE = "floating_suggestion_visible"
        private const val KEY_X = "floating_x"
        private const val KEY_Y = "floating_y"
        private const val KEY_MIC_ENABLED = "floating_microphone_enabled"
        private const val CHANNEL = "tn-floating-control"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "vn.tn.assistant.FLOATING_STOP"
        @Volatile private var instance: TnFloatingControlService? = null
        fun settings(context: Context): SharedPreferences = context.getSharedPreferences("tn-floating-control-v1", Context.MODE_PRIVATE)
        fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        fun isEnabled(context: Context) = settings(context).getBoolean(KEY_ENABLED, false)
        fun isHidden(context: Context) = settings(context).getBoolean(KEY_HIDDEN, false)
        fun isAppForegroundSuppressed(context: Context) = settings(context).getBoolean(KEY_APP_FOREGROUND, false)
        fun setAppForeground(context: Context, foreground: Boolean) {
            settings(context).edit().putBoolean(KEY_APP_FOREGROUND, foreground).apply()
        }
        fun isVisible() = instance?.bubble?.isAttachedToWindow == true
        fun enable(context: Context): Boolean {
            if (!hasPermission(context)) return false
            val prefs = settings(context); val oldEnabled = prefs.getBoolean(KEY_ENABLED, false); val oldHidden = prefs.getBoolean(KEY_HIDDEN, false)
            prefs.edit().putBoolean(KEY_ENABLED, true).putBoolean(KEY_HIDDEN, false).apply()
            return try { ContextCompat.startForegroundService(context, Intent(context, TnFloatingControlService::class.java)); true }
            catch (_: RuntimeException) { prefs.edit().putBoolean(KEY_ENABLED, oldEnabled).putBoolean(KEY_HIDDEN, oldHidden).apply(); false }
        }
        fun disable(context: Context) { settings(context).edit().putBoolean(KEY_ENABLED, false).apply(); context.stopService(Intent(context, TnFloatingControlService::class.java)) }
        fun reconcile(context: Context) {
            if (isEnabled(context) && !isHidden(context) && hasPermission(context)) {
                try { ContextCompat.startForegroundService(context, Intent(context, TnFloatingControlService::class.java)) } catch (_: RuntimeException) {}
            } else context.stopService(Intent(context, TnFloatingControlService::class.java))
        }
        fun setMicrophoneEnabled(context: Context, enabled: Boolean) { settings(context).edit().putBoolean(KEY_MIC_ENABLED, enabled).apply() }
    }
}
