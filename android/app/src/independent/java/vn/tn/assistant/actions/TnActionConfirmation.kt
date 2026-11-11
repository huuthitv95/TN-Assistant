package vn.tn.assistant.actions

import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import vn.tn.assistant.api.TnAssistantConversation
import java.util.UUID

/** Short-lived consent proposals, never executable directives or persisted tokens. */
internal class TnActionConfirmation(
    private val conversation: TnAssistantConversation = TnAssistantConversation.shared,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    internal data class Pending(val owner: Long, val requestId: String, val conversationId: String,
        val token: String, val summary: String, val deadline: Long, val mode: String = "tools")
    private var pending: Pending? = null

    @Synchronized fun offer(response: JSONObject, owner: Long, active: () -> Boolean): Boolean {
        if (!active() || response.optBoolean("replayed") || !response.optBoolean("confirmation_required")) return false
        val request = response.optString("request_id")
        val context = response.optString("conversation_id")
        if (!conversation.ownsTurn(owner, request, context)) return false
        val token = response.optString("confirmation_token")
        val preview = response.optJSONObject("confirmation") ?: return false
        val summary = preview.optString("summary").trim()
        val expiry = preview.opt("expires_in_ms")
        val mode = preview.optString("mode", "tools")
        if ((preview.has("mode") && preview.opt("mode") !is String) || mode !in setOf("commands", "tools") ||
            (mode == "commands" && preview.optString("type") !in setOf("PhoneCall", "Alerts"))) return false
        if (!token.matches(Regex("[a-zA-Z0-9_.-]{1,100}")) || preview.optString("type") !in setOf("PhoneCall", "Alerts") ||
            summary.isBlank() || summary.length > 1500 || expiry !is Number || expiry.toDouble() != expiry.toLong().toDouble() ||
            expiry.toLong() !in 1L..120000L) return false
        pending = Pending(owner, request, context, token, summary, clock() + expiry.toLong(), mode)
        return true
    }

    @Synchronized fun current(owner: Long): Pending? {
        val value = pending ?: return null
        if (clock() >= value.deadline || !conversation.ownsTurn(value.owner, value.requestId, value.conversationId)) {
            pending = null
            return null
        }
        return value.takeIf { it.owner == owner }
    }

    @Synchronized fun dismiss(value: Pending) { if (pending === value) pending = null }

    @Synchronized fun consume(value: Pending, device: String, active: () -> Boolean): JSONObject? {
        if (pending !== value || !active() || current(value.owner) !== value) return null
        pending = null // One click, one request. Never auto-retry an ambiguous confirmation.
        return conversation.request("Xác nhận", UUID.randomUUID().toString(), device, value.owner, active)
            .put("mode", value.mode).put("conversation_id", value.conversationId).put("confirmation_token", value.token)
    }

    companion object {
        val shared = TnActionConfirmation()
        fun speechOnly(response: JSONObject): JSONObject {
            val input = response.optJSONArray("directives") ?: JSONArray()
            val output = JSONArray()
            for (index in 0 until input.length()) input.optJSONObject(index)?.let {
                if (it.optString("type") == "SpeechSynthesizer") output.put(it)
            }
            return JSONObject(response.toString()).put("directives", output)
        }
    }
}
