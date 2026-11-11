package vn.tn.assistant.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Authenticated, bounded PCM stream. Audio sent to ASR is never retried over HTTP. */
internal class TnSpeechStreamSession(
    private val client: OkHttpClient,
    private val url: String,
    initialToken: String,
    private val clientVersion: String,
    private val refresh: (String) -> String,
    private val onPartial: (String) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val ready = CountDownLatch(1)
    private val finished = CountDownLatch(1)
    private val queued = ArrayDeque<ByteString>()
    @Volatile private var socket: WebSocket? = null
    private var unauthorizedRetried = false
    private var serverReady = false
    private var endRequested = false
    private var endSent = false
    private var closed = false
    private var fallbackCode: String? = null
    private var failure: IOException? = null
    private var finalText: String? = null
    private var queuedBytes = 0
    private var sentBytes = 0
    private var totalBytes = 0

    init {
        connect(initialToken)
    }

    /** Returns false only when the server rejected streaming before accepting any audio. */
    fun sendAudio(pcm: ByteArray): Boolean {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "PCM16 audio must be non-empty and sample-aligned" }
        synchronized(lock) {
            if (fallbackCode != null) return false
            failure?.let { throw it }
            if (closed || finalText != null) throw IOException("ASR stream has already ended")
            if (totalBytes > MAX_AUDIO_BYTES - pcm.size) throw IOException("ASR stream exceeded the 15-second audio limit")
            totalBytes += pcm.size
            val frame = ByteString.of(*pcm)
            if (!serverReady) {
                queued.addLast(frame)
                queuedBytes += frame.size
                return true
            }
            return sendLocked(frame)
        }
    }

    fun finish(timeoutMillis: Long = FINAL_TIMEOUT_MS): String {
        synchronized(lock) {
            if (fallbackCode != null) throw TnSpeechStreamFallback(fallbackCode!!)
            failure?.let { throw it }
            if (closed) throw IOException("ASR stream was cancelled")
            endRequested = true
            if (serverReady) flushAndEndLocked()
        }

        if (!ready.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            synchronized(lock) {
                fallbackCode?.let { throw TnSpeechStreamFallback(it) }
                failure?.let { throw it }
                if (!closed) failLocked(IOException("ASR stream did not become ready"), "ASR_STREAM_UNAVAILABLE")
                failure?.let { throw it }
                fallbackCode?.let { throw TnSpeechStreamFallback(it) }
            }
        }
        synchronized(lock) {
            fallbackCode?.let { throw TnSpeechStreamFallback(it) }
            failure?.let { throw it }
            if (closed) throw IOException("ASR stream was cancelled")
            endRequested = true
            if (serverReady) flushAndEndLocked()
        }

        if (!finished.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            cancel()
            throw IOException("ASR stream timed out")
        }
        synchronized(lock) {
            fallbackCode?.let { throw TnSpeechStreamFallback(it) }
            failure?.let { throw it }
            if (closed) throw IOException("ASR stream was cancelled")
            return finalText?.takeIf(String::isNotBlank) ?: throw IOException("TN chưa nhận được câu lệnh. Hãy nhấn micro và nói lại.")
        }
    }

    fun cancel() {
        synchronized(lock) {
            if (closed) return
            closed = true
            queued.clear()
            queuedBytes = 0
            ready.countDown()
            finished.countDown()
            val current = socket
            if (serverReady && finalText == null && failure == null && fallbackCode == null && current != null) {
                current.send(CANCEL_FRAME)
                current.close(1000, "cancelled")
            } else current?.cancel()
            socket = null
        }
    }

    override fun close() = cancel()

    private fun connect(authToken: String) {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $authToken")
            .header("X-TN-Client-Version", clientVersion)
            .build()
        val opened = client.newWebSocket(request, listener(authToken))
        synchronized(lock) {
            if (closed || isTerminalLocked()) opened.cancel() else socket = opened
        }
    }

    private fun listener(authToken: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (closed || isTerminalLocked()) {
                    webSocket.cancel()
                    return
                }
                socket = webSocket
                if (!webSocket.send(START_FRAME)) failLocked(IOException("Không thể bắt đầu luồng nhận dạng TN"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = try { JSONObject(text) } catch (_: Exception) {
                synchronized(lock) { failLocked(IOException("Máy chủ gửi sự kiện nhận dạng không hợp lệ")) }
                return
            }
            val kind = event.optString("type")
            when (kind) {
                "ready" -> synchronized(lock) {
                    if (!closed && !isTerminalLocked()) {
                        serverReady = true
                        ready.countDown()
                        flushAndEndLocked()
                    }
                }
                "partial" -> {
                    val textValue = event.optString("text").trim()
                    if (textValue.isNotEmpty() && textValue.length <= MAX_TRANSCRIPT_CHARS) runCatching { onPartial(textValue) }
                }
                "final" -> synchronized(lock) {
                    if (!closed && !isTerminalLocked()) {
                        val recognized = event.optString("text").trim()
                        if (recognized.isBlank() || recognized.length > MAX_TRANSCRIPT_CHARS) {
                            failLocked(IOException("TN chưa nhận được câu lệnh. Hãy nhấn micro và nói lại."))
                        } else {
                            finalText = recognized
                            ready.countDown()
                            finished.countDown()
                        }
                    }
                }
                "error" -> {
                    val error = event.optJSONObject("error") ?: JSONObject()
                    val code = error.optString("code")
                    val message = error.optString("message").takeIf(String::isNotBlank)
                        ?: "Nhận dạng giọng nói thất bại."
                    synchronized(lock) {
                        if (!closed && !isTerminalLocked()) {
                            if (sentBytes == 0) failLocked(IOException(message), code.ifBlank { "ASR_STREAM_UNAVAILABLE" })
                            else failLocked(IOException(message))
                        }
                    }
                }
                else -> synchronized(lock) { failLocked(IOException("Máy chủ gửi sự kiện nhận dạng không được hỗ trợ")) }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val failedCode = responseCode(response)
            if (failedCode == 401) {
                val retryToken = synchronized(lock) {
                    if (closed || isTerminalLocked() || unauthorizedRetried || sentBytes > 0) null
                    else { unauthorizedRetried = true; authToken }
                }
                if (retryToken != null) {
                    try {
                        val replacement = refresh(retryToken)
                        if (replacement.isBlank()) throw IOException("Phiên TN đã hết hạn")
                        connect(replacement)
                        return
                    } catch (error: Exception) {
                        synchronized(lock) { failLocked(IOException(error.message ?: "Phiên TN đã hết hạn", error)) }
                        return
                    }
                }
                synchronized(lock) {
                    if (!closed && !isTerminalLocked()) failLocked(IOException("Phiên TN đã hết hạn"))
                }
                return
            }
            val serverCode = errorCode(response)
            synchronized(lock) {
                if (closed || isTerminalLocked()) return
                if (sentBytes == 0) {
                    val fallback = serverCode ?: failedCode?.let { "HTTP_$it" } ?: t.javaClass.simpleName.uppercase()
                    failLocked(IOException("Luồng nhận dạng chưa nhận audio; chuyển sang HTTP"), fallback)
                } else {
                    failLocked(IOException("Không thể kết nối luồng nhận dạng TN"))
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                if (!closed && !isTerminalLocked()) failLocked(IOException("Luồng nhận dạng TN đã kết thúc trước khi có kết quả"))
            }
        }
    }

    private fun responseCode(response: Response?): Int? = response?.code

    private fun errorCode(response: Response?): String? = runCatching {
        val body = response?.peekBody(4096)?.string().orEmpty()
        JSONObject(body).optJSONObject("error")?.optString("code")?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun sendLocked(frame: ByteString): Boolean {
        if (fallbackCode != null) return false
        failure?.let { throw it }
        val current = socket ?: throw IOException("Luồng nhận dạng TN chưa kết nối")
        if (!current.send(frame)) {
            failLocked(IOException("Không thể gửi audio tới luồng nhận dạng TN"))
            failure?.let { throw it }
        }
        sentBytes += frame.size
        return true
    }

    private fun flushAndEndLocked() {
        if (!serverReady || closed || isTerminalLocked()) return
        while (queued.isNotEmpty()) {
            val frame = queued.removeFirst()
            queuedBytes -= frame.size
            if (!sendLocked(frame)) return
        }
        if (endRequested && !endSent) {
            val current = socket ?: return
            if (!current.send(END_FRAME)) failLocked(IOException("Không thể kết thúc luồng nhận dạng TN"))
            else endSent = true
        }
    }

    private fun failLocked(error: IOException, fallback: String? = null) {
        if (isTerminalLocked()) return
        if (fallback != null) fallbackCode = fallback else failure = error
        queued.clear()
        queuedBytes = 0
        ready.countDown()
        finished.countDown()
    }

    private fun isTerminalLocked() = failure != null || fallbackCode != null || finalText != null

    internal companion object {
        const val MAX_AUDIO_BYTES = 480_000
        const val MAX_TRANSCRIPT_CHARS = 2_000
        const val READY_TIMEOUT_MS = 8_000L
        const val FINAL_TIMEOUT_MS = 45_000L
        private const val START_FRAME = "{\"type\":\"start\",\"sample_rate\":16000,\"channels\":1,\"encoding\":\"pcm_s16le\"}"
        private const val END_FRAME = "{\"type\":\"end\"}"
        private const val CANCEL_FRAME = "{\"type\":\"cancel\"}"
    }
}

internal class TnSpeechStreamFallback(val providerCode: String) : IOException("ASR streaming fallback: $providerCode")
