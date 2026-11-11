package vn.tn.assistant.api

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class TnSpeechStreamSessionTest {
    @Test fun queuesAudioUntilReadyThenSendsEndAndReturnsFinalTranscript() {
        val server = MockWebServer()
        val events = CopyOnWriteArrayList<String>()
        val receivedAudio = CopyOnWriteArrayList<ByteString>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = when {
                    text.contains("\"type\":\"start\"") -> "start"
                    text.contains("\"type\":\"end\"") -> "end"
                    else -> "other"
                }
                events += type
                when (type) {
                    "start" -> webSocket.send("{\"type\":\"ready\"}")
                    "end" -> {
                        webSocket.send("{\"type\":\"partial\",\"text\":\"mở bản\"}")
                        webSocket.send("{\"type\":\"final\",\"text\":\"mở bản đồ\"}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events += "audio"
                receivedAudio += bytes
            }
        }))
        server.start()
        val partials = CopyOnWriteArrayList<String>()
        val session = TnSpeechStreamSession(OkHttpClient(), server.url("/v1/speech/stream").toString()
            .replaceFirst("http:", "ws:"), "access", "test", { error("unexpected refresh") }, partials::add)
        try {
            assertTrue(session.sendAudio(byteArrayOf(1, 2, 3, 4)))
            assertEquals("mở bản đồ", session.finish(5_000))
            assertEquals(listOf("start", "audio", "end"), events)
            assertEquals(4, receivedAudio.single().size)
            assertEquals(listOf("mở bản"), partials)
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test fun refreshesOneUnauthorizedHandshakeBeforeSendingAudio() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"code\":\"SESSION_EXPIRED\"}}"))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"type\":\"start\"")) webSocket.send("{\"type\":\"ready\"}")
                if (text.contains("\"type\":\"end\"")) webSocket.send("{\"type\":\"final\",\"text\":\"đã nhận\"}")
            }
        }))
        server.start()
        var refreshes = 0
        val session = TnSpeechStreamSession(OkHttpClient(), server.url("/v1/speech/stream").toString()
            .replaceFirst("http:", "ws:"), "expired", "test", {
                refreshes++
                "rotated"
            })
        try {
            session.sendAudio(byteArrayOf(7, 8))
            assertEquals("đã nhận", session.finish(5_000))
            val first = server.takeRequest(5, TimeUnit.SECONDS)
            val second = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(first)
            assertNotNull(second)
            assertEquals("Bearer expired", first!!.getHeader("Authorization"))
            assertEquals("Bearer rotated", second!!.getHeader("Authorization"))
            assertEquals(1, refreshes)
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test fun fallsBackOnlyWhenServerRejectsBeforeReceivingAudio() {
        val server = MockWebServer()
        val audioFrames = CopyOnWriteArrayList<ByteString>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"type\":\"start\"")) {
                    webSocket.send("{\"type\":\"error\",\"error\":{\"code\":\"STREAMING_UNSUPPORTED\",\"message\":\"HTTP only\"}}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { audioFrames += bytes }
        }))
        server.start()
        val session = TnSpeechStreamSession(OkHttpClient(), server.url("/v1/speech/stream").toString()
            .replaceFirst("http:", "ws:"), "access", "test", { error("unexpected refresh") })
        try {
            assertTrue(session.sendAudio(byteArrayOf(1, 2)))
            val fallback = runCatching { session.finish(5_000) }.exceptionOrNull()
            assertTrue(fallback is TnSpeechStreamFallback)
            assertEquals("STREAMING_UNSUPPORTED", (fallback as TnSpeechStreamFallback).providerCode)
            assertTrue(audioFrames.isEmpty())
        } finally {
            session.close()
            server.shutdown()
        }
    }

    @Test fun neverRetriesAudioOverHttpAfterStreamAcceptedAudio() {
        val server = MockWebServer()
        val audioFrames = CopyOnWriteArrayList<ByteString>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"type\":\"start\"")) webSocket.send("{\"type\":\"ready\"}")
                if (text.contains("\"type\":\"end\"")) {
                    webSocket.send("{\"type\":\"error\",\"error\":{\"code\":\"NO_SPEECH\",\"message\":\"No speech\"}}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { audioFrames += bytes }
        }))
        server.start()
        val session = TnSpeechStreamSession(OkHttpClient(), server.url("/v1/speech/stream").toString()
            .replaceFirst("http:", "ws:"), "access", "test", { error("unexpected refresh") })
        try {
            assertTrue(session.sendAudio(byteArrayOf(5, 6, 7, 8)))
            val failure = runCatching { session.finish(5_000) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(failure !is TnSpeechStreamFallback)
            assertEquals(4, audioFrames.single().size)
        } finally {
            session.close()
            server.shutdown()
        }
    }
}
