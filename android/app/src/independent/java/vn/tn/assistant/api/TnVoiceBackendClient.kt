package vn.tn.assistant.api

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.auth.TnSessionStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID

internal class TnVoiceBackendClient(context: Context) {
    private val session = TnSessionStore.get(context)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val streamClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val base = BuildConfig.API_BASE_URL.toHttpUrl()

    fun json(path: String, data: JSONObject? = null, method: String = if (data == null) "GET" else "POST"): JSONObject {
        val body = data?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        return decode(send(path, method, body))
    }
    fun pcm(bytes: ByteArray): JSONObject {
        require(bytes.isNotEmpty() && bytes.size <= 16000 * 2 * 15)
        return decode(send("/v1/speech/decode?sample_rate=16000&channels=1", "POST", bytes.toRequestBody("audio/pcm".toMediaType())))
    }
    fun speechStream(token: String, onPartial: (String) -> Unit = {}): TnSpeechStreamSession {
        require(token.isNotBlank())
        val httpUrl = base.newBuilder().encodedPath("/v1/speech/stream").build().toString()
        val wsUrl = when {
            httpUrl.startsWith("https:") -> "wss:" + httpUrl.removePrefix("https:")
            httpUrl.startsWith("http:") -> "ws:" + httpUrl.removePrefix("http:")
            else -> throw IOException("Địa chỉ dịch vụ TN không hợp lệ")
        }
        return TnSpeechStreamSession(streamClient, wsUrl, token, BuildConfig.VERSION_NAME,
            refresh = { failed -> session.refreshAfterUnauthorized(failed, ::refresh) }, onPartial = onPartial)
    }
    fun audio(path: String): Pair<ByteArray, String> {
        val target = base.resolve(path) ?: throw IOException("Địa chỉ âm thanh không hợp lệ")
        if (target.scheme != "https" || target.host != base.host || target.port != base.port ||
            target.username.isNotEmpty() || target.password.isNotEmpty() || !target.encodedPath.startsWith("/v1/tts/audio/")) {
            throw IOException("TN từ chối âm thanh ngoài dịch vụ")
        }
        send(target.encodedPath + (target.encodedQuery?.let { "?$it" } ?: ""), "GET", null).use { response ->
            val body = response.body ?: throw IOException("Âm thanh trống")
            val extension = when (body.contentType()?.let { "${it.type}/${it.subtype}" }) {
                "audio/mpeg" -> "mp3"
                "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
                else -> throw IOException("Định dạng âm thanh chưa được hỗ trợ")
            }
            val limit = 16 shl 20
            if (body.contentLength() > limit) throw IOException("Âm thanh vượt giới hạn")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() > limit - count) throw IOException("Âm thanh vượt giới hạn")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray() to extension
        }
    }
    fun cancel() = client.dispatcher.cancelAll()

    private fun refresh(token: String): JSONObject = decode(raw("/v1/auth/refresh", "POST",
        JSONObject().put("refresh_token", token).toString().toRequestBody("application/json".toMediaType()), null))
    private fun send(path: String, method: String, body: RequestBody?): okhttp3.Response {
        val token = session.restore(::refresh)
        val first = raw(path, method, body, token)
        if (first.code != 401) { validate(first); return first }
        first.close()
        val next = session.refreshAfterUnauthorized(token, ::refresh)
        return raw(path, method, body, next).also(::validate)
    }
    private fun raw(path: String, method: String, body: RequestBody?, token: String?): okhttp3.Response {
        require(path.startsWith("/v1/"))
        val request = Request.Builder().url(base.toString().trimEnd('/') + path).method(method, body)
            .header("X-TN-Client-Version", BuildConfig.VERSION_NAME)
        if (token != null) request.header("Authorization", "Bearer $token")
        return client.newCall(request.build()).execute()
    }
    private fun validate(response: okhttp3.Response) {
        if (response.isSuccessful) return
        val code = response.code
        val errorCode = runCatching {
            JSONObject(response.peekBody(8192).string()).optJSONObject("error")?.optString("code")
        }.getOrNull()
        response.close()
        throw TnServiceFailure(code, errorCode)
    }
    private fun decode(response: okhttp3.Response): JSONObject = response.use {
        validate(it)
        JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
    }
}

