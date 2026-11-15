package vn.tn.assistant.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.boot.TnBootWelcomeReceiver
import vn.tn.assistant.auth.TnSessionStore

class TnBootWelcomeReceiverTest {
    @Test fun postsOnlyAnOptedInOncePerBootTapToSpeakReminder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "vn.tn.assistant.preview")
        assumeTrue(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        val isolated = TnIsolatedActivityPreferences(context)
        val preferences = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val sessions = TnSessionStore.get(context)
        val manager = NotificationManagerCompat.from(context)
        try {
            sessions.clear()
            preferences.edit().putBoolean(TnBootWelcomeReceiver.KEY_ENABLED, true)
                .putBoolean("welcome_enabled", true)
                .remove(TnBootWelcomeReceiver.KEY_LAST_PLAYED_DAY)
                .remove(TnBootWelcomeReceiver.KEY_LAST_PROMPTED).commit()
            assumeTrue("Notification permission is needed to verify the boot prompt", manager.areNotificationsEnabled())
            sessions.accept(JSONObject().put("access_token", "boot-test-access")
                .put("refresh_token", "boot-test-refresh"))

            val receiver = TnBootWelcomeReceiver()
            receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            val notification = manager.activeNotifications.singleOrNull {
                it.id == TnBootWelcomeReceiver.NOTIFICATION_ID
            }?.notification
            assertNotNull("The boot opt-in did not create a notification", notification)
            assertNotNull("The user must tap to hear the greeting", notification!!.contentIntent)
            assertTrue(preferences.getString(TnBootWelcomeReceiver.KEY_LAST_PROMPTED, null).orEmpty().isNotBlank())

            receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertEquals(1, manager.activeNotifications.count { it.id == TnBootWelcomeReceiver.NOTIFICATION_ID })

            preferences.edit().putString(TnBootWelcomeReceiver.KEY_LAST_PLAYED_DAY, java.text.SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())).commit()
            manager.cancel(TnBootWelcomeReceiver.NOTIFICATION_ID)
            preferences.edit().remove(TnBootWelcomeReceiver.KEY_LAST_PROMPTED).commit()
            receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertFalse("A completed daily greeting must suppress the boot reminder",
                manager.activeNotifications.any { it.id == TnBootWelcomeReceiver.NOTIFICATION_ID })

            preferences.edit().putBoolean(TnBootWelcomeReceiver.KEY_ENABLED, false).commit()
            manager.cancel(TnBootWelcomeReceiver.NOTIFICATION_ID)
            preferences.edit().remove(TnBootWelcomeReceiver.KEY_LAST_PROMPTED).commit()
            receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertFalse(manager.activeNotifications.any { it.id == TnBootWelcomeReceiver.NOTIFICATION_ID })
        } finally {
            manager.cancel(TnBootWelcomeReceiver.NOTIFICATION_ID)
            context.getSystemService(NotificationManager::class.java)
                .deleteNotificationChannel(TnBootWelcomeReceiver.CHANNEL_ID)
            sessions.clear()
            isolated.close()
        }
    }
}
