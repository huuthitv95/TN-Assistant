package vn.tn.assistant.audio

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import vn.tn.assistant.ui.TnOnboardingSurface
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Exercises real AudioRecord/service teardown against a loopback-only fixture API. */
class TnWakeLifecycleRuntimeTest {
    private fun withListener(
        fixture: LocalFixtureServer,
        expectReady: Boolean = true,
        initialMicrophoneConsent: Boolean = true,
        armBeforeEnable: (() -> Unit)? = null,
        test: (Context, ActivityScenario<MainActivity>) -> Unit,
    ) {
        assertTrue("Debug runtime tests must never target production", BuildConfig.API_BASE_URL == "http://127.0.0.1:8088")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessionPrefs = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val wakePrefs = context.getSharedPreferences("tn-wake-control-v1", Context.MODE_PRIVATE)
        val savedSession = snapshot(sessionPrefs)
        val savedWake = snapshot(wakePrefs)
        var scenario: ActivityScenario<MainActivity>? = null
        var fixtureClosed = false
        var scenarioClosed = false
        var listenerTeardownSafe = false
        var transientCloseFailure: Throwable? = null
        fun closeWithRetry(action: () -> Unit, markClosed: () -> Unit): Throwable? {
            var firstFailure: Throwable? = null
            repeat(3) { attempt ->
                try {
                    action()
                    markClosed()
                    return firstFailure
                } catch (error: Throwable) {
                    if (firstFailure == null) firstFailure = error
                    else firstFailure?.addSuppressed(error)
                    if (attempt < 2) Thread.sleep(100)
                }
            }
            throw requireNotNull(firstFailure)
        }
        fun rememberTransientCloseFailure(failure: Throwable?) {
            if (failure == null) return
            if (transientCloseFailure == null) transientCloseFailure = failure
            else transientCloseFailure?.addSuppressed(failure)
        }
        fun closeFixture() {
            if (fixtureClosed) return
            rememberTransientCloseFailure(closeWithRetry(fixture::close) { fixtureClosed = true })
        }
        fun closeScenario() {
            if (scenarioClosed) return
            rememberTransientCloseFailure(closeWithRetry({ scenario?.close() }, { scenarioClosed = true }))
        }
        val store = TnSessionStore.get(context)
        try {
            fixture.start()
            store.clear()
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            store.accept(session("fixture-access-a"))
            sessionPrefs.edit().putBoolean("tn_welcome_completed_v2", true)
                .putBoolean("wake_word_enabled", true).putString("device_id", "fixture-device").commit()
            wakePrefs.edit().putBoolean("microphone_consent", initialMicrophoneConsent).commit()

            val launchedScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = launchedScenario
            launchedScenario.onActivity { activity ->
                armBeforeEnable?.invoke()
                assertTrue("The user-visible activity can enable the foreground microphone service",
                    TnWakeService.enableFromVisibleActivity(activity))
            }
            if (expectReady) {
                await("AudioRecord should capture frames before the test", 30_000) { TnWakeService.isReady() && TnWakeService.capturedFrames() >= 3L }
            }
            test(context, launchedScenario)
        } finally {
            runCatching { TnWakeService.disable(context) }
            var cleanupFailure: Throwable? = null
            try {
                await("Listener cleanup must release service, recorder, and capture lease", 30_000) {
                    !TnWakeService.isRunning() && !TnWakeService.isListenerThreadAlive() &&
                        TnWakeService.isWorkerComplete() && !TnWakeService.isRecording() && !TnRecordingSessionGate.process.isActive
                }
                listenerTeardownSafe = true
            } catch (error: Throwable) {
                cleanupFailure = error
                runCatching { closeFixture() }
                runCatching { closeScenario() }
                runCatching { TnWakeService.disable(context) }
                runCatching {
                    await("Listener teardown after cleanup timeout", 30_000) {
                        !TnWakeService.isRunning() && !TnWakeService.isListenerThreadAlive() &&
                            TnWakeService.isWorkerComplete() && !TnWakeService.isRecording() &&
                            !TnRecordingSessionGate.process.isActive
                    }
                }.onSuccess {
                    listenerTeardownSafe = true
                    // Preserve the initial timeout as the primary cleanup failure.
                }
                    .onFailure { teardown ->
                        cleanupFailure?.let { initial ->
                            if (initial !== teardown) teardown.addSuppressed(initial)
                        }
                        cleanupFailure = teardown
                    }
            } finally {
                // Every cleanup action is independent: a close failure must not
                // skip fixture/session restoration or make the next test inherit
                // this test's account and microphone state.
                var finalFailure: Throwable? = cleanupFailure
                fun recordFailure(error: Throwable?) {
                    if (error == null) return
                    if (finalFailure == null) finalFailure = error
                    else if (finalFailure !== error) finalFailure?.addSuppressed(error)
                }
                recordFailure(runCatching { closeFixture() }.exceptionOrNull())
                recordFailure(runCatching { closeScenario() }.exceptionOrNull())
                // A close that succeeds only after retry is still a teardown
                // defect: retain that evidence instead of silently treating the
                // later successful attempt as a clean run.
                recordFailure(transientCloseFailure)
                if (listenerTeardownSafe && fixtureClosed && scenarioClosed) {
                    recordFailure(runCatching { store.clear() }.exceptionOrNull())
                    recordFailure(runCatching { restore(sessionPrefs, savedSession) }.exceptionOrNull())
                    recordFailure(runCatching { restore(wakePrefs, savedWake) }.exceptionOrNull())
                } else if (finalFailure == null) {
                    finalFailure = IllegalStateException("Unsafe teardown: state restoration suppressed")
                }
                finalFailure?.let { throw it }
            }
        }
    }

