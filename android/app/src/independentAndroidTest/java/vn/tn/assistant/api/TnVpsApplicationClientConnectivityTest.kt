package vn.tn.assistant.api

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

/** Exercises the production OkHttp path used by MainActivity against the configured VPS. */
class TnVpsApplicationClientConnectivityTest {
    @Test
    fun mainActivityApiClientReadsLiveVpsHealth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("https://assistant.tnfastvn.com", BuildConfig.API_BASE_URL)

        val preferences = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val saved = preferences.all.toMap()
        var activity: MainActivity? = null
        try {
            // Avoid refreshing or changing any previously saved user session during this read-only check.
            check(preferences.edit().clear().commit())
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ) as MainActivity

            val api = MainActivity::class.java.getDeclaredMethod(
                "api",
                String::class.java,
                JSONObject::class.java,
                String::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val response = api.invoke(activity, "/v1/health", null, null, "GET") as JSONObject
            assertEquals("The app's OkHttp client must read the live VPS status", "ok", response.optString("status"))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            val editor = preferences.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("Unsupported TN session preference type: ${value?.javaClass?.name ?: "null"}")
                }
            }
            check(editor.commit())
        }
    }
}

/** Client context and account boundaries, not acceptance of cloud inference. */
class TnAssistantConversationRuntimeTest {
    @Test fun visibleAppBuildsModeFreeRequestsWithContinuousContextThroughItsActualHttpClient() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        vn.tn.assistant.ui.TnIsolatedActivityPreferences(context).use {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val requests = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
            try {
                val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    check(request.url.host == "assistant.tnfastvn.com")
                    check(request.url.encodedPath == "/v1/assistant/text")
                    val buffer = okio.Buffer()
                    requireNotNull(request.body).writeTo(buffer)
                    val data = JSONObject(buffer.readUtf8())
                    requests.put(data)
                    okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                        .body(JSONObject().put("request_id", data.getString("request_id")).put("directives", org.json.JSONArray())
                            .toString().toResponseBody("application/json".toMediaType())).build()
                }.build()
                MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, client)
                val store = vn.tn.assistant.auth.TnSessionStore.get(context)
                store.accept(JSONObject().put("access_token", "TEST-http-context").put("refresh_token", "TEST-http-context-refresh"))
                val send = MainActivity::class.java.getDeclaredMethod("sendCommand", String::class.java).apply { isAccessible = true }
                instrumentation.runOnMainSync { send.invoke(activity, "Giải thích bầu trời") }
                val first = requests.poll(5, java.util.concurrent.TimeUnit.SECONDS)
                assertNotNull("Visible app did not construct its assistant request", first)
                instrumentation.runOnMainSync { send.invoke(activity, "Giải thích thêm") }
                val followup = requests.poll(5, java.util.concurrent.TimeUnit.SECONDS)
                assertNotNull(followup)
                assertFalse(first!!.has("mode")); assertFalse(followup!!.has("mode"))
                assertEquals(first.getString("conversation_id"), followup.getString("conversation_id"))
                assertNotEquals(first.getString("request_id"), followup.getString("request_id"))
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    @Test fun successiveForegroundAndWakeRequestsKeepOneContextButNeverForceMode() {
        val conversation = TnAssistantConversation({ 1L }, { "test-conversation" })
        val first = conversation.request("Giải thích bầu trời", "request-one", "device-one", 1) { true }
        val followup = conversation.request("Giải thích thêm", "request-two", "device-one", 1) { true }
        assertEquals(first.getString("conversation_id"), followup.getString("conversation_id"))
        assertEquals("request-one", first.getString("request_id"))
        assertEquals("request-two", followup.getString("request_id"))
        assertEquals("Giải thích thêm", followup.getString("text"))
        assertFalse(first.has("mode")); assertFalse(followup.has("mode"))
        assertFalse(first.has("role")); assertFalse(first.has("premium"))
    }

