package vn.tn.assistant.actions

import android.net.Uri
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/** Resolves the Original app's configured YouTube API and HTML search payloads. */
internal class TnYouTubeSearchResolver(private val exchange: (Request) -> String?) {
    fun resolve(payload: JSONObject): Uri? = runCatching {
        when {
            payload.has("youtube_api") -> resolveApi(payload, payload.optJSONObject("youtube_api") ?: return null)
            payload.has("query_html") -> resolveHtml(payload, payload.optJSONObject("query_html") ?: return null)
            else -> null
        }
    }.getOrNull()

    private fun resolveApi(payload: JSONObject, config: JSONObject): Uri? {
        val query = query(payload) ?: return null
        val type = config.optString("type", "youtube").trim().lowercase()
        if (type !in setOf("youtube", "kiki")) return null

        var clientVersion = ORIGINAL_CLIENT_VERSION
        val configuredKey = config.optString("api_key").trim().takeIf(API_KEY::matches)
        var endpoint = config.optString("url").trim()
        if (endpoint.isEmpty() && type == "youtube") {
            val bootstrap = bootstrap()
            val apiKey = bootstrap?.first?.takeIf(API_KEY::matches) ?: configuredKey ?: return null
            clientVersion = bootstrap?.second?.takeIf(String::isNotBlank) ?: clientVersion
            endpoint = requireNotNull("https://www.youtube.com/youtubei/v1/search".toHttpUrlOrNull())
                .newBuilder().addQueryParameter("key", apiKey)
                .addQueryParameter("prettyPrint", "false").build().toString()
        }
        val url = httpsUrl(endpoint) ?: return null
        val requestBody = config.optString("requestBody").let { template ->
            if (template.isEmpty()) defaultBody(query, clientVersion) else template.replace(SEARCH_PLACEHOLDER, query)
        }
        if (requestBody.length > MAX_REQUEST_BODY_CHARS) return null

        val filters = filters(config.optJSONObject("filters"))
        repeat(API_ATTEMPTS) {
            val builder = Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("Accept-Language", "VN")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            if (!applyHeaders(builder, config.optJSONArray("requestHeader"))) return null
            val text = exchange(builder.build()) ?: return@repeat
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return@repeat
            val video = if (type == "kiki") parseKikiVideo(json, filters) else findVideo(json, filters)
            if (video != null) return videoUri(video.watchUrl, video.id)
        }
        return null
    }

    private fun resolveHtml(payload: JSONObject, config: JSONObject): Uri? {
        val type = config.optString("offline_type").trim()
        return if (type.equals("youtube", ignoreCase = true)) {
            resolveYouTubeHtml(payload, config)
        } else {
            resolveGoogleHtml(payload, config)
        }
    }

    private fun resolveYouTubeHtml(payload: JSONObject, config: JSONObject): Uri? {
        val search = config.optString("search_query").trim().ifEmpty { query(payload).orEmpty() }
        if (validQuery(search) == null) return null
        val url = httpsUrl("https://www.youtube.com/results?search_query=${encode(search)}") ?: return null
        val request = Request.Builder().url(url)
            .header("Origin", "https://youtube.com")
            .header("Referer", "https://youtube.com")
            .get().build()
        val html = exchange(request) ?: return null
        if (html.length > MAX_RESPONSE_CHARS) return null

        val patternText = config.optString("selector").trim().ifEmpty { YOUTUBE_INITIAL_DATA_PATTERN }
        if (patternText.length > MAX_SELECTOR_CHARS) return null
        val pattern = runCatching { Pattern.compile(patternText, Pattern.DOTALL) }.getOrNull() ?: return null
        val matcher = pattern.matcher(html)
        var initialData: String? = null
        while (matcher.find()) initialData = matcher.group(0)
        val json = initialData?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return findVideo(json, filters(payload.optJSONObject("filters")))?.let { videoUri(it.watchUrl, it.id) }
    }

    private fun resolveGoogleHtml(payload: JSONObject, config: JSONObject): Uri? {
        val query = query(payload) ?: return null
        val searchPrefix = config.optString("search_url").trim()
        val sitePrefix = config.optString("site_prefix").trim()
        val googleBase = config.optString("google_url").trim().trimEnd('/')
        val selector = config.optString("selector").trim()
        if (searchPrefix.length > MAX_URL_CHARS || selector.isEmpty() || selector.length > MAX_SELECTOR_CHARS) return null
        if (httpsUrl(googleBase) == null) return null
        val searchUrl = httpsUrl(searchPrefix + encode("$sitePrefix $query")) ?: return null
        val request = Request.Builder().url(searchUrl)
            .header("User-Agent", ORIGINAL_SEARCH_USER_AGENT).get().build()
        val html = exchange(request) ?: return null
        if (html.length > MAX_RESPONSE_CHARS) return null

        val href = runCatching { Jsoup.parse(html).select(selector).first()?.attr("href") }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: return null
        val redirect = if (href.startsWith("https://", ignoreCase = true)) href else googleBase + href
        val googleUrl = httpsUrl(redirect) ?: return null
        val result = googleUrl.queryParameter("q") ?: return null
        return youtubeVideoUrl(result)
    }

