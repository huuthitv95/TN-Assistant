package vn.tn.assistant.actions

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
import okio.Buffer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import vn.tn.assistant.ui.TnIsolatedActivityPreferences
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TnYouTubeIntentCascadeRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText().trim() }

    private fun withVideoPackage(value: String, action: () -> Unit) {
        val prefs = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val wasPresent = prefs.contains("video_package")
        val previous = prefs.getString("video_package", null)
        try {
            assertTrue(prefs.edit().putString("video_package", value).commit())
            action()
        } finally {
            val edit = prefs.edit()
            if (wasPresent) edit.putString("video_package", previous) else edit.remove("video_package")
            assertTrue(edit.commit())
        }
    }

    @Test fun directLinkHasPriorityAndUsesConfiguredVideoPackage() = withVideoPackage("com.android.chrome") {
        val link = "https://example.com/video?id=1&from=assistant"
        val intent = TnYouTubeIntentCascade.build(context, JSONObject()
            .put("direct_link", link)
            .put("youtube_search", true)
            .put("query", "ignored search")
            .put("video_id", "dQw4w9WgXcQ"))

        assertNotNull(intent)
        assertEquals(link, intent!!.dataString)
        assertEquals("com.android.chrome", intent.`package`)
    }

    @Test fun searchEncodesVietnameseQueryAndFallsBackToYouTubeWhenSelectionIsMissing() =
        withVideoPackage("vn.tn.assistant.video.not.installed") {
            val intent = TnYouTubeIntentCascade.build(context, JSONObject()
                .put("youtube_search", true)
                .put("query", "nhạc Trịnh & bolero"))

            assertNotNull(intent)
            assertEquals("com.google.android.youtube", intent!!.`package`)
            assertEquals("www.youtube.com", intent.data?.host)
            assertEquals("/results", intent.data?.path)
            assertEquals("nhạc Trịnh & bolero", intent.data?.getQueryParameter("search_query"))
        }

    @Test fun videoIdUsesWatchUrlAndArbitraryHttpsFallsBackToSystemHandler() {
        withVideoPackage("") {
            val video = TnYouTubeIntentCascade.build(context, JSONObject().put("video_id", "dQw4w9WgXcQ"))
            assertNotNull(video)
            assertEquals("com.google.android.youtube", video!!.`package`)
            assertEquals("/watch", video.data?.path)
            assertEquals("dQw4w9WgXcQ", video.data?.getQueryParameter("v"))

            val web = TnYouTubeIntentCascade.build(context, JSONObject()
                .put("direct_link", "https://example.com/watch?v=1"))
            assertNotNull(web)
            assertNull("A non-YouTube link must reach the generic HTTPS handler after the YouTube package misses", web!!.`package`)
            assertNotNull(web.resolveActivity(context.packageManager))
        }
    }

    @Test fun malformedDirectLinkAndVideoIdFailClosed() = withVideoPackage("") {
        assertNull(TnYouTubeIntentCascade.build(context, JSONObject()
            .put("direct_link", "http://example.com/video")
            .put("youtube_search", true)
            .put("query", "must not override direct link")))
        assertNull(TnYouTubeIntentCascade.build(context, JSONObject().put("video_id", "short")))
        assertNull(TnYouTubeIntentCascade.build(context, JSONObject().put("youtube_search", true)))
    }

    @Test fun originalApiAndHtmlResolversDoNotSilentlyBecomeSearchPageFallbacks() = withVideoPackage("") {
        assertNull(TnYouTubeIntentCascade.build(context, JSONObject()
            .put("query", "configured API search")
            .put("youtube_api", JSONObject().put("url", "https://api.example.com/search"))) { null })
        assertNull(TnYouTubeIntentCascade.build(context, JSONObject()
            .put("query", "configured HTML search")
            .put("query_html", JSONObject().put("offline_type", "youtube"))) { null })
    }

    @Test fun configuredYoutubeApiPostsSearchAndAppliesOriginalFilters() {
        var observed: Request? = null
        val resolver = TnYouTubeSearchResolver { request ->
            observed = request
            youtubeSearchResponse(
                JSONObject().put("videoId", "dQw4w9WgXcQ")
                    .put("thumbnailOverlays", JSONArray().put(JSONObject()
                        .put("thumbnailOverlayTimeStatusRenderer", JSONObject().put("style", "LIVE"))))
                    .put("lengthText", JSONObject().put("simpleText", "1:00:00")),
                JSONObject().put("videoId", "M7lc1UVf-VE")
                    .put("lengthText", JSONObject().put("simpleText", "4:05")),
            ).toString()
        }
        val payload = JSONObject().put("query", "nhạc Trịnh")
            .put("youtube_api", JSONObject().put("type", "youtube")
                .put("url", "https://api.example.test/youtube/search")
                .put("requestHeader", JSONArray().put(JSONObject().put("key", "X-Provider-Mode").put("value", "preview")))
                .put("requestBody", "{\"query\":\"{replace-text-search}\"}")
                .put("filters", JSONObject().put("live_status", "not_live").put("min_video_duration_in_second", 120)))

        val resolved = resolver.resolve(payload)
        val request = requireNotNull(observed)
        val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
        assertEquals("POST", request.method)
        assertEquals("preview", request.header("X-Provider-Mode"))
        assertEquals("nhạc Trịnh", JSONObject(body).getString("query"))
        assertEquals("https://www.youtube.com/watch?v=M7lc1UVf-VE", resolved.toString())
    }

    @Test fun configuredKikiApiAndYouTubeHtmlResolveWatchLinks() {
        val kiki = TnYouTubeSearchResolver {
            JSONObject().put("status", 0).put("data", JSONObject().put("yt_video",
                JSONObject().put("id", "dQw4w9WgXcQ").put("yt_watch_url", "https://youtu.be/dQw4w9WgXcQ")))
                .toString()
        }.resolve(JSONObject().put("query", "music")
            .put("youtube_api", JSONObject().put("type", "kiki").put("url", "https://api.example.test/search")))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", kiki.toString())

        val initialData = youtubeSearchResponse(JSONObject().put("videoId", "M7lc1UVf-VE")
            .put("lengthText", JSONObject().put("simpleText", "3:45")))
        val html = "<script>var ytInitialData = $initialData;</script>"
        val htmlResult = TnYouTubeSearchResolver { html }
            .resolve(JSONObject().put("query", "TN video")
                .put("query_html", JSONObject().put("offline_type", "youtube")))
        assertEquals("https://www.youtube.com/watch?v=M7lc1UVf-VE", htmlResult.toString())
    }

    @Test fun youtubeApiBootstrapsTheInnertubeKeyAndClientVersionWhenUrlIsOmitted() {
        val requests = mutableListOf<Request>()
        val resolver = TnYouTubeSearchResolver { request ->
            requests += request
            if (requests.size == 1) {
                "<script>{\"INNERTUBE_API_KEY\":\"fixture_key-1\",\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"23.33.1\"}</script>"
            } else youtubeSearchResponse(JSONObject().put("videoId", "dQw4w9WgXcQ")
                .put("lengthText", JSONObject().put("simpleText", "4:00"))).toString()
        }
        val result = resolver.resolve(JSONObject().put("query", "TN video")
            .put("youtube_api", JSONObject().put("type", "youtube")))

        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result.toString())
        assertEquals(2, requests.size)
        assertEquals("GET", requests[0].method)
        assertEquals("POST", requests[1].method)
        assertEquals("fixture_key-1", requests[1].url.queryParameter("key"))
        val body = Buffer().also { requireNotNull(requests[1].body).writeTo(it) }.readUtf8()
        assertEquals("23.33.1", JSONObject(body).getJSONObject("context")
            .getJSONObject("client").getString("clientVersion"))
    }

    @Test fun configuredGoogleHtmlUsesCssSelectorAndRejectsUnsafeResults() {
        var searchUrl = ""
        val resolver = TnYouTubeSearchResolver { request ->
            searchUrl = request.url.toString()
            "<div><a class=\"result\" href=\"/url?q=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ\">video</a></div>"
        }
        val config = JSONObject().put("search_url", "https://www.google.com/search?q=")
            .put("site_prefix", "site:youtube.com").put("google_url", "https://www.google.com")
            .put("selector", "a.result")
        val result = resolver.resolve(JSONObject().put("query", "nhạc Trịnh").put("query_html", config))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result.toString())
        assertTrue(searchUrl.contains("site%3Ayoutube.com+nh%E1%BA%A1c+Tr%E1%BB%8Bnh"))

        var contacted = false
        val unsafeResolver = TnYouTubeSearchResolver { contacted = true; "{}" }
        val unsafe = JSONObject().put("query", "search")
            .put("youtube_api", JSONObject().put("url", "http://127.0.0.1/private")
                .put("requestHeader", JSONArray().put(JSONObject().put("key", "Authorization").put("value", "secret"))))
        assertNull(unsafeResolver.resolve(unsafe))
        assertFalse(contacted)
    }

    private fun youtubeSearchResponse(vararg videos: JSONObject): JSONObject {
        val entries = JSONArray()
        videos.forEach { entries.put(JSONObject().put("compactVideoRenderer", it)) }
        return JSONObject().put("contents", JSONObject().put("sectionListRenderer", JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("itemSectionRenderer", JSONObject().put("contents", entries))))))
    }

    @Test fun resolvedYouTubeSearchIntentLaunchesTheInstalledApp() = withVideoPackage("com.google.android.youtube") {
        var receiptResult = ""
        var receiptStartedIntent = false
        val payload = JSONObject()
            .put("youtube_search", true)
            .put("query", "TN Assistant kiểm tra mở ứng dụng")
        val executor = TnDirectiveExecutor(
            active = { true },
            speech = { true },
            map = { true },
            app = { true },
            volume = object : TnDirectiveExecutor.Volume {
                override fun maximum() = 1
                override fun current() = 0
                override fun set(value: Int) = Unit
            },
            receipt = { _, result, details ->
                receiptResult = result
                receiptStartedIntent = details.optBoolean("intent_started")
            },
            youtube = { command ->
                val intent = TnYouTubeIntentCascade.build(context, command)
                if (intent == null || intent.resolveActivity(context.packageManager)?.packageName != "com.google.android.youtube") {
                    false
                } else {
                    context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }
            },
        )
        val summary = executor.execute(JSONObject().put("directives", JSONArray().put(
            JSONObject().put("type", "YouTube").put("action_id", "yt-dispatch-test").put("payload", payload),
        )))
        assertEquals(0, summary.failed)
        assertEquals(0, summary.unsupported)
        assertEquals("success", receiptResult)
        assertTrue(receiptStartedIntent)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000
        var resumed = ""
        while (SystemClock.uptimeMillis() < deadline) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities")
            val snapshot = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            resumed = snapshot.lineSequence()
                .filter { it.contains("mResumedActivity") || it.contains("topResumedActivity") }
                .joinToString(" ")
            if (resumed.contains("com.google.android.youtube")) break
            SystemClock.sleep(250)
        }
        assertTrue("Android did not foreground the resolved YouTube activity: $resumed",
            resumed.contains("com.google.android.youtube"))
    }

    @Test fun liveVpsCommandRunsThroughMainActivityAndOpensItsUrl() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("TNNative166", shell("getprop ro.kernel.qemu.avd_name"))
        assertEquals("https://assistant.tnfastvn.com", BuildConfig.API_BASE_URL)
        assertTrue("Unexpected app package: ${context.packageName}", context.packageName in setOf("vn.tn.assistant.preview", "vn.tn.assistant"))
        assertNotNull(context.packageManager.getLaunchIntentForPackage("com.android.chrome"))

        val isolated = TnIsolatedActivityPreferences(context)
        val preferences = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val store = TnSessionStore.get(context)
        val transport = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).build()
        val url = "https://example.com/watch?v=1"
        val command = "Mở $url"
        val launches = LinkedBlockingQueue<Intent>()
        val textRequestBodies = LinkedBlockingQueue<JSONObject>()
        val textResponses = LinkedBlockingQueue<Pair<Int, JSONObject>>()
        val actionRequests = LinkedBlockingQueue<JSONObject>()
        val actionResponses = LinkedBlockingQueue<Pair<Int, JSONObject>>()
        var activity: MainActivity? = null
        var monitor: Instrumentation.ActivityMonitor? = null
        var guestCreated = false
        var cleanupAccess = ""
        try {
            store.clear()
            val deviceId = "tn-live-youtube-${UUID.randomUUID()}"
            val guestBody = JSONObject().put("device_id", deviceId).put("device_name", "TN Assistant live URL acceptance")
            val guestRequest = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/v1/auth/guest")
                .post(guestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
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
                .putBoolean("tn_welcome_completed_v2", true).putBoolean("wake_word_enabled", false)
                .putString("video_package", "").commit())

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
                    val body = if (path == "/v1/assistant/text" || path == "/v1/device/actions/results") {
                        val buffer = Buffer()
                        request.body?.writeTo(buffer)
                        JSONObject(buffer.readUtf8())
                    } else null
                    val response = chain.proceed(request)
                    if (path == "/v1/assistant/text") {
                        textRequestBodies.offer(requireNotNull(body))
                        textResponses.offer(response.code to JSONObject(response.peekBody(2L * 1024 * 1024).string()))
                    } else if (path == "/v1/device/actions/results") {
                        actionRequests.offer(requireNotNull(body))
                        actionResponses.offer(response.code to JSONObject(response.peekBody(64L * 1024).string()))
                    }
                    response
                }.build()
            MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, requestsClient)

            monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action == Intent.ACTION_VIEW && intent.data == Uri.parse(url)) launches.offer(Intent(intent))
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
            val sent = requireNotNull(textRequestBodies.poll(2, TimeUnit.SECONDS))
            assertEquals(command, sent.getString("text"))
            assertEquals(deviceId, sent.getString("device_id"))
            val directives = reply.optJSONArray("directives")
            assertNotNull("VPS response omitted directives", directives)
            val youtube = (0 until requireNotNull(directives).length()).mapNotNull { directives.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "YouTube" }
            assertNotNull("VPS response omitted the YouTube route", youtube)
            val payload = requireNotNull(youtube).getJSONObject("payload")
            assertEquals(url, payload.getString("direct_link"))

            val launched = launches.poll(15, TimeUnit.SECONDS)
            assertNotNull("The live MainActivity route did not start ACTION_VIEW for the returned URL", launched)
            val actualLaunch = requireNotNull(launched)
            assertEquals(url, actualLaunch.dataString)
            assertEquals(Intent.ACTION_VIEW, actualLaunch.action)
            assertEquals("com.android.chrome", actualLaunch.resolveActivity(context.packageManager)?.packageName)

            val actionStatusAndReply = actionResponses.poll(15, TimeUnit.SECONDS)
            assertNotNull("MainActivity did not submit the device action receipt", actionStatusAndReply)
            val (actionStatus, actionAck) = requireNotNull(actionStatusAndReply)
            val receipt = requireNotNull(actionRequests.poll(2, TimeUnit.SECONDS))
            assertEquals(200, actionStatus)
            assertEquals("success", receipt.getString("result"))
            assertEquals(youtube.optString("action_id"), receipt.getString("action_id"))
            assertTrue(actionAck.optBoolean("accepted"))
            assertEquals(receipt.getString("request_id"), actionAck.getString("request_id"))
            assertEquals(receipt.getString("action_id"), actionAck.getString("action_id"))

            val foregroundDeadline = SystemClock.uptimeMillis() + 10000
            var resumed = ""
            while (SystemClock.uptimeMillis() < foregroundDeadline) {
                resumed = shell("dumpsys activity activities").lineSequence()
                    .filter { it.contains("mResumedActivity") || it.contains("topResumedActivity") }
                    .joinToString(" ")
                if (resumed.contains("com.android.chrome")) break
                SystemClock.sleep(250)
            }
            assertTrue("The requested browser did not reach foreground: $resumed", resumed.contains("com.android.chrome"))
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
                isolated.close()
            }
        }
    }
}