    @Test fun accountOrDeviceChangeRetiresContextAndStaleWorkCannotReplaceTheNewOwner() {
        var next = 0
        val conversation = TnAssistantConversation({ 1L }, { "test-${++next}" })
        val old = conversation.request("old", "one", "first-device", 1) { true }
        val current = conversation.request("new", "two", "first-device", 2) { true }
        assertNotEquals(old.getString("conversation_id"), current.getString("conversation_id"))
        try { conversation.request("stale", "three", "first-device", 1) { false }; fail("Retired account created a context") }
        catch (_: java.io.IOException) { }
        conversation.retire(1)
        assertEquals(current.getString("conversation_id"), conversation.request("followup", "four", "first-device", 2) { true }.getString("conversation_id"))
        assertNotEquals(current.getString("conversation_id"), conversation.request("other device", "five", "second-device", 2) { true }.getString("conversation_id"))
    }

    @Test fun idleExpiryAndExplicitLogoutCreateNewContextWithoutKeepingTranscript() {
        var clock = 0L
        var next = 0
        val conversation = TnAssistantConversation({ clock }, { "test-${++next}" })
        fun request() = conversation.request("hello", "request-${++next}", "device", 1) { true }.getString("conversation_id")
        val first = request()
        clock = 30 * 60 * 1000L - 1
        assertEquals(first, request())
        clock += 30 * 60 * 1000L
        val expired = request()
        assertNotEquals(first, expired)
        conversation.retire(1)
        assertNotEquals(expired, request())
    }

    @Test fun tokenRotationPreservesConversationWhileRealSessionReplacementDoesNot() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        vn.tn.assistant.ui.TnIsolatedActivityPreferences(context).use {
            val store = vn.tn.assistant.auth.TnSessionStore.get(context)
            fun session(token: String) = JSONObject().put("access_token", token).put("refresh_token", "$token-refresh")
            store.accept(session("TEST-context-original"))
            var next = 0
            val conversation = TnAssistantConversation({ 1L }, { "test-${++next}" })
            fun request(): JSONObject {
                val owner = store.actionGeneration()
                return conversation.request("hello", "request-${++next}", "device", owner) { store.ownsActions(owner) }
            }
            val first = request()
            store.refreshAfterUnauthorized("TEST-context-original") { session("TEST-context-rotated") }
            assertEquals(first.getString("conversation_id"), request().getString("conversation_id"))
            store.accept(session("TEST-context-other-account"))
            assertNotEquals(first.getString("conversation_id"), request().getString("conversation_id"))
        }
    }

    @Test fun speechClientPreservesFailureCodeWithoutExposingRawProviderTextOrMisreporting403AsLogout() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        vn.tn.assistant.ui.TnIsolatedActivityPreferences(context).use {
            val store = vn.tn.assistant.auth.TnSessionStore.get(context)
            store.accept(JSONObject().put("access_token", "TEST-errors").put("refresh_token", "TEST-errors-refresh"))
            val owner = store.actionGeneration()
            for ((status, code) in listOf(503 to "AI_NOT_CONFIGURED", 502 to "AI_CREDENTIAL_REJECTED",
                429 to "AI_PROJECT_QUOTA", 504 to "AI_TIMEOUT", 403 to "FEATURE_NOT_AVAILABLE", 503 to "AI_FREE_TIER_UNVERIFIED")) {
                val client = TnVoiceBackendClient(context)
                val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("test")
                        .body(JSONObject().put("error", JSONObject().put("code", code).put("message", "PRIVATE_PROVIDER_TEXT"))
                            .toString().toResponseBody("application/json".toMediaType())).build()
                }.build()
                TnVoiceBackendClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(client, transport)
                try { client.json("/v1/assistant/text", JSONObject().put("text", "test")); fail("Expected structured failure") }
                catch (failure: TnServiceFailure) {
                    assertEquals(status, failure.statusCode)
                    assertEquals(code, failure.errorCode)
                    assertFalse(failure.message.orEmpty().contains("PRIVATE_PROVIDER_TEXT"))
                    if (status == 403) assertFalse(failure.message.orEmpty().contains("đăng nhập lại"))
                    assertTrue("Provider denial must not discard a TN login", store.ownsActions(owner))
                } finally { client.cancel() }
            }
        }
    }
}

