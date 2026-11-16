package vn.tn.assistant.ui

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Cancellation after TTS must not fetch the stale signed audio URL. */
class TnWelcomeCancellationRuntimeTest {
    @Test fun canceledPlaybackStopsBeforeAudioDownload() {
        assertEquals("This loopback-only test must use the debug backend", "http://127.0.0.1:8088", BuildConfig.API_BASE_URL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val isolated = TnIsolatedActivityPreferences(context)
        val store = TnSessionStore.get(context)
        val fixture = LocalFixtureServer()
        var scenario: ActivityScenario<MainActivity>? = null
        val executor = Executors.newSingleThreadExecutor()
        try {
            fixture.start()
            store.clear()
            store.accept(JSONObject().put("access_token", "fixture-access").put("refresh_token", "fixture-refresh"))
            context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE).edit()
                .putBoolean("tn_welcome_completed_v2", true)
                .putBoolean("welcome_enabled", false)
                .putString("device_id", "welcome-cancellation-fixture")
                .commit()

            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var activity: MainActivity
            scenario!!.onActivity { activity = it }
            val active = AtomicBoolean(true)
            val playSpeech = MainActivity::class.java.getDeclaredMethod(
                "playSpeech", String::class.java, String::class.java, Function0::class.java,
            ).apply { isAccessible = true }
            val activeCheck: () -> Boolean = { active.get() }
            val playback = executor.submit<Boolean> {
                playSpeech.invoke(activity, "TN ready", "fixture-access", activeCheck) as Boolean
            }

            assertTrue("Playback never reached the local /v1/tts fixture", fixture.ttsStarted.await(10, TimeUnit.SECONDS))
            active.set(false)
            fixture.releaseTts.countDown()
            val result = runCatching { playback.get(10, TimeUnit.SECONDS) }
            assertTrue("Canceled playback must return without downloading audio", result.isSuccess)
            assertFalse("Canceled playback must not start", result.getOrThrow())
            assertEquals("A stale HTTPS audio URL was requested after cancellation", 0, fixture.audioTlsAttempts.get())
        } finally {
            fixture.releaseTts.countDown()
            runCatching { scenario?.close() }
            executor.shutdownNow()
            fixture.close()
            store.clear()
            isolated.close()
        }
    }

    private class LocalFixtureServer : AutoCloseable {
        private val running = AtomicBoolean(false)
        private var server: ServerSocket? = null
        private var acceptThread: Thread? = null
        val ttsStarted = CountDownLatch(1)
        val releaseTts = CountDownLatch(1)
        val audioTlsAttempts = AtomicInteger()

        fun start() {
            server = ServerSocket(8088, 8, InetAddress.getByName("127.0.0.1"))
            running.set(true)
            acceptThread = Thread({
                while (running.get()) {
                    val client = try { server?.accept() } catch (_: Exception) { null } ?: break
                    Thread({ respond(client) }, "tn-welcome-cancellation-request").apply { isDaemon = true; start() }
                }
            }, "tn-welcome-cancellation-accept").apply { isDaemon = true; start() }
        }

        private fun respond(client: Socket) {
            client.use { connection ->
                connection.soTimeout = 5000
                val input = PushbackInputStream(connection.getInputStream(), 1)
                val firstByte = input.read()
                if (firstByte == 0x16) {
                    audioTlsAttempts.incrementAndGet()
                    return
                }
                if (firstByte < 0) return
                input.unread(firstByte)
                val reader = BufferedReader(InputStreamReader(input, StandardCharsets.US_ASCII))
                val request = reader.readLine()?.split(' ')
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                repeat(contentLength) { if (reader.read() < 0) return }
                val path = request?.getOrNull(1)?.substringBefore('?')
                if (path == "/v1/tts") {
                    ttsStarted.countDown()
                    releaseTts.await(10, TimeUnit.SECONDS)
                }
                val payload = when (path) {
                    "/v1/auth/refresh" -> JSONObject().put("access_token", "fixture-access")
                        .put("refresh_token", "fixture-refresh").toString()
                    "/v1/me" -> JSONObject().put("user", JSONObject().put("id", "fixture-user")).toString()
                    "/v1/settings" -> JSONObject().put("welcome_enabled", false).toString()
                    "/v1/tts" -> JSONObject().put("url", "https://127.0.0.1:8088/v1/tts/audio/fixture").toString()
                    else -> "{}"
                }.toByteArray(StandardCharsets.UTF_8)
                val output = connection.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.US_ASCII))
                output.write(payload)
                output.flush()
            }
        }

        override fun close() {
            running.set(false)
            runCatching { server?.close() }
            runCatching { acceptThread?.join(1000) }
        }
    }
}