/** Error bodies are bounded and only recognized codes become user-facing details. */
internal class TnServiceFailure(val statusCode: Int, val errorCode: String?) : IOException(
    knownMessage(errorCode) ?: when (statusCode) {
        401 -> "Phiên TN đã hết hạn. Hãy mở ứng dụng để đăng nhập lại."
        403 -> "Tài khoản hoặc thiết bị chưa có quyền sử dụng tính năng này."
        429 -> "Dịch vụ đang giới hạn lượt dùng. Hãy thử lại sau."
        504 -> "Dịch vụ TN phản hồi quá lâu. Hãy thử lại sau."
        else -> "Dịch vụ TN chưa trả lời được ($statusCode)."
    }
) {
    companion object {
        fun knownMessage(code: String?): String? = when (code) {
            "TOKEN_EXPIRED", "INVALID_REFRESH", "REFRESH_REPLAY", "REFRESH_EXPIRED" ->
                "Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục."
            "AI_NOT_CONFIGURED", "AI_KEY_REQUIRED" -> "AI chưa được cấu hình. Quản trị viên cần kiểm tra nhà cung cấp."
            "AI_KEY_STORE_UNAVAILABLE" -> "Chưa mở được khóa cấu hình AI trên máy chủ. Cần quản trị viên khôi phục."
            "AI_FREE_TIER_UNVERIFIED" -> "AI chưa được bật vì dự án chưa được xác minh gói miễn phí."
            "AI_FREE_POLICY" -> "Cấu hình AI hiện tại không đáp ứng chế độ chỉ dùng miễn phí."
            "AI_CREDENTIAL_REJECTED" -> "Nhà cung cấp từ chối khóa hoặc quyền truy cập AI. Cần quản trị viên kiểm tra."
            "AI_PROJECT_QUOTA", "AI_DAILY_LIMIT" -> "AI đã hết hạn mức hoặc đang chờ cấp lại hạn mức. Hãy thử lại sau."
            "AI_TIMEOUT" -> "AI phản hồi quá lâu; chưa xác nhận được kết quả."
            "AI_UNAVAILABLE", "AI_HTTP_ERROR", "AI_INVALID_RESPONSE", "AI_SEARCH_UNAVAILABLE" -> "Nhà cung cấp AI chưa trả lời hợp lệ. Hãy thử lại sau."
            "LIVE_DATA_PROVIDER_REQUIRED", "AI_SEARCH_CONFIG_REQUIRED" -> "TN chưa có nguồn dữ liệu trực tiếp cho thông tin này."
            "AI_INVALID_TOOL" -> "AI chưa tạo được thao tác hợp lệ. TN chưa thực hiện thao tác."
            "FEATURE_NOT_AVAILABLE" -> "Tính năng này chưa thuộc quyền sử dụng của tài khoản hiện tại."
            "INVALID_TIMEZONE" -> "Múi giờ thiết bị chưa hợp lệ. Kiểm tra cài đặt ngày giờ rồi thử lại."
            "CONFIRMATION_EXPIRED", "CONFIRMATION_USED", "CONFIRMATION_RETIRED" -> "Xác nhận đã hết hiệu lực. Vui lòng gửi lại yêu cầu."
            else -> null
        }
    }
}

/** Only an opaque context label is kept in RAM. The server owns mode and authorization. */
internal class TnAssistantConversation(
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val identifier: () -> String = { UUID.randomUUID().toString() },
) {
    private var owner: Long? = null
    private var device = ""
    private var conversation = ""
    private var lastUse = 0L
    private var latestRequest = ""

    @Synchronized fun request(text: String, requestId: String, deviceId: String, generation: Long,
        active: () -> Boolean): JSONObject {
        if (!active()) throw IOException("Lượt trợ lý đã bị hủy hoặc tài khoản đã thay đổi.")
        val current = clock()
        if (owner != generation || device != deviceId || conversation.isBlank() || current - lastUse >= CONTEXT_IDLE_MS) {
            conversation = identifier()
            owner = generation
            device = deviceId
        }
        lastUse = current
        latestRequest = requestId
        // Omitting mode lets Basic stay commands-only while Premium/Admin use
        // their server-side preference. No client entitlement can enable AI.
        return JSONObject().put("text", text).put("request_id", requestId).put("device_id", deviceId)
            .put("conversation_id", conversation)
            .put("timezone", java.util.TimeZone.getDefault().id)
            .put("client_capabilities", JSONObject().put("basic_alerts", true))
    }

    @Synchronized fun retire(generation: Long) {
        if (owner != generation) return
        owner = null
        device = ""
        conversation = ""
        lastUse = 0
        latestRequest = ""
    }

    @Synchronized fun ownsTurn(generation: Long, requestId: String, conversationId: String): Boolean =
        owner == generation && latestRequest == requestId && conversation == conversationId

    companion object {
        private const val CONTEXT_IDLE_MS = 30 * 60 * 1000L
        val shared = TnAssistantConversation()
    }
}
