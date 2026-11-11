package vn.tn.assistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Builds the original app's direct-link/search/video routes and resolves them
 * through the selected video app, YouTube, then Android's HTTPS handlers. */
internal object TnYouTubeIntentCascade {
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    fun build(
        context: Context,
        payload: JSONObject,
        configuredResolver: (JSONObject) -> Uri? = TnYouTubeSearchResolver.production::resolve,
    ): Intent? {
        val directLink = payload.optString("direct_link").trim()
        val videoId = payload.optString("video_id").trim()
        val query = payload.optString("query").trim().takeIf { it.isNotEmpty() }
            ?: payload.optString("search").trim()
        val needsOriginalResolver = payload.has("youtube_api") || payload.has("query_html")

        val target = when {
            directLink.isNotEmpty() -> validatedHttpsUri(directLink) ?: return null
            payload.optBoolean("youtube_search") -> searchUri(query) ?: return null
            videoId.isNotEmpty() -> {
                if (!videoIdPattern.matches(videoId)) return null
                Uri.Builder().scheme("https").authority("www.youtube.com").path("/watch")
                    .appendQueryParameter("v", videoId).build()
            }
            needsOriginalResolver -> configuredResolver(payload) ?: return null
            query.isNotEmpty() && !needsOriginalResolver -> searchUri(query) ?: return null
            else -> return null
        }

        val selected = context.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
            .getString("video_package", "").orEmpty().trim()
        val packageCascade = listOf(selected, YOUTUBE_PACKAGE).filter(String::isNotBlank).distinct()
        for (packageName in packageCascade) {
            val candidate = viewIntent(target).setPackage(packageName)
            if (candidate.resolveActivity(context.packageManager) != null) return candidate
        }
        val generic = viewIntent(target)
        return generic.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    private fun searchUri(query: String): Uri? {
        if (query.isBlank() || query.length > 200 || query.any { it.isISOControl() }) return null
        return Uri.Builder().scheme("https").authority("www.youtube.com").path("/results")
            .appendQueryParameter("search_query", query).build()
    }

    private fun validatedHttpsUri(value: String): Uri? {
        if (value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return null
        return uri
    }

    private fun viewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
}
