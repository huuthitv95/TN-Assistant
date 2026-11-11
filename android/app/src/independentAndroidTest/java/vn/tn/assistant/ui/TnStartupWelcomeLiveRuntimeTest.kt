package vn.tn.assistant.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** One real TN greeting journey on an otherwise empty Preview profile. */
class TnStartupWelcomeLiveRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val transport = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun request(method: String, path: String, token: String = "", payload: JSONObject? = null): Pair<Int, JSONObject> {
        val builder = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + path)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST", "PUT" -> builder.method(method, requireNotNull(payload).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType()))
            else -> error("Unsupported test request")
        }
        return transport.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            response.code to runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        }
    }

    private fun launcherIntent() = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun startHome(): MainActivity = instrumentation.startActivitySync(launcherIntent()) as MainActivity

    private fun finish(activity: MainActivity?) {
        instrumentation.runOnMainSync { activity?.finish() }
        instrumentation.waitForIdleSync()
    }

    private fun welcomeAttempt(activity: MainActivity): Any? =
        MainActivity::class.java.getDeclaredField("welcomeAttempt").apply { isAccessible = true }.get(activity)

    private fun awaitWelcomeAttempt(activity: MainActivity) {
        val deadline = SystemClock.elapsedRealtime() + 30000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (welcomeAttempt(activity) != null) return
            Thread.sleep(100)
        }
        assertNotNull("Launcher did not reach the signed-in welcome gate", welcomeAttempt(activity))
    }

    private fun awaitWelcomeWorker(activity: MainActivity) {
        val executor = MainActivity::class.java.getDeclaredField("welcomeWorker")
            .apply { isAccessible = true }.get(activity) as ExecutorService
        executor.submit { }.get(30, TimeUnit.SECONDS)
    }

    private fun playedKey(userId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
        return "tn_welcome_last_played_day_" + digest.joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    @Test fun serverSwitchAndCompletedPlaybackControlDailyWelcome() {
        assertEquals("Run this live acceptance only on Preview", "vn.tn.assistant.preview", context.packageName)
        assertEquals("https://assistant.tnfastvn.com", BuildConfig.API_BASE_URL)
        val store = TnSessionStore.get(context)
        assertFalse("Preview profile already owns a TN session", store.hasSession())
        val isolated = TnIsolatedActivityPreferences(context)
        val preferences = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var activity: MainActivity? = null
        var guestCreated = false
        var cleanupAccess = ""
        try {
            val deviceId = "tn-welcome190-" + UUID.randomUUID()
            val (guestStatus, guest) = request("POST", "/v1/auth/guest", payload = JSONObject()
                .put("device_id", deviceId).put("device_name", "TN startup welcome acceptance"))
            assertEquals("Disposable TN guest creation failed", 200, guestStatus)
            guestCreated = true
            cleanupAccess = guest.getString("access_token")
            val userId = guest.getJSONObject("user").getString("id")
            val mark = playedKey(userId)
            store.accept(guest)
            assertTrue(preferences.edit().putString("device_id", deviceId)
                .putBoolean("tn_welcome_completed_v2", true)
                .putBoolean("tn_setup_voice_muted", true)
                .putBoolean("wake_word_enabled", false)
                .putBoolean("welcome_enabled", false).commit())

            val (offStatus, off) = request("PUT", "/v1/settings", cleanupAccess,
                JSONObject().put("welcome_enabled", false))
            assertEquals(200, offStatus)
            assertFalse(off.optBoolean("welcome_enabled", true))
            activity = startHome()
            awaitWelcomeAttempt(requireNotNull(activity))
            awaitWelcomeWorker(requireNotNull(activity))
            assertFalse("Disabled greeting wrote a daily completion mark", preferences.contains(mark))
            finish(activity)
            activity = null

            val (onStatus, on) = request("PUT", "/v1/settings", store.accessToken ?: cleanupAccess,
                JSONObject().put("welcome_enabled", true))
            assertEquals(200, onStatus)
            assertTrue(on.optBoolean("welcome_enabled", false))
            activity = startHome()
            awaitWelcomeAttempt(requireNotNull(activity))
            val playbackDeadline = SystemClock.elapsedRealtime() + 90000
            while (SystemClock.elapsedRealtime() < playbackDeadline && preferences.getString(mark, null) != day) {
                Thread.sleep(100)
            }
            assertEquals("Greeting was not marked after real audio playback", day, preferences.getString(mark, null))
            awaitWelcomeWorker(requireNotNull(activity))
            finish(activity)
            activity = null

            activity = startHome()
            awaitWelcomeAttempt(requireNotNull(activity))
            awaitWelcomeWorker(requireNotNull(activity))
            assertEquals("Same-day launcher changed the completed greeting mark", day, preferences.getString(mark, null))
        } finally {
            finish(activity)
            try {
                if (guestCreated) {
                    val access = store.accessToken ?: cleanupAccess
                    assertTrue("Disposable guest lost its cleanup token", access.isNotBlank())
                    val (deleteStatus, deleted) = request("DELETE", "/v1/me", access)
                    assertEquals("Disposable TN guest was not deleted", 200, deleteStatus)
                    assertTrue(deleted.optBoolean("ok"))
                }
            } finally {
                store.clear()
                isolated.close()
            }
        }
    }
}
