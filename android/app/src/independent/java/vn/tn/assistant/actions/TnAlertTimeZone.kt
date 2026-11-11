package vn.tn.assistant.actions

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/** Reject a stale wall-clock proposal after a timezone change; old metadata remains supported. */
internal object TnAlertTimeZone {
    fun requireCompatible(payload: JSONObject, date: Calendar) {
        if (!payload.has("timezone") && !payload.has("utc_offset_seconds")) return
        val zone = payload.opt("timezone")
        require(zone is String && zone.isNotEmpty() && zone.length <= 80)
        val requested = TimeZone.getTimeZone(zone)
        // TimeZone silently substitutes GMT for unknown identifiers.
        require(requested.id == zone)
        if (requested.id != date.timeZone.id) {
            require(!requested.useDaylightTime() && !date.timeZone.useDaylightTime() &&
                requested.rawOffset == date.timeZone.rawOffset)
        }
        val offset = payload.opt("utc_offset_seconds")
        require(offset is Number && offset.toDouble().isFinite() &&
            offset.toDouble() == offset.toInt().toDouble() && offset.toInt() in -50400..50400)
        val expected = offset.toInt() * 1000
        require(requested.getOffset(date.timeInMillis) == expected)
        require(date.timeZone.getOffset(date.timeInMillis) == expected)
    }
}