    private fun bootstrap(): Pair<String, String>? {
        val url = httpsUrl("https://www.youtube.com/") ?: return null
        val html = exchange(Request.Builder().url(url).header("User-Agent", ORIGINAL_SEARCH_USER_AGENT).get().build())
            ?: return null
        if (html.length > MAX_RESPONSE_CHARS) return null
        val key = API_KEY_PATTERN.find(html)?.groupValues?.getOrNull(1).orEmpty()
        val version = CLIENT_VERSION_PATTERN.find(html)?.groupValues?.getOrNull(1).orEmpty()
        return key.takeIf(String::isNotBlank)?.let { it to version }
    }

    private fun applyHeaders(builder: Request.Builder, headers: JSONArray?): Boolean {
        if (headers == null) return true
        if (headers.length() > MAX_HEADERS) return false
        for (index in 0 until headers.length()) {
            val item = headers.optJSONObject(index) ?: continue
            val key = item.optString("key").trim()
            val value = item.optString("value")
            if (key.isEmpty() || key.length > 128 || !HEADER_NAME.matches(key) ||
                value.length > MAX_HEADER_VALUE_CHARS || value.any { it == '\r' || it == '\n' || it == '\u0000' }) return false
            builder.header(key, value)
        }
        return true
    }

    private fun findVideo(root: JSONObject, filters: Filters): Video? {
        val nodes = intArrayOf(0)
        return findVideoNode(root, filters, 0, nodes)
    }

    private fun findVideoNode(value: Any?, filters: Filters, depth: Int, nodes: IntArray): Video? {
        if (value == null || depth > MAX_JSON_DEPTH || ++nodes[0] > MAX_JSON_NODES) return null
        when (value) {
            is JSONObject -> {
                for (key in VIDEO_RENDERER_KEYS) {
                    val renderer = value.optJSONObject(key) ?: continue
                    val candidate = videoFromRenderer(renderer)
                    if (candidate != null && matches(candidate, filters)) return candidate
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val candidate = findVideoNode(value.opt(keys.next()), filters, depth + 1, nodes)
                    if (candidate != null) return candidate
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val candidate = findVideoNode(value.opt(index), filters, depth + 1, nodes)
                if (candidate != null) return candidate
            }
        }
        return null
    }

    private fun videoFromRenderer(renderer: JSONObject): Video? {
        val id = renderer.optString("videoId").takeIf(VIDEO_ID::matches) ?: return null
        val watch = renderer.optString("watchUrl").takeIf(String::isNotBlank)
        val live = renderer.optBoolean("isLive", false) || renderer.optBoolean("yt_is_live", false) ||
            rendererHasLiveBadge(renderer)
        val short = renderer.optBoolean("yt_is_short_video", false) || rendererIsShort(renderer)
        val durationText = renderer.optString("yt_length_text").ifEmpty {
            renderer.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
        }
        return Video(id, watch, live, short, parseDuration(durationText))
    }

    private fun parseKikiVideo(root: JSONObject, filters: Filters): Video? {
        if (root.optInt("status", -1) != 0) return null
        val renderer = root.optJSONObject("data")?.optJSONObject("yt_video") ?: return null
        val id = renderer.optString("id").takeIf(VIDEO_ID::matches) ?: return null
        val candidate = Video(
            id = id,
            watchUrl = renderer.optString("yt_watch_url").takeIf(String::isNotBlank),
            isLive = renderer.optBoolean("yt_is_live", false),
            isShort = renderer.optBoolean("yt_is_short_video", false),
            durationSeconds = parseDuration(renderer.optString("yt_length_text")),
        )
        return candidate.takeIf { matches(it, filters) }
    }

    private fun rendererHasLiveBadge(renderer: JSONObject): Boolean {
        fun arrayHasLive(array: JSONArray?): Boolean {
            if (array == null) return false
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val badge = item.optJSONObject("metadataBadgeRenderer")
                if (badge?.optString("style") == "BADGE_STYLE_TYPE_LIVE_NOW" ||
                    badge?.optString("label").equals("LIVE", ignoreCase = true)) return true
                val overlay = item.optJSONObject("thumbnailOverlayTimeStatusRenderer")
                if (overlay?.optString("style") == "LIVE") return true
            }
            return false
        }
        return arrayHasLive(renderer.optJSONArray("badges")) || arrayHasLive(renderer.optJSONArray("thumbnailOverlays"))
    }

    private fun rendererIsShort(renderer: JSONObject): Boolean {
        val endpoint = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
        return endpoint.startsWith("/shorts/")
    }

    private fun matches(video: Video, filters: Filters): Boolean {
        if (filters.liveStatus == "is_live" && !video.isLive) return false
        if (filters.liveStatus == "not_live" && video.isLive) return false
        if (filters.shortVideo != null && filters.shortVideo != video.isShort) return false
        if (filters.minimumDurationSeconds > 0 &&
            (video.durationSeconds == null || video.durationSeconds < filters.minimumDurationSeconds)) return false
        return true
    }

