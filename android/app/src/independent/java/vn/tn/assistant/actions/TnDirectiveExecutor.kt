package vn.tn.assistant.actions

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Ordered TN actions shared by foreground and wake; never loads the legacy runtime. */
internal class TnDirectiveExecutor(
    private val active: () -> Boolean,
    private val speech: (String) -> Boolean,
    private val map: (String) -> Boolean,
    private val app: (String) -> Boolean,
    private val volume: Volume,
    private val receipt: (String, String, JSONObject) -> Unit,
    private val media: ((JSONObject, JSONObject) -> String)? = null,
    private val alarm: ((JSONObject, JSONObject) -> String)? = null,
    private val calendar: ((JSONObject, JSONObject) -> String)? = null,
    private val phone: ((JSONObject, JSONObject) -> String)? = null,
    private val youtube: ((JSONObject) -> Boolean)? = null,
) {
    internal interface Volume {
        fun maximum(): Int
        fun current(): Int
        fun set(value: Int)
    }
    internal data class Summary(val failed: Int = 0, val unsupported: Int = 0, val cancelled: Boolean = false, val interactionRequired: Int = 0)

    fun execute(response: JSONObject): Summary {
        if (response.optBoolean("replayed")) return Summary()
        val directives = response.optJSONArray("directives") ?: return Summary()
        require(directives.length() <= 100) { "Quá nhiều hành động trong một lượt lệnh TN" }
        val outcomes = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        var failed = 0
        var unsupported = 0
        var interactionRequired = 0
        for (index in 0 until directives.length()) {
            if (!running()) return Summary(failed, unsupported, true, interactionRequired)
            val directive = directives.optJSONObject(index) ?: continue
            val payload = directive.optJSONObject("payload") ?: JSONObject()
            val type = directive.optString("type")
            val id = directive.optString("action_id").ifBlank { payload.optString("action_id") }
            // A repeated action ID in one response is never executed twice.
            if (id.isNotBlank() && !seen.add(id)) continue
            val required = payload.optJSONArray("required") ?: directive.optJSONArray("required")
            val details = JSONObject().put("directive_type", type).put("intent_started", false)
            val result = try {
                if (!prerequisites(required, outcomes)) {
                    details.put("reason", "prerequisite_not_met")
                    "cancelled"
                } else when (type) {
                    "SpeechSynthesizer" -> success(speech(payload.optString("utter_text")))
                    "MapDirection" -> {
                        val started = map(payload.optString("url"))
                        details.put("intent_started", started)
                        success(started)
                    }
                    "OpenApp" -> {
                        val started = app(payload.optString("package_name"))
                        details.put("intent_started", started)
                        success(started)
                    }
                    "YouTube", "PlayVideoApp" -> {
                        val started = youtube?.invoke(payload)
                        if (started == null) {
                            details.put("reason", "youtube_adapter_unavailable")
                            "unsupported"
                        } else {
                            details.put("intent_started", started)
                            success(started)
                        }
                    }
                    "PlayerVolume" -> changeVolume(payload, details)
                    "PlayerMP3" -> media?.invoke(payload, details) ?: run {
                        details.put("reason", "media_adapter_unavailable"); "unsupported"
                    }
                    "PhoneCall" -> phone?.invoke(payload, details) ?: run {
                        details.put("reason", "call_adapter_unavailable"); "unsupported"
                    }
                    "Alerts" -> (if (payload.optString("type") == "Reminder") calendar else alarm)?.invoke(payload, details) ?: run {
                        details.put("reason", "alarm_adapter_unavailable"); "unsupported"
                    }
                    "Delay" -> {
                        val raw = if (payload.has("duration")) payload.get("duration") else payload.opt("delay") ?: 0
                        require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toLong().toDouble())
                        val duration = (raw as Number).toLong().coerceIn(0, 30000)
                        val end = SystemClock.elapsedRealtime() + duration
                        while (running() && SystemClock.elapsedRealtime() < end) {
                            Thread.sleep(minOf(25L, end - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                        }
                        if (!running()) return Summary(failed, unsupported, true, interactionRequired)
                        "success"
                    }
                    else -> { details.put("reason", "directive_not_implemented"); "unsupported" }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Summary(failed, unsupported, true, interactionRequired)
            } catch (_: Exception) {
                details.put("reason", "android_action_failed")
                "failed"
            }
            if (!running()) return Summary(failed, unsupported, true, interactionRequired)
            if (result == "failed") failed++
            if (result == "unsupported") unsupported++
            if (details.optBoolean("interaction_required")) interactionRequired++
            if (id.isNotBlank()) {
                // Receipt errors stop the batch; do not execute later actions
                // after losing its owner-bound acknowledgement channel.
                receipt(id, result, details)
                outcomes.add("$id|$result")
            }
        }
        return Summary(failed, unsupported, interactionRequired = interactionRequired)
    }

    private fun running() = !Thread.currentThread().isInterrupted && active()
    private fun success(value: Boolean) = if (value) "success" else "failed"
    private fun prerequisites(required: JSONArray?, outcomes: Set<String>): Boolean = required == null ||
        (0 until required.length()).all { required.opt(it) is String && required.getString(it) in outcomes }

    private fun number(payload: JSONObject, name: String): Double {
        val value = payload.get(name)
        require(value is Number && value.toDouble().isFinite()) { "Âm lượng không hợp lệ" }
        return (value as Number).toDouble()
    }

    private fun changeVolume(payload: JSONObject, details: JSONObject): String {
        val max = volume.maximum()
        require(max > 0)
        val before = volume.current()
        val command = payload.optString("command", payload.optString("operator")).lowercase(Locale.ROOT)
        // Original e5/a: relative +/-15%, truncate percent before converting to
        // STREAM_MUSIC steps; j4 classifier distinguishes level and percent.
        val target = when (command) {
            "increase_volume", "up", "increase", "volume_up" -> before + 15L * max / 100
            "decrease_volume", "down", "decrease", "volume_down" -> before - 15L * max / 100
            "mute" -> 0L
            "adjust_volume", "" -> {
                val percent = if (payload.has("adjust_level") || payload.has("adjust_percentage")) {
                    val level = number(payload, "adjust_level")
                    val percentage = number(payload, "adjust_percentage")
                    when {
                        level == -1.0 && percentage != -1.0 -> percentage
                        level != -1.0 && percentage == -1.0 -> level * 10
                        else -> throw IllegalArgumentException("Unknown volume type")
                    }
                } else if (payload.has("volume")) number(payload, "volume") else number(payload, "value")
                percent.coerceIn(0.0, 100.0).toLong() * max / 100
            }
            else -> throw IllegalArgumentException("Unknown volume command")
        }.coerceIn(0, max.toLong()).toInt()
        if (!running()) return "cancelled"
        volume.set(target)
        val observed = volume.current()
        details.put("volume_before", before).put("volume_requested", target)
            .put("volume_observed", observed).put("volume_maximum", max)
        return success(observed == target)
    }

    companion object {
        fun androidVolume(context: Context): Volume {
            val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return object : Volume {
                override fun maximum() = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                override fun current() = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                override fun set(value: Int) = manager.setStreamVolume(AudioManager.STREAM_MUSIC, value,
                    AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND)
            }
        }
    }
}
