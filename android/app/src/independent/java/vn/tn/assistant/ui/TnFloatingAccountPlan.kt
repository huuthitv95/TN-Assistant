package vn.tn.assistant.ui

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ceil

/** Non-secret display snapshot. Never used to authorize a feature or a payment. */
internal data class TnFloatingAccountPlan(val kind: String, val expires: Long?, val server: Long, val received: Long) {
    fun now(elapsed: Long = SystemClock.elapsedRealtime()) = server + (elapsed - received).coerceAtLeast(0)
    fun effectiveKind(elapsed: Long = SystemClock.elapsedRealtime()): String =
        if (kind == "premium" && (expires == null || expires <= now(elapsed))) "basic" else kind
    fun days(elapsed: Long = SystemClock.elapsedRealtime()): Int =
        ceil(((expires ?: 0) - now(elapsed)).coerceAtLeast(0) / 86400000.0).toInt()
    companion object {
        private val revision = java.util.concurrent.atomic.AtomicLong(0)
        fun generation() = revision.get()
        private const val KEY = "account_plan_display"
        private fun parse(value: String): Long? = runCatching {
            val normalized = value.replace(Regex("\\.(\\d{3})\\d+"), ".$1")
                .replace(Regex("(T\\d{2}:\\d{2}:\\d{2})(Z|[+-]\\d{2}:\\d{2})$"), "$1.000$2")
            val compatible = normalized.replace(Regex("Z$"), "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).apply { isLenient = false }.parse(compatible)?.time
        }.getOrNull()
        fun read(context: Context): TnFloatingAccountPlan? = runCatching {
            val p = JSONObject(TnFloatingControlService.settings(context).getString(KEY, null) ?: return null)
            // Elapsed time belongs to this boot. Never reuse stale account display after reboot.
            if (p.getInt("boot") != android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)) return null
            val received = p.getLong("received")
            if (SystemClock.elapsedRealtime() < received || SystemClock.elapsedRealtime() - received > 300000) return null
            TnFloatingAccountPlan(p.getString("kind"), p.optLong("expires").takeIf { it > 0 }, p.getLong("server"), received)
        }.getOrNull()
        @Synchronized fun update(context: Context, me: JSONObject, expectedGeneration: Long = generation()) {
            if (expectedGeneration != generation() || !vn.tn.assistant.auth.TnSessionStore.get(context).hasSession()) return
            val p = me.optJSONObject("account_plan")
            val kind = p?.optString("kind")
            val server = p?.optString("server_time")?.let(::parse)
            if (kind !in setOf("basic", "premium", "admin") || server == null) { clear(context); return }
            val expires = p.optString("expires_at").let(::parse)
            if (kind == "premium" && expires == null) { clear(context); return }
            val saved = JSONObject().put("kind", kind).put("server", server).put("expires", expires ?: 0)
                .put("received", SystemClock.elapsedRealtime())
                .put("boot", android.provider.Settings.Global.getInt(context.contentResolver,"boot_count",-1))
            TnFloatingControlService.settings(context).edit().putString(KEY,saved.toString()).apply()
        }
        @Synchronized fun clear(context: Context) { revision.incrementAndGet(); TnFloatingControlService.settings(context).edit().remove(KEY).apply() }
    }
}
