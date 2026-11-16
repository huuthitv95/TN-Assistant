package vn.tn.assistant.actions

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import vn.tn.assistant.ui.TnIsolatedActivityPreferences
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the foreground MapDirection adapter while intercepting Android's external launch. */
class TnMapDirectionRuntimeTest {
    @Test fun mainActivityLaunchesGoogleMapsHttpsAndRejectsOtherTargets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val launches = LinkedBlockingQueue<Intent>()
        val mapUrl = "https://www.google.com/maps/dir/?api=1&destination=123+Main+Street"
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW && intent.data?.host?.endsWith("google.com") == true) {
                    launches.offer(Intent(intent))
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }

        instrumentation.addMonitor(monitor)
        try {
            TnIsolatedActivityPreferences(context).use {
                val activity = instrumentation.startActivitySync(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ) as MainActivity
                try {
                    val execute = MainActivity::class.java.getDeclaredMethod(
                        "executeDirectives",
                        JSONObject::class.java,
                        String::class.java,
                        String::class.java,
                        kotlin.jvm.functions.Function0::class.java,
                    ).apply { isAccessible = true }
                    fun dispatch(url: String) = JSONObject().put("directives", JSONArray().put(
                        JSONObject().put("type", "MapDirection").put("payload", JSONObject().put("url", url)),
                    ))

                    val accepted = execute.invoke(activity, dispatch(mapUrl), "", "", { true })
                    assertEquals(TnDirectiveExecutor.Summary(), accepted)
                    val launched = launches.poll(2, TimeUnit.SECONDS)
                    assertNotNull("MapDirection did not start Android ACTION_VIEW", launched)
                    assertEquals(Intent.ACTION_VIEW, launched!!.action)
                    assertEquals(Uri.parse(mapUrl), launched.data)

                    val rejected = listOf(
                        "http://www.google.com/maps/dir/?api=1",
                        "https://maps.google.com/maps/dir/?api=1",
                        "https://www.google.com.evil.example/maps/dir/?api=1",
                        "https://www.google.com/search?q=maps",
                    )
                    rejected.forEach { url ->
                        val summary = execute.invoke(activity, dispatch(url), "", "", { true }) as TnDirectiveExecutor.Summary
                        assertEquals("Unsafe or non-map URL was not rejected: $url", 1, summary.failed)
                        assertEquals("Unsafe or non-map URL was treated as unsupported: $url", 0, summary.unsupported)
                        assertNull("Rejected URL reached an external Android app: $url", launches.poll(100, TimeUnit.MILLISECONDS))
                    }
                    assertTrue("The intercepted Maps launch should not leave MainActivity", activity.hasWindowFocus())
                    assertFalse(launches.isNotEmpty())
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun liveVpsMapCommandRunsThroughMainActivityWithoutCallingSpeechProvider() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val avdName = shell("getprop ro.boot.qemu.avd_name").ifBlank { shell("getprop ro.kernel.qemu.avd_name") }
        assertTrue("Use a TN Android SDK emulator; found AVD '$avdName'", avdName in setOf("TNNative166", "TNParity37"))
        assertEquals("https://assistant.tnfastvn.com", BuildConfig.API_BASE_URL)
        assertTrue(context.packageName in setOf("vn.tn.assistant.preview", "vn.tn.assistant"))

        val isolation = TnIsolatedActivityPreferences(context)
        val preferences = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val store = TnSessionStore.get(context)
        val transport = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).build()
        val command = "Chỉ đường tới Đường Hoa, Quận 1"
        val expectedDestination = "Đường Hoa, Quận 1"
        val launches = LinkedBlockingQueue<Intent>()
        val requestBodies = LinkedBlockingQueue<JSONObject>()
        val textResponses = LinkedBlockingQueue<Pair<Int, JSONObject>>()
        val actionRequests = LinkedBlockingQueue<JSONObject>()
        val actionResponses = LinkedBlockingQueue<Pair<Int, JSONObject>>()
        val speechProviderRequests = AtomicInteger()
        var activity: MainActivity? = null
        var monitor: Instrumentation.ActivityMonitor? = null
        var guestCreated = false
        var cleanupAccess = ""
        try {
            store.clear()
            val deviceId = "tn-live-map-${java.util.UUID.randomUUID()}"
            val guestRequest = Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/auth/guest")
                .post(JSONObject().put("device_id", deviceId).put("device_name", "TN Assistant live map acceptance")
                    .toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val guest = transport.newCall(guestRequest).execute().use { response ->
                assertEquals("VPS guest registration failed", 200, response.code)
                JSONObject(requireNotNull(response.body).string())
            }
            guestCreated = true
            cleanupAccess = guest.optString("access_token")
            assertEquals(deviceId, guest.getJSONObject("device").getString("id"))
            assertTrue(cleanupAccess.isNotBlank())
            store.accept(guest)
            assertTrue(preferences.edit().putString("device_id", deviceId)
                .putBoolean("tn_welcome_completed_v2", true).putBoolean("wake_word_enabled", false).commit())

            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val homeField = MainActivity::class.java.getDeclaredField("homeSurface").apply { isAccessible = true }
            val readyDeadline = SystemClock.elapsedRealtime() + 30000
            while ((store.accessToken.isNullOrBlank() || homeField.get(activity) == null) &&
                SystemClock.elapsedRealtime() < readyDeadline) Thread.sleep(100)
            assertNotNull("MainActivity did not restore the temporary VPS session into Home", homeField.get(activity))
            cleanupAccess = store.accessToken ?: cleanupAccess

            val requestsClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS).addInterceptor { chain ->
                    val request = chain.request()
                    val path = request.url.encodedPath
                    if (path == "/v1/tts") speechProviderRequests.incrementAndGet()
                    val input = if (path == "/v1/assistant/text" || path == "/v1/device/actions/results") {
                        val buffer = Buffer()
                        request.body?.writeTo(buffer)
                        JSONObject(buffer.readUtf8())
                    } else null
                    val response = chain.proceed(request)
                    if (path == "/v1/assistant/text") {
                        requestBodies.offer(requireNotNull(input))
                        val contentType = response.body?.contentType()
                        val bodyText = response.body?.string().orEmpty()
                        val reply = JSONObject(bodyText)
                        textResponses.offer(response.code to JSONObject(reply.toString()))
                        if (response.code == 200) {
                            // The speech directive would call a provider. This acceptance isolates the map route.
                            val directives = JSONArray()
                            val original = reply.optJSONArray("directives") ?: JSONArray()
                            for (index in 0 until original.length()) {
                                val directive = original.optJSONObject(index) ?: continue
                                if (directive.optString("type") != "SpeechSynthesizer") directives.put(directive)
                            }
                            reply.put("directives", directives)
                        }
                        response.newBuilder().body(reply.toString().toResponseBody(contentType)).build()
                    } else {
                        if (path == "/v1/device/actions/results") {
                            actionRequests.offer(requireNotNull(input))
                            actionResponses.offer(response.code to JSONObject(response.peekBody(64L * 1024).string()))
                        }
                        response
                    }
                }.build()
            MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, requestsClient)

            monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    val uri = intent.data
                    if (intent.action == Intent.ACTION_VIEW && uri?.scheme == "https" &&
                        uri.host == "www.google.com" && uri.path.orEmpty().startsWith("/maps/")) {
                        launches.offer(Intent(intent))
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                    return null
                }
            }
            instrumentation.addMonitor(monitor)
            val sendCommand = MainActivity::class.java.getDeclaredMethod("sendCommand", String::class.java).apply { isAccessible = true }
            instrumentation.runOnMainSync { sendCommand.invoke(activity, command) }

            val textStatusAndReply = textResponses.poll(45, TimeUnit.SECONDS)
            assertNotNull("MainActivity did not receive a live assistant response from the VPS", textStatusAndReply)
            val (textStatus, reply) = requireNotNull(textStatusAndReply)
            assertEquals(200, textStatus)
            val sent = requireNotNull(requestBodies.poll(2, TimeUnit.SECONDS))
            assertEquals(command, sent.getString("text"))
            assertEquals(deviceId, sent.getString("device_id"))
            assertEquals("tn_command_router", reply.getString("source"))
            assertEquals(0, reply.getInt("provider_latency_ms"))
            val directives = reply.optJSONArray("directives")
            assertNotNull("VPS response omitted directives", directives)
            val map = (0 until requireNotNull(directives).length()).mapNotNull { directives.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "MapDirection" }
            assertNotNull("VPS response omitted the MapDirection route", map)
            val payload = requireNotNull(map).getJSONObject("payload")
            assertEquals(expectedDestination, payload.getString("query"))
            val mapUri = Uri.parse(payload.getString("url"))
            assertEquals("https", mapUri.scheme)
            assertEquals("www.google.com", mapUri.host)
            assertTrue(mapUri.path.orEmpty().startsWith("/maps/"))
            assertEquals(expectedDestination, mapUri.getQueryParameter("query"))

            val launched = launches.poll(15, TimeUnit.SECONDS)
            assertNotNull("MainActivity did not dispatch the VPS Maps URL", launched)
            assertEquals(Intent.ACTION_VIEW, requireNotNull(launched).action)
            assertEquals(mapUri, launched?.data)
            val actionStatusAndReply = actionResponses.poll(15, TimeUnit.SECONDS)
            assertNotNull("MainActivity did not submit the map action receipt", actionStatusAndReply)
            val (actionStatus, actionAck) = requireNotNull(actionStatusAndReply)
            val receipt = requireNotNull(actionRequests.poll(2, TimeUnit.SECONDS))
            assertEquals(200, actionStatus)
            assertEquals("success", receipt.getString("result"))
            assertEquals(map.optString("action_id"), receipt.getString("action_id"))
            assertTrue(actionAck.optBoolean("accepted"))
            assertEquals(0, speechProviderRequests.get())
        } finally {
            monitor?.let(instrumentation::removeMonitor)
            instrumentation.runOnMainSync { activity?.finish() }
            try {
                if (guestCreated) {
                    val access = store.accessToken ?: cleanupAccess
                    assertTrue("Temporary VPS account lost its cleanup token", access.isNotBlank())
                    val delete = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/me")
                        .header("Authorization", "Bearer $access").delete().build()
                    val deleted = transport.newCall(delete).execute().use { response ->
                        response.code == 200 && JSONObject(requireNotNull(response.body).string()).optBoolean("ok")
                    }
                    assertTrue("Temporary VPS account was not deleted", deleted)
                }
            } finally {
                store.clear()
                isolation.close()
            }
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }
}
