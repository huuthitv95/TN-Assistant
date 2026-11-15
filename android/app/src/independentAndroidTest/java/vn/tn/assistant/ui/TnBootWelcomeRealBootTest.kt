package vn.tn.assistant.ui

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import vn.tn.assistant.boot.TnBootWelcomeReceiver

/** Opt-in two-phase test, orchestrated by scripts/validate_boot_welcome.py around a real AVD reboot. */
class TnBootWelcomeRealBootTest {
    @Test fun preflightRequiresNotificationPermissionAndNoExistingBootChannel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName == "vn.tn.assistant.preview")
        assertNotificationsEnabled(context)
        assertTrue("Use a clean AVD notification channel so cleanup preserves prior state",
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(TnBootWelcomeReceiver.CHANNEL_ID) == null)
    }

    @Test fun stageOneTimeSessionAndBootWelcomeOptInForHostReboot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName == "vn.tn.assistant.preview")
        assertNotificationsEnabled(context)

        val preferences = context.getSharedPreferences("tn-session-v1", 0)
        val sessions = TnSessionStore.get(context)
        sessions.clear()
        preferences.edit()
            .putBoolean(TnBootWelcomeReceiver.KEY_ENABLED, true)
            .putBoolean("welcome_enabled", true)
            .remove(TnBootWelcomeReceiver.KEY_LAST_PLAYED_DAY)
            .remove(TnBootWelcomeReceiver.KEY_LAST_PROMPTED)
            .commit()
        assertTrue("Could not stage the isolated boot opt-in", preferences.getBoolean(TnBootWelcomeReceiver.KEY_ENABLED, false))
        sessions.accept(JSONObject().put("access_token", "boot-runtime-access")
            .put("refresh_token", "boot-runtime-refresh"))
        assertTrue(sessions.hasSession())
    }

    @Test fun actualBootPromptOpensMainActivityWithoutStartingSpeech() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageName == "vn.tn.assistant.preview")
        assertNotificationsEnabled(context)

        val manager = NotificationManagerCompat.from(context)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var notification: android.app.Notification? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            notification = manager.activeNotifications.singleOrNull {
                it.id == TnBootWelcomeReceiver.NOTIFICATION_ID
            }?.notification
            if (notification != null) break
            SystemClock.sleep(250)
        }
        assertNotNull("A real BOOT_COMPLETED did not post the opted-in welcome reminder", notification)
        val contentIntent = notification!!.contentIntent
        assertNotNull("The reboot reminder must open TN after a user tap", contentIntent)

        // Clear the synthetic session before opening MainActivity: the test verifies the real
        // notification route without making authenticated requests or starting provider speech.
        TnSessionStore.get(context).clear()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var activity: MainActivity? = null
        try {
            contentIntent!!.send()
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
            assertNotNull("Tapping the boot reminder did not open MainActivity", activity)
            assertTrue("The boot launch extra must reach MainActivity",
                activity!!.intent.getBooleanExtra(TnBootWelcomeReceiver.EXTRA_BOOT_WELCOME, false))
        } finally {
            activity?.finish()
            instrumentation.removeMonitor(monitor)
            context.getSystemService(NotificationManager::class.java)
                .cancel(TnBootWelcomeReceiver.NOTIFICATION_ID)
        }
    }

    private fun assertNotificationsEnabled(context: android.content.Context) {
        assertTrue("Notification permission is required for boot acceptance", Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        assertTrue("Notifications are disabled for Preview", NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    @Test fun cleanupSyntheticBootState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TnSessionStore.get(context).clear()
        NotificationManagerCompat.from(context).cancel(TnBootWelcomeReceiver.NOTIFICATION_ID)
        context.getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(TnBootWelcomeReceiver.CHANNEL_ID)
    }
}