/** Real dialog/window and production request construction, with outbound HTTP intercepted. */
class TnActionConfirmationRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun proposal(input: JSONObject, ttl: Long = 120000) = JSONObject()
        .put("request_id", input.getString("request_id")).put("conversation_id", input.getString("conversation_id"))
        .put("confirmation_required", true).put("confirmation_token", "TEST-consent-token")
        .put("confirmation", JSONObject().put("type", "PhoneCall").put("summary", "Gọi số 0900000000?").put("expires_in_ms", ttl))
        .put("directives", org.json.JSONArray())

    @Test fun proposalsMustBeCurrentAndCanOnlyBeConsumedOnce() {
        val conversation = TnAssistantConversation({ 0 }, { "TEST-context" })
        val manager = vn.tn.assistant.actions.TnActionConfirmation(conversation) { 0 }
        val request = conversation.request("call", "TEST-request", "TEST-device", 1) { true }
        val input = proposal(request)
        assertFalse(manager.offer(JSONObject(input.toString()).put("replayed", true), 1) { true })
        assertFalse(manager.offer(input, 1) { false })
        assertTrue(manager.offer(input, 1) { true })
        assertNull(manager.current(2))
        val pending = requireNotNull(manager.current(1))
        assertEquals("Gọi số 0900000000?", pending.summary)
        val confirmed = requireNotNull(manager.consume(pending, "TEST-device") { true })
        assertEquals("tools", confirmed.getString("mode"))
        assertEquals("TEST-consent-token", confirmed.getString("confirmation_token"))
        assertEquals(request.getString("conversation_id"), confirmed.getString("conversation_id"))
        assertNotEquals(request.getString("request_id"), confirmed.getString("request_id"))
        assertNull(manager.consume(pending, "TEST-device") { true })
    }

    @Test fun expiryNewTurnAndWrongOwnerNeverRedeemAnOldProposal() {
        var clock = 0L
        val conversation = TnAssistantConversation({ clock }, { "TEST-context-$clock" })
        val manager = vn.tn.assistant.actions.TnActionConfirmation(conversation) { clock }
        val first = conversation.request("call", "first", "device", 1) { true }
        assertTrue(manager.offer(proposal(first, 100), 1) { true })
        val pending = requireNotNull(manager.current(1))
        clock = 100
        assertNull(manager.consume(pending, "device") { true })
        val second = conversation.request("call", "second", "device", 1) { true }
        assertTrue(manager.offer(proposal(second), 1) { true })
        val stale = requireNotNull(manager.current(1))
        conversation.request("new command", "third", "device", 1) { true }
        assertNull(manager.consume(stale, "device") { true })
        assertFalse(manager.offer(proposal(second), 1) { true })
        val other = conversation.request("other login", "fourth", "device", 2) { true }
        assertTrue(manager.offer(proposal(other), 2) { true })
        manager.dismiss(stale)
        assertNotNull(manager.current(2))
    }

    @Test fun malformedPreviewIsRejectedAndSensitiveActionsCannotRunAlongsideConsentSpeech() {
        val conversation = TnAssistantConversation({ 0 }, { "context" })
        val manager = vn.tn.assistant.actions.TnActionConfirmation(conversation) { 0 }
        val request = conversation.request("call", "request", "device", 1) { true }
        val input = proposal(request)
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("confirmation_token", "invalid\ntoken") },
            { it.getJSONObject("confirmation").put("type", "Unknown") },
            { it.getJSONObject("confirmation").put("expires_in_ms", -1) },
            { it.getJSONObject("confirmation").put("expires_in_ms", 1.5) },
            { it.getJSONObject("confirmation").put("summary", "") })) {
            val invalid = JSONObject(input.toString()); change(invalid)
            assertFalse(manager.offer(invalid, 1) { true })
        }
        input.getJSONArray("directives").put(JSONObject().put("type", "PhoneCall"))
            .put(JSONObject().put("type", "PlayerVolume"))
            .put(JSONObject().put("type", "SpeechSynthesizer").put("action_id", "test-speech"))
        val safe = vn.tn.assistant.actions.TnActionConfirmation.speechOnly(input).getJSONArray("directives")
        assertEquals(1, safe.length()); assertEquals("test-speech", safe.getJSONObject(0).getString("action_id"))
    }

    private inner class Harness(private val ttl: Long = 120000, private val externalClock: Boolean = false,
                                private val basicClock: Boolean = false) : AutoCloseable {
        val isolation = vn.tn.assistant.ui.TnIsolatedActivityPreferences(context)
        val activity = try {
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        } catch (error: Throwable) {
            isolation.close()
            throw error
        }
        val requests = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        val receipts = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        private val client: okhttp3.OkHttpClient
        init {
            client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                check(request.url.encodedPath in listOf("/v1/assistant/text", "/v1/device/actions/results"))
                val buffer = okio.Buffer(); requireNotNull(request.body).writeTo(buffer)
                val input = JSONObject(buffer.readUtf8())
                val output = if (request.url.encodedPath == "/v1/device/actions/results") {
                    receipts.put(input)
                    JSONObject().put("accepted", true).put("replayed", false)
                } else {
                    requests.put(input)
                    if (input.has("confirmation_token")) JSONObject().put("request_id", input.getString("request_id"))
                        .put("directives", org.json.JSONArray().apply {
                            if (externalClock) put(JSONObject().put("type", "Alerts").put("action_id", "TEST-clock-action")
                                .put("payload", JSONObject().put("type", "ShowAlarms")))
                        }) else proposal(input, ttl).apply {
                        if (externalClock) getJSONObject("confirmation").put("type", "Alerts").put("summary", "Mở ứng dụng Đồng hồ?")
                        if (basicClock) getJSONObject("confirmation").put("mode", "commands")
                        // A protocol regression must not execute this before consent.
                        getJSONArray("directives").put(JSONObject().put("type", "PlayerVolume")
                            .put("payload", JSONObject().put("command", "mute")))
                    }
                }
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(output.toString().toResponseBody("application/json".toMediaType())).build()
            }.build()
            MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, client)
            vn.tn.assistant.auth.TnSessionStore.get(context).accept(JSONObject().put("access_token", "TEST-consent").put("refresh_token", "TEST-consent-refresh"))
        }
        fun send(): JSONObject {
            instrumentation.runOnMainSync { MainActivity::class.java.getDeclaredMethod("sendCommand", String::class.java).apply { isAccessible = true }.invoke(activity, "gọi số kiểm thử") }
            return requireNotNull(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        fun dialog(wait: Boolean = true): android.app.Dialog? {
            val reference = java.util.concurrent.atomic.AtomicReference<android.app.Dialog>()
            val deadline = android.os.SystemClock.elapsedRealtime() + if (wait) 5000 else 0
            do {
                instrumentation.runOnMainSync { reference.set(MainActivity::class.java.getDeclaredField("actionConfirmation").apply { isAccessible = true }.get(activity) as? android.app.Dialog) }
                if (reference.get()?.isShowing == true) return reference.get()
                if (wait) Thread.sleep(25)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            return null
        }
        override fun close() {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            client.dispatcher.cancelAll(); isolation.close()
        }
    }
    private fun texts(view: android.view.View): List<android.widget.TextView> =
        (if (view is android.widget.TextView) listOf(view) else emptyList()) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()

    @Test fun cancelDoesNotSubmitConfirmationOrExecutePrematureDirective() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val saved = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 7, 0)
            Harness().use { h ->
                h.send(); val dialog = requireNotNull(h.dialog())
                assertTrue(texts(dialog.window!!.decorView).any { it.text == "Gọi số 0900000000?" })
                assertEquals(7, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                instrumentation.runOnMainSync { texts(dialog.window!!.decorView).single { it.text == "HỦY" }.performClick() }
                assertNull(h.requests.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                assertFalse(dialog.isShowing)
            }
        } finally { audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, saved, 0) }
    }

    @Test fun doubleConfirmSubmitsOneBoundRequestWithANewRequestId() {
        Harness().use { h ->
            val first = h.send(); val dialog = requireNotNull(h.dialog())
            val positive = texts(dialog.window!!.decorView).single { it.text == "XÁC NHẬN" }
            instrumentation.runOnMainSync { positive.performClick(); positive.performClick() }
            val accepted = requireNotNull(h.requests.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(first.getString("conversation_id"), accepted.getString("conversation_id"))
            assertEquals("tools", accepted.getString("mode"))
            assertEquals("TEST-consent-token", accepted.getString("confirmation_token"))
            assertNotEquals(first.getString("request_id"), accepted.getString("request_id"))
            assertNull(h.requests.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }

    @Test fun confirmedClockLaunchKeepsTheOwnersReceiptChannelAfterExpectedActivityPause() {
        Harness(externalClock = true).use { h ->
            h.send(); val dialog = requireNotNull(h.dialog())
            instrumentation.runOnMainSync { texts(dialog.window!!.decorView).single { it.text == "XÁC NHẬN" }.performClick() }
            val confirmation = requireNotNull(h.requests.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            val receipt = requireNotNull(h.receipts.poll(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(confirmation.getString("request_id"), receipt.getString("request_id"))
            assertEquals("TEST-clock-action", receipt.getString("action_id"))
            assertEquals("success", receipt.getString("result"))
            assertTrue(receipt.getJSONObject("details").getBoolean("intent_started"))
            assertFalse(receipt.getJSONObject("details").getBoolean("completion_verified"))
            val resumed = MainActivity::class.java.getDeclaredField("resumed").apply { isAccessible = true }
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000
            while (resumed.getBoolean(h.activity) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertFalse("Opening Clock should pause the assistant without retiring the approved action", resumed.getBoolean(h.activity))
        }
    }

    @Test fun confirmedBasicClockUsesCommandsModeAndPreservesTheOwnersReceiptChannel() {
        Harness(externalClock = true, basicClock = true).use { h ->
            val initial = h.send()
            val dialog = requireNotNull(h.dialog())
            assertEquals(java.util.TimeZone.getDefault().id, initial.getString("timezone"))
            assertTrue(initial.getJSONObject("client_capabilities").getBoolean("basic_alerts"))
            instrumentation.runOnMainSync { texts(dialog.window!!.decorView).single { it.text == "XÁC NHẬN" }.performClick() }
            val confirmation = requireNotNull(h.requests.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("commands", confirmation.getString("mode"))
            assertEquals(initial.getString("conversation_id"), confirmation.getString("conversation_id"))
            val receipt = requireNotNull(h.receipts.poll(8, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(confirmation.getString("request_id"), receipt.getString("request_id"))
            assertEquals("success", receipt.getString("result"))
            assertFalse(receipt.getJSONObject("details").getBoolean("completion_verified"))
        }
    }

    @Test fun accountReplacementMakesAnAlreadyVisibleConfirmButtonInert() {
        Harness().use { h ->
            h.send(); val dialog = requireNotNull(h.dialog())
            vn.tn.assistant.auth.TnSessionStore.get(context).accept(JSONObject().put("access_token", "TEST-consent-other").put("refresh_token", "TEST-consent-other-refresh"))
            instrumentation.runOnMainSync { texts(dialog.window!!.decorView).single { it.text == "XÁC NHẬN" }.performClick() }
            assertNull(h.requests.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }

    @Test fun dialogExpiryAndLeavingForegroundNeverSubmitTheAction() {
        Harness(600).use { h ->
            h.send(); val dialog = requireNotNull(h.dialog())
            Thread.sleep(800)
            instrumentation.waitForIdleSync()
            assertFalse(dialog.isShowing)
            assertNull(h.requests.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
        Harness().use { h ->
            h.send(); val dialog = requireNotNull(h.dialog())
            instrumentation.runOnMainSync { h.activity.moveTaskToBack(true) }
            Thread.sleep(200); instrumentation.waitForIdleSync()
            assertFalse(dialog.isShowing)
            assertNull(h.requests.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }

    @Test fun backgroundProposalCanBePresentedWhenTheOwnerReturnsToTheApp() {
        Harness().use { h ->
            instrumentation.runOnMainSync { h.activity.moveTaskToBack(true) }
            Thread.sleep(200)
            val store = vn.tn.assistant.auth.TnSessionStore.get(context)
            val owner = store.actionGeneration()
            val request = TnAssistantConversation.shared.request("call", "TEST-background", "TEST-device", owner) { store.ownsActions(owner) }
            assertTrue(vn.tn.assistant.actions.TnActionConfirmation.shared.offer(proposal(request), owner) { store.ownsActions(owner) })
            assertNull(h.dialog(false))
            instrumentation.runOnMainSync { h.activity.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            val dialog = requireNotNull(h.dialog())
            instrumentation.runOnMainSync { dialog.cancel() }
            assertNull(h.requests.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }

    @Test fun longConsentTextScrollsWithoutPushingButtonsOutsideNarrowOrLandscapeWindows() {
        val config = android.content.res.Configuration(context.resources.configuration).apply { fontScale = 2f }
        val scaled = context.createConfigurationContext(config)
        for ((width, height) in listOf(900 to 500, 440 to 800)) {
            instrumentation.runOnMainSync {
                val surface = vn.tn.assistant.ui.TnSettingsConfirmation(scaled, "Xác nhận thao tác", "Nội dung nhắc việc dài. ".repeat(50),
                    "HỦY", "XÁC NHẬN", {}, {}, {})
                surface.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                surface.layout(0, 0, width, height)
                for (label in listOf("HỦY", "XÁC NHẬN")) {
                    val button = texts(surface).single { it.text == label }
                    val bounds = android.graphics.Rect(0, 0, button.width, button.height)
                    surface.offsetDescendantRectToMyCoords(button, bounds)
                    var parent: android.view.View? = button
                    val geometry = StringBuilder()
                    while (parent != null) {
                        geometry.append(" ${parent.javaClass.simpleName}:${parent.measuredWidth}x${parent.measuredHeight}@${parent.top}")
                        if (parent is android.widget.LinearLayout && parent.childCount == 4) {
                            geometry.append((0 until parent.childCount).map { val child = parent.getChildAt(it); "child:${child.javaClass.simpleName}:${child.measuredWidth}x${child.measuredHeight}@${child.top}" })
                        }
                        parent = parent.parent as? android.view.View
                    }
                    assertTrue("$label outside $width x $height: $bounds $geometry", bounds.top >= 0 && bounds.bottom <= height && bounds.left >= 0 && bounds.right <= width)
                    assertTrue(button.height > 0)
                }
                fun scrolls(view: android.view.View): List<android.widget.ScrollView> =
                    (if (view is android.widget.ScrollView) listOf(view) else emptyList()) +
                        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { scrolls(view.getChildAt(it)) } else emptyList()
                val scroll = scrolls(surface).single()
                assertTrue(scroll.getChildAt(0).height > scroll.height)
            }
        }
    }
}
