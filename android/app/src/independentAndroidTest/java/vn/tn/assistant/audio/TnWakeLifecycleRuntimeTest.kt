package vn.tn.assistant.audio

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises real AudioRecord/service teardown against a loopback-only fixture API. */
class TnWakeLifecycleRuntimeTest {
    @Test fun logoutAndMicrophoneRevocationReleaseTheLiveRecorder() {
        assertTrue("Debug runtime tests must never target production", BuildConfig.API_BASE_URL == "http://127.0.0.1:8088")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessionPrefs = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val wakePrefs = context.getSharedPreferences("tn-wake-control-v1", Context.MODE_PRIVATE)
        val savedSession = snapshot(sessionPrefs)
        val savedWake = snapshot(wakePrefs)
        val fixture = LocalFixtureServer()
        var scenario: ActivityScenario<MainActivity>? = null
        val store = TnSessionStore.get(context)
        try {
            fixture.start()
            store.clear()
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
            store.accept(session("fixture-access-a"))
            sessionPrefs.edit().putBoolean("tn_welcome_completed_v2", true)
                .putBoolean("wake_word_enabled", true).putString("device_id", "fixture-device").commit()
            wakePrefs.edit().putBoolean("microphone_consent", true).commit()

            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario!!.onActivity { activity ->
                assertTrue("The user-visible activity can enable the foreground microphone service",
                    TnWakeService.enableFromVisibleActivity(activity))
            }
            await("AudioRecord should capture frames before the logout scenario") { TnWakeService.isReady() && TnWakeService.capturedFrames() >= 3L }

            scenario!!.onActivity { activity ->
                val logout = MainActivity::class.java.getDeclaredMethod("clearSession").apply { isAccessible = true }
                logout.invoke(activity)
            }
            await("Logout must stop the service and release the recorder") {
                !TnWakeService.isRunning() && !TnWakeService.isRecording() && !TnRecordingSessionGate.process.isActive
            }
            assertFalse("Logout clears local session state", store.hasSession())
            assertFalse("Logout withdraws microphone consent", TnWakeService.consented(context))

            store.accept(session("fixture-access-b"))
            sessionPrefs.edit().putBoolean("wake_word_enabled", true).commit()
            wakePrefs.edit().putBoolean("microphone_consent", true).commit()
            scenario!!.onActivity { activity ->
                assertTrue(TnWakeService.enableFromVisibleActivity(activity))
            }
            await("A fresh fixture login should start a new recorder") { TnWakeService.isReady() && TnWakeService.capturedFrames() >= 3L }

            instrumentation.runOnMainSync { TnWakeService.microphoneRevoked(context) }
            await("The microphone-revoked handler must stop and release the recorder") {
                !TnWakeService.isRunning() && !TnWakeService.isRecording() && !TnRecordingSessionGate.process.isActive
            }
            assertFalse("Revocation withdraws microphone consent", TnWakeService.consented(context))
            assertTrue("Revocation leaves a recovery message", TnWakeService.lastError(context)?.contains("thu hồi") == true)
        } finally {
            runCatching { TnWakeService.disable(context) }
            runCatching { store.clear() }
            runCatching { scenario?.close() }
            fixture.close()
            restore(sessionPrefs, savedSession)
            restore(wakePrefs, savedWake)
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

    private class LocalFixtureServer : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var socket: ServerSocket? = null
        private var worker: Thread? = null

        fun start() {
            socket = ServerSocket(8088, 8, InetAddress.getByName("127.0.0.1"))
            running.set(true)
            worker = Thread({
                while (running.get()) {
                    val client = try { socket?.accept() } catch (_: Exception) { null } ?: break
                    Thread({ respond(client) }, "tn-wake-fixture-request").apply { isDaemon = true; start() }
                }
            }, "tn-wake-fixture-accept").apply { isDaemon = true; start() }
        }

        private fun respond(client: Socket) {
            client.use { connection ->
                connection.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII))
                val request = reader.readLine()?.split(' ').orEmpty()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                repeat(contentLength) { if (reader.read() < 0) return }
                val path = request.getOrNull(1)?.substringBefore('?')
                val body = when (path) {
                    "/v1/auth/refresh" -> JSONObject().put("access_token", "fixture-access-a")
                        .put("refresh_token", "fixture-access-a-refresh").toString()
                    "/v1/me" -> JSONObject().put("user", JSONObject().put("id", "fixture-user").put("role", "user"))
                        .put("premium_features", JSONObject().put("wake_word", true))
                        .put("local_premium", JSONObject().put("enabled", true)).toString()
                    "/v1/settings" -> JSONObject().put("wake_word_enabled", true).toString()
                    else -> "{}"
                }.toByteArray(StandardCharsets.UTF_8)
                val output = connection.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.write(body)
                output.flush()
            }
        }

        override fun close() {
            running.set(false)
            runCatching { socket?.close() }
            runCatching { worker?.join(1000) }
        }
    }
}