    private fun videoUri(watchUrl: String?, id: String): Uri? = watchUrl?.let(::youtubeVideoUrl)
        ?: Uri.Builder().scheme("https").authority("www.youtube.com").path("/watch")
            .appendQueryParameter("v", id).build()

    private fun youtubeVideoUrl(value: String): Uri? {
        val url = httpsUrl(value) ?: return null
        val host = url.host.lowercase()
        if (!(host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com"))) return null
        val path = url.encodedPathSegments
        val id = when {
            host == "youtu.be" -> path.firstOrNull()
            url.encodedPath == "/watch" -> url.queryParameter("v")
            path.size >= 2 && path.first() in setOf("shorts", "live", "embed", "v") -> path[1]
            else -> null
        }
        if (id == null || !VIDEO_ID.matches(id)) return null
        return Uri.parse(url.toString())
    }

    private fun filters(json: JSONObject?): Filters = Filters(
        liveStatus = json?.optString("live_status")?.trim()?.lowercase().orEmpty(),
        shortVideo = json?.takeIf { it.has("short_video") }?.optBoolean("short_video"),
        minimumDurationSeconds = json?.optInt("min_video_duration_in_second", -1) ?: -1,
    )

    private fun query(payload: JSONObject): String? = validQuery(
        payload.optString("query").trim().ifEmpty { payload.optString("search").trim() },
    )

    private fun validQuery(value: String): String? = value.takeIf {
        it.isNotBlank() && it.length <= MAX_QUERY_CHARS && it.none(Char::isISOControl)
    }

    private fun httpsUrl(value: String): HttpUrl? {
        if (value.isBlank() || value.length > MAX_URL_CHARS || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val url = value.toHttpUrlOrNull() ?: return null
        return url.takeIf { it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() && it.port == 443 }
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun defaultBody(query: String, clientVersion: String): String = JSONObject()
        .put("context", JSONObject()
            .put("client", JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", clientVersion)
                .put("platform", "MOBILE")
                .put("osName", "Android")
                .put("osVersion", "13")
                .put("androidSdkVersion", 32)
                .put("hl", "vi")
                .put("gl", "VN"))
            .put("user", JSONObject().put("lockedSafetyMode", false)))
        .put("query", query).toString()

    private fun parseDuration(value: String): Int? {
        val parts = value.trim().split(':').map { it.toIntOrNull() ?: return null }
        if (parts.isEmpty() || parts.size > 3 || parts.any { it < 0 }) return null
        return parts.fold(0) { total, part -> total * 60 + part }
    }

    private data class Filters(val liveStatus: String, val shortVideo: Boolean?, val minimumDurationSeconds: Int)
    private data class Video(val id: String, val watchUrl: String?, val isLive: Boolean, val isShort: Boolean, val durationSeconds: Int?)

    companion object {
        private const val SEARCH_PLACEHOLDER = "{replace-text-search}"
        private const val MAX_QUERY_CHARS = 200
        private const val MAX_URL_CHARS = 2048
        private const val MAX_SELECTOR_CHARS = 512
        private const val MAX_HEADER_VALUE_CHARS = 4096
        private const val MAX_HEADERS = 24
        private const val MAX_REQUEST_BODY_CHARS = 64 * 1024
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_RESPONSE_CHARS = MAX_RESPONSE_BYTES
        private const val MAX_JSON_DEPTH = 48
        private const val MAX_JSON_NODES = 100_000
        private const val API_ATTEMPTS = 2
        private const val ORIGINAL_CLIENT_VERSION = "17.31.35"
        private const val YOUTUBE_INITIAL_DATA_PATTERN = "(?<=var ytInitialData = ).*?(?=;</script>)"
        private const val ORIGINAL_SEARCH_USER_AGENT = "Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.9.0.7) Gecko/2009021910 Firefox/3.0.7"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private val API_KEY = Regex("^[A-Za-z0-9_-]{1,256}$")
        private val VIDEO_RENDERER_KEYS = listOf("compactVideoRenderer", "videoRenderer")
        private val API_KEY_PATTERN = Regex("INNERTUBE_API_KEY\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]+)\\\"")
        private val CLIENT_VERSION_PATTERN = Regex("INNERTUBE_CONTEXT_CLIENT_VERSION\\\"\\s*:\\s*\\\"([0-9.]+)\\\"")
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val production: TnYouTubeSearchResolver by lazy {
            TnYouTubeSearchResolver { request ->
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    if (body.contentLength() > MAX_RESPONSE_BYTES) return@use null
                    val buffer = Buffer()
                    val source = body.source()
                    while (!source.exhausted()) {
                        val remaining = MAX_RESPONSE_BYTES.toLong() + 1 - buffer.size
                        if (remaining <= 0) return@use null
                        source.read(buffer, minOf(8192L, remaining))
                    }
                    if (buffer.size > MAX_RESPONSE_BYTES) null else buffer.readUtf8()
                }
            }
        }
    }
}