    @Test fun logoutAndMicrophoneRevocationReleaseTheLiveRecorder() {
        withListener(LocalFixtureServer()) { context, scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val store = TnSessionStore.get(context)
            scenario.onActivity { activity ->
                val logout = MainActivity::class.java.getDeclaredMethod("clearSession").apply { isAccessible = true }
                logout.invoke(activity)
            }
            await("Logout must stop the service and release the recorder") {
                !TnWakeService.isRunning() && !TnWakeService.isRecording() && !TnRecordingSessionGate.process.isActive
            }
            assertFalse("Logout clears local session state", store.hasSession())
            assertFalse("Logout withdraws microphone consent", TnWakeService.consented(context))

            store.accept(session("fixture-access-b"))
            context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE).edit().putBoolean("wake_word_enabled", true).commit()
            scenario.onActivity { activity ->
                assertTrue(TnWakeService.enableFromVisibleActivity(activity))
            }
            await("A fresh fixture login should start a new recorder") { TnWakeService.isReady() && TnWakeService.capturedFrames() >= 3L }

            instrumentation.runOnMainSync { TnWakeService.microphoneRevoked(context) }
            await("The microphone-revoked handler must stop and release the recorder") {
                !TnWakeService.isRunning() && !TnWakeService.isRecording() && !TnRecordingSessionGate.process.isActive
            }
            assertFalse("Revocation withdraws microphone consent", TnWakeService.consented(context))
            assertTrue("Revocation leaves a recovery message", TnWakeService.lastError(context)?.contains("thu hồi") == true)
        }
    }

    @Test fun nonEmptyGrantRequiresValidServerTimeBeforeMicrophoneCapture() {
        listOf(null, "not-a-timestamp").forEach { serverTime ->
            val fixtureResponseObserved = AtomicBoolean(false)
            val fixture = LocalFixtureServer(customizeMe = { me ->
                me.put("entitlements", JSONArray().put(JSONObject()
                    .put("id", "fixture-wake")
                    .put("product_id", "fixture-wake-product")
                    .put("source", "fixture")
                    .put("starts_at", "2020-01-01T00:00:00Z")
                    .put("expires_at", "2030-01-01T00:00:00Z")))
                val plan = JSONObject()
                if (serverTime != null) plan.put("server_time", serverTime)
                me.put("account_plan", plan)
                fixtureResponseObserved.set(
                    if (serverTime == null) !plan.has("server_time")
                    else plan.opt("server_time") == "not-a-timestamp",
                )
            })
            withListener(fixture, expectReady = false) { context, _ ->
                await("Invalid or missing server time must fail closed before opening the recorder") {
                    fixtureResponseObserved.get() &&
                        TnWakeService.lastError(context)?.contains("Chưa xác minh") == true &&
                        !TnWakeService.isRecording() && !TnRecordingSessionGate.process.isActive
                }
                assertFalse(TnWakeService.isRunning())
                assertFalse(TnWakeService.consented(context))
            }
        }
    }

    @Test fun settingsSuccessIsFollowedByFreshEntitlementDenial() {
        val fixture = LocalFixtureServer(staleResponseMode = true)
        withListener(fixture, expectReady = false, initialMicrophoneConsent = false,
            armBeforeEnable = fixture::armStaleResponse) { context, _ ->
            await("Settings and fresh entitlement denial must stop before capture", 30_000) {
                fixture.servicePreflightObserved && fixture.settingsSucceeded && fixture.denialObserved &&
                    fixture.denialAfterSettings &&
                    TnWakeService.lastError(context)?.contains("chưa có quyền") == true &&
                    !TnWakeService.isRunning() && !TnWakeService.isRecording() &&
                    !TnRecordingSessionGate.process.isActive
            }
            assertTrue("The armed service preflight must precede settings", fixture.servicePreflightObserved)
            assertTrue("The settings response must be flushed before denial", fixture.settingsSucceeded)
            assertTrue("The denied entitlement check must follow settings", fixture.denialObserved && fixture.denialAfterSettings)
            assertFalse("Fresh entitlement denial stops the service", TnWakeService.isRunning())
            assertFalse(TnWakeService.isRecording())
            assertFalse(TnRecordingSessionGate.process.isActive)
            assertFalse(TnWakeService.consented(context))
            assertTrue(TnWakeService.lastError(context)?.contains("chưa có quyền") == true)
            assertEquals("The recorder must not capture after stale authorization", fixture.framesBeforeEnable, TnWakeService.capturedFrames())
        }
    }

    @Test fun expiryRechecksTheFeatureAndStopsEvenWithAnotherActiveGrant() {
        // Fix the server clock far from device time. The first grant ends while another
        // still covers wake, then the wake grant ends while an unrelated grant remains.
        val firstBoundary = AtomicBoolean(false)
        val lastBoundary = AtomicBoolean(false)
        var previouslyHadFirstGrant: Boolean? = null
        var previouslyHadWakeFeature: Boolean? = null
        val origin = AtomicLong(0L)
        val boundaryLock = Any()
        val fixture = LocalFixtureServer(customizeMe = { me ->
            synchronized(boundaryLock) {
                val elapsed = SystemClock.elapsedRealtime()
                origin.compareAndSet(0L, elapsed)
                val age = elapsed - origin.get()
                val clock = 1_700_000_000_000L
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                fun stamp(offset: Long) = format.format(Date(clock + offset))
                val grants = JSONArray()
                val grantEnds = listOf(30_000L, 50_000L, 120_000L)
                grantEnds.forEachIndexed { index, end ->
                    if (age < end) grants.put(JSONObject()
                        .put("id", "fixture-grant-$index")
                        .put("product_id", if (index < 2) "fixture-wake-product" else "fixture-unrelated-product")
                        .put("source", "fixture")
                        .put("starts_at", stamp(-60_000L))
                        .put("expires_at", stamp(end)))
                }
                val hasFirstGrant = age < grantEnds[0]
                val hasWakeFeature = age < grantEnds[1]
                me.put("entitlements", grants)
                    .put("account_plan", JSONObject().put("kind", "premium").put("server_time", stamp(age)))
                    .put("premium_features", JSONObject().put("wake_word", hasWakeFeature))
                if (previouslyHadFirstGrant == true && !hasFirstGrant) firstBoundary.set(true)
                if (previouslyHadWakeFeature == true && !hasWakeFeature) lastBoundary.set(true)
                previouslyHadFirstGrant = hasFirstGrant
                previouslyHadWakeFeature = hasWakeFeature
            }
        })
        withListener(fixture) { context, _ ->
            await("Revalidate the earliest grant while a second grant still covers wake", 45_000) { firstBoundary.get() }
            val before = TnWakeService.capturedFrames()
            await("A still-authorized recorder resumes capturing after verification") {
                TnWakeService.isReady() && TnWakeService.capturedFrames() > before + 3
            }
            await("Release the recorder when the backend withdraws the wake feature", 60_000) {
                lastBoundary.get() && !TnWakeService.isRunning() && !TnWakeService.isRecording() && !TnRecordingSessionGate.process.isActive
            }
            assertFalse(TnWakeService.consented(context))
            assertTrue(TnWakeService.lastError(context)?.contains("chưa có quyền") == true)
        }
    }

    /** Run seed and restore in separate instrumentations with an am force-stop between them. */
    @Test fun seedConsentedRecorderForColdStart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnWakeColdStart") == "seed")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:8088" && context.packageName.endsWith(".debug"))
        val store = TnSessionStore.get(context)
        check(!store.hasSession()) { "Use a disposable debug installation without a login" }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        LocalFixtureServer().use { fixture ->
            fixture.start()
            store.accept(session("fixture-seed"))
            context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE).edit()
                .putBoolean("tn_welcome_completed_v2", true).putBoolean("wake_word_enabled", true).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertTrue(TnWakeService.enableFromVisibleActivity(it)) }
                await("Recorder active before the process ends", 30_000) { TnWakeService.isReady() }
                assertTrue(context.getSharedPreferences("tn-wake-restart-test", Context.MODE_PRIVATE).edit()
                    .putInt("seed_pid", android.os.Process.myPid()).commit())
            }
        }
    }

    @Test fun launcherRestoresWakeOnlyAfterFreshAuthorization() {
        val mode = InstrumentationRegistry.getArguments().getString("tnWakeColdStart")
        assumeTrue(mode in setOf("offline-retry", "denied"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.API_BASE_URL == "http://127.0.0.1:8088" && context.packageName.endsWith(".debug"))
        val marker = context.getSharedPreferences("tn-wake-restart-test", Context.MODE_PRIVATE)
        val previousPid = marker.getInt("seed_pid", -1)
        check(previousPid > 0 && previousPid != android.os.Process.myPid())
        val store = TnSessionStore.get(context)
        assertNull(store.accessToken)
        assertTrue(store.hasSession() && TnWakeService.consented(context))
        assertFalse(TnWakeService.isRecording())
        val fixture = LocalFixtureServer(customizeMe = { me ->
            me.put("premium_features", JSONObject().put("wake_word", mode != "denied"))
        })
        try {
            if (mode == "denied") fixture.start()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                if (mode == "offline-retry") {
                    var retry: View? = null
                    await("The offline launcher must offer a session retry") {
                        scenario.onActivity { activity ->
                            val content = activity.findViewById<ViewGroup>(android.R.id.content)
                            val surface = content.getChildAt(0) as? TnOnboardingSurface
                            retry = surface?.primaryButton?.takeIf { it.text.toString().equals("THỬ LẠI", ignoreCase = true) }
                        }
                        retry != null
                    }
                    assertNull(store.accessToken)
                    assertTrue(store.hasSession())
                    assertFalse(TnWakeService.isRunning() || TnWakeService.isRecording() || TnRecordingSessionGate.process.isActive)
                    fixture.start()
                    scenario.onActivity { assertTrue(requireNotNull(retry).performClick()) }
                    await("Retry refreshes the saved login before starting the recorder", 30_000) {
                        store.accessToken == "fixture-access-a" && TnWakeService.isReady() && TnWakeService.capturedFrames() >= 3
                    }
                } else {
                    await("A saved switch cannot restore the microphone after wake entitlement expires") {
                        store.accessToken == "fixture-access-a" && TnWakeService.lastError(context)?.contains("chưa có quyền") == true
                    }
                    assertFalse(TnWakeService.consented(context) || TnWakeService.isRecording() || TnRecordingSessionGate.process.isActive)
                }
            }
        } finally {
            TnWakeService.disableRuntime(context)
            store.clear()
            marker.edit().remove("seed_pid").commit()
            fixture.close()
        }
    }


    private fun session(access: String) = JSONObject()
        .put("access_token", access)
        .put("refresh_token", "$access-refresh")

    private fun await(message: String, timeoutMillis: Long = 12_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(message, predicate())
    }

    private fun snapshot(preferences: SharedPreferences): Map<String, Any> = preferences.all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else requireNotNull(value)
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, Any>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported preference type")
            }
        }
        check(editor.commit())
    }

    private class LocalFixtureServer(
        private val customizeMe: (JSONObject) -> Unit = {},
        private val staleResponseMode: Boolean = false,
    ) : AutoCloseable {
        private val running = AtomicBoolean(false)
        private val lifecycleLock = Any()
        @Volatile var servicePreflightObserved = false
            private set
        @Volatile var settingsSucceeded = false
            private set
        @Volatile var denialObserved = false
            private set
        @Volatile var denialAfterSettings = false
            private set
        @Volatile var framesBeforeEnable = 0L
            private set
        @Volatile private var staleResponseArmed = false
        @Volatile private var denyLaterMe = false
        private var socket: ServerSocket? = null
        private var worker: Thread? = null
        private val workers = java.util.Collections.synchronizedSet(mutableSetOf<Thread>())
        private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
        private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        private var effectiveWakeFeature = true
        private var armedMeRequests = 0
        private var settingsRequestsAfterArm = 0

        fun armStaleResponse() {
            check(staleResponseMode)
            framesBeforeEnable = TnWakeService.capturedFrames()
            synchronized(this) {
                staleResponseArmed = true
                armedMeRequests = 0
                settingsRequestsAfterArm = 0
                servicePreflightObserved = false
                settingsSucceeded = false
                denialObserved = false
                denialAfterSettings = false
                denyLaterMe = false
            }
        }

        fun start() {
            synchronized(lifecycleLock) {
                check(!running.get()) { "Fixture server is already running" }
                val bound = ServerSocket(8088, 8, InetAddress.getByName("127.0.0.1"))
                socket = bound
                running.set(true)
                worker = Thread({
                    while (running.get()) {
                        val client = try { bound.accept() } catch (_: Exception) { null } ?: break
                        synchronized(lifecycleLock) {
                            if (!running.get()) {
                                runCatching { client.close() }
                            } else {
                                clients += client
                                Thread({
                                    try { respond(client) }
                                    finally {
                                        synchronized(lifecycleLock) {
                                            clients.remove(client)
                                            workers.remove(Thread.currentThread())
                                        }
                                    }
                                }, "tn-wake-fixture-request").also {
                                    it.isDaemon = true
                                    workers += it
                                    // Register and start under the lifecycle
                                    // lock: close() cannot snapshot a NEW
                                    // worker and join it before start().
                                    it.start()
                                }
                            }
                        }
                    }
                }, "tn-wake-fixture-accept").apply { isDaemon = true }
                worker?.start()
            }
        }

        private fun respond(client: Socket) {
            client.use { connection ->
                connection.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))
                val request = reader.readLine()?.split(' ').orEmpty()
                val method = request.getOrNull(0)
                val requestedPath = request.getOrNull(1)?.substringBefore('?')
                if (method != null && requestedPath != null) synchronized(requests) { requests += "$method $requestedPath" }
                var contentLength = 0
                var wakeEntitlementMarker = false
                var promoteDenialAfterResponse = false
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    if (line.equals("X-TN-Wake-Entitlement: 1", ignoreCase = true)) wakeEntitlementMarker = true
                }
                if (staleResponseMode && staleResponseArmed && requestedPath != "/v1/auth/refresh" && !wakeEntitlementMarker) {
                    return
                }
                repeat(contentLength) { if (reader.read() < 0) return }
                val path = request.getOrNull(1)?.substringBefore('?')
                synchronized(this) {
                    var status = "200 OK"
                    val body = when (path) {
                        "/v1/auth/refresh" -> JSONObject().put("access_token", "fixture-access-a")
                            .put("refresh_token", "fixture-access-a-refresh").toString()
                        "/v1/me" -> {
                            val denied = denyLaterMe
                            JSONObject().put("user", JSONObject().put("id", "fixture-user").put("role", "user"))
                                .put("premium_features", JSONObject().put("wake_word", !denied))
                                .put("entitlements", JSONArray())
                                .put("account_plan", JSONObject().put("kind", "basic").put("server_time", "2024-01-01T00:00:00Z"))
                                .put("local_premium", JSONObject().put("enabled", false)).also { me ->
                                    customizeMe(me)
                                    if (staleResponseArmed && wakeEntitlementMarker && !denied &&
                                        me.optJSONObject("premium_features")?.opt("wake_word") == true) {
                                        armedMeRequests += 1
                                        if (armedMeRequests == 1) servicePreflightObserved = true
                                    }
                                    if (denied && wakeEntitlementMarker) {
                                        denialObserved = true
                                        denialAfterSettings = settingsSucceeded
                                    }
                                    effectiveWakeFeature = me.optJSONObject("premium_features")?.opt("wake_word") == true
                                }.toString()
                        }
                        "/v1/settings" -> {
                            if (effectiveWakeFeature) {
                                val responseBody = JSONObject().put("wake_word_enabled", true).toString()
                                if (staleResponseArmed && wakeEntitlementMarker && servicePreflightObserved) {
                                    settingsRequestsAfterArm += 1
                                    if (settingsRequestsAfterArm == 1) promoteDenialAfterResponse = true
                                }
                                responseBody
                            } else {
                                status = "403 Forbidden"
                                JSONObject().put("error", JSONObject().put("code", "FEATURE_NOT_AVAILABLE")).toString()
                            }
                        }
                        else -> {
                            status = "404 Not Found"
                            JSONObject().put("error", JSONObject().put("code", "NOT_FOUND")).toString()
                        }
                    }.toByteArray(StandardCharsets.UTF_8)
                    val output = connection.getOutputStream()
                    output.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write(body)
                    output.flush()
                    if (promoteDenialAfterResponse) {
                        settingsSucceeded = true
                        denyLaterMe = true
                    }
                }
            }
        }

        override fun close() {
            var failure: Throwable? = null
            fun record(error: Throwable) {
                val prior = failure
                if (prior == null) failure = error else if (prior !== error) prior.addSuppressed(error)
            }
            val acceptor: Thread?
            synchronized(lifecycleLock) {
                running.set(false)
                acceptor = worker
                worker = null
                runCatching { socket?.close() }.onFailure(::record)
                socket = null
                clients.toList().forEach { client ->
                    runCatching { client.close() }.onFailure(::record)
                }
            }
            acceptor?.let { thread ->
                runCatching { thread.join(1000) }.onFailure(::record)
            }
            repeat(3) {
                val pending = synchronized(lifecycleLock) { workers.toList() }
                if (pending.isEmpty()) return@repeat
                pending.forEach { thread ->
                    runCatching { thread.join(1000) }.onFailure(::record)
                }
                synchronized(lifecycleLock) {
                    clients.toList().forEach { client ->
                        runCatching { client.close() }.onFailure(::record)
                    }
                }
            }
            synchronized(lifecycleLock) {
                if (workers.isNotEmpty()) record(IllegalStateException("Fixture request workers did not terminate"))
                if (clients.isNotEmpty()) record(IllegalStateException("Fixture client sockets did not close"))
            }
            failure?.let { throw it }
        }
    }
}
