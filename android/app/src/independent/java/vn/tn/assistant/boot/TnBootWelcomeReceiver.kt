package vn.tn.assistant.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import vn.tn.assistant.MainActivity
import vn.tn.assistant.R
import vn.tn.assistant.auth.TnSessionStore

/** Offers the daily welcome after boot through an explicit user tap.
 * Android does not allow this target to start media playback from BOOT_COMPLETED.
 */
internal object TnBootWelcomePolicy {
    fun shouldNotify(
        explicitlyEnabled: Boolean,
        welcomeEnabled: Boolean,
        hasSession: Boolean,
        notificationsEnabled: Boolean,
        alreadyPlayedToday: Boolean,
        bootKey: String,
        previousBootKey: String?,
    ): Boolean = explicitlyEnabled && welcomeEnabled && hasSession && notificationsEnabled && !alreadyPlayedToday &&
        bootKey.isNotBlank() && bootKey != previousBootKey
}

class TnBootWelcomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val preferences = app.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val bootKey = bootKey(app)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(LOCK) {
            if (!TnBootWelcomePolicy.shouldNotify(
                    explicitlyEnabled = preferences.getBoolean(KEY_ENABLED, false),
                    welcomeEnabled = preferences.getBoolean("welcome_enabled", true),
                    hasSession = runCatching { TnSessionStore.get(app).hasSession() }.getOrDefault(false),
                    notificationsEnabled = NotificationManagerCompat.from(app).areNotificationsEnabled(),
                    alreadyPlayedToday = preferences.getString(KEY_LAST_PLAYED_DAY, null) == today,
                    bootKey = bootKey,
                    previousBootKey = preferences.getString(KEY_LAST_PROMPTED, null),
                )) return

            val manager = NotificationManagerCompat.from(app)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(android.app.NotificationChannel(
                    CHANNEL_ID,
                    app.getString(R.string.tn_boot_welcome_channel),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply { description = app.getString(R.string.tn_boot_welcome_channel_description) })
            }
            val open = Intent(app, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_BOOT_WELCOME, true)
            }
            val pending = android.app.PendingIntent.getActivity(
                app, REQUEST_CODE, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(app.getString(R.string.tn_boot_welcome_title))
                .setContentText(app.getString(R.string.tn_boot_welcome_message))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            try {
                manager.notify(NOTIFICATION_ID, notification)
                if (!preferences.edit().putString(KEY_LAST_PROMPTED, bootKey).commit()) {
                    manager.cancel(NOTIFICATION_ID)
                }
            } catch (_: SecurityException) {
                // Permission may be revoked after the notification state check.
            }
        }
    }

    private fun bootKey(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val count = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
                .getOrDefault(-1)
            if (count >= 0) return "count:$count"
        }
        val approximateBoot = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000
        return "time:$approximateBoot"
    }

    companion object {
        const val EXTRA_BOOT_WELCOME = "tn_boot_welcome"
        const val CHANNEL_ID = "tn_boot_welcome"
        const val NOTIFICATION_ID = 4701
        const val KEY_ENABLED = "welcome_after_boot"
        const val KEY_LAST_PROMPTED = "welcome_after_boot_last_prompted"
        const val KEY_LAST_PLAYED_DAY = "welcome_last_played_day_device"
        private const val REQUEST_CODE = 4701
        private val LOCK = Any()
    }
}
