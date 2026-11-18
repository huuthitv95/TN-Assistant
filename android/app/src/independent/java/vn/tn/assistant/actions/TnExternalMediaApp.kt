package vn.tn.assistant.actions

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * PlayMusicApp/PlayRadioApp/PlayTVApp arrive from the server without a TN
 * catalogue, provider account or channel/deeplink contract. TN therefore only
 * opens the app the account selected in Settings and reports that the intent
 * started; it never claims a song, station or channel is resolved or playing.
 */
internal object TnExternalMediaApp {
    private const val ORIGINAL_APP_PACKAGE = "ai.zalo.kiki.car"
    private const val PREFS = "tn-session-v1"
    private val validPackage = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val kinds = mapOf("PlayMusicApp" to "music", "PlayRadioApp" to "radio", "PlayTVApp" to "tv")

    // Only the account setting TN itself writes for this directive is read;
    // nothing is inferred from installed apps, the source app or the query.
    fun preferenceKey(type: String): String? = kinds[type]?.let { "${it}_package" }

    /** Settings store a package name only; anything else is ignored, never guessed. */
    fun normalizePackage(value: String?): String? {
        val packageName = value?.trim().orEmpty()
        if (!validPackage.matches(packageName) || packageName == ORIGINAL_APP_PACKAGE) return null
        return packageName
    }

    fun execute(
        context: Context,
        type: String,
        payload: JSONObject,
        details: JSONObject,
        launch: (Intent) -> Boolean,
    ): String {
        val kind = kinds[type] ?: return outcome(details, "unsupported", "media_app_type_unknown")
        val key = "${kind}_package"
        details.put("media_app_kind", kind).put("media_app_setting", key)
            // The query is user content; receipts record only that one was sent.
            .put("query_present", payload.optString("query").isNotBlank())
        val selected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
        if (selected.isNullOrBlank()) return outcome(details, "failed", "media_app_not_configured")
        val packageName = normalizePackage(selected)
            ?: return outcome(details, "failed", "media_app_invalid_package")
        if (packageName == context.packageName) return outcome(details, "failed", "media_app_invalid_package")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return outcome(details, "failed", "media_app_unavailable")
        details.put("media_app_package", packageName)
        if (!launch(intent.setPackage(packageName))) return outcome(details, "failed", "media_app_launch_refused")
        // Dispatch only: the target app owns playback, so the user is asked to
        // check it rather than being told the requested content is playing.
        details.put("completion_verified", false).put("interaction_required", true)
        return outcome(details, "success", "media_app_opened", started = true)
    }

    private fun outcome(details: JSONObject, result: String, reason: String, started: Boolean = false): String {
        details.put("reason", reason).put("intent_started", started)
        return result
    }
}
