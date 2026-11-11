package vn.tn.assistant.actions

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.provider.CalendarContract as C
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Calendar

/** Direct TN Calendar Provider writes with consent/ownership guards and read-back. */
internal class TnCalendarControls(
    private val context: Context,
    private val active: () -> Boolean,
    private val authorize: () -> Boolean = { hasAccess(context) },
) {
    fun execute(payload: JSONObject, details: JSONObject): String {
        check(Looper.myLooper() != Looper.getMainLooper())
        details.put("completion_verified", false).put("calendar_event_saved", false)
        if (!running()) return "cancelled"
        val event = try { event(payload) } catch (_: IllegalArgumentException) {
            details.put("reason", "invalid_calendar_event"); return "failed"
        }
        if (!hasAccess(context) && !authorize()) {
            details.put("reason", "calendar_permission_required"); return "failed"
        }
        if (!running()) return "cancelled"
        if (!hasAccess(context)) { details.put("reason", "calendar_permission_required"); return "failed" }
        val resolver = context.contentResolver
        val calendar = resolver.query(C.Calendars.CONTENT_URI,
            arrayOf(C.Calendars._ID, C.Calendars.IS_PRIMARY, C.Calendars.MAX_REMINDERS, C.Calendars.ALLOWED_REMINDERS),
            "${C.Calendars.CALENDAR_ACCESS_LEVEL}>=? AND ${C.Calendars.VISIBLE}=1 AND ${C.Calendars.SYNC_EVENTS}=1",
            arrayOf(C.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), "${C.Calendars.IS_PRIMARY} DESC, ${C.Calendars._ID} ASC")?.use { cursor ->
            var candidate: Long? = null
            while (cursor.moveToNext()) {
                candidate = -1L
                val maximum = if (cursor.isNull(2)) 1 else cursor.getInt(2)
                val methods = cursor.getString(3).orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
                if (maximum > 0 && (methods.isEmpty() || C.Reminders.METHOD_ALERT.toString() in methods)) {
                    candidate = cursor.getLong(0)
                    break
                }
            }
            candidate
        }
        if (calendar == null) { details.put("reason", "calendar_no_writable_calendar"); return "failed" }
        if (calendar < 0) { details.put("reason", "calendar_alert_not_supported"); return "failed" }
        if (!running()) return "cancelled"
        // CalendarProvider applies the event and reminder in one transaction.
        // Never retry an ambiguous write or forward an arbitrary provider URI.
        val results = resolver.applyBatch(C.AUTHORITY, operations(event, calendar))
        val uri = results.getOrNull(0)?.uri ?: run { details.put("reason", "calendar_write_unconfirmed"); return "failed" }
        require(uri.scheme == "content" && uri.authority == C.AUTHORITY && uri.pathSegments.firstOrNull() == "events")
        if (!running()) {
            runCatching { resolver.delete(uri, null, null) }
            return "cancelled"
        }
        val stored = resolver.query(uri, arrayOf(C.Events.TITLE, C.Events.DTSTART, C.Events.DTEND, C.Events.DURATION,
            C.Events.EVENT_TIMEZONE, C.Events.RRULE), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == event.title && cursor.getLong(1) == event.start &&
                (if (event.repeat == null) cursor.getLong(2) == event.end else cursor.getString(3) == "PT1H") &&
                cursor.getString(4) == event.timezone && cursor.getString(5) == event.repeat
        } == true
        val reminded = resolver.query(C.Reminders.CONTENT_URI, arrayOf(C.Reminders.MINUTES, C.Reminders.METHOD),
            "${C.Reminders.EVENT_ID}=?", arrayOf(ContentUris.parseId(uri).toString()), null)?.use { cursor ->
            cursor.count == 1 && cursor.moveToFirst() && cursor.getInt(0) == EVENT_TIME_REMINDER_MINUTES && cursor.getInt(1) == C.Reminders.METHOD_ALERT
        } == true
        if (!running()) { runCatching { resolver.delete(uri, null, null) }; return "cancelled" }
        details.put("completion_verified", stored && reminded).put("calendar_event_saved", stored && reminded)
            .put("reason", if (stored && reminded) "calendar_event_and_reminder_verified" else "calendar_write_unconfirmed")
        // Persistence is confirmed, not delivery of an eventual notification.
        return if (stored && reminded) "success" else "failed"
    }

    private fun running() = active() && !Thread.currentThread().isInterrupted
    internal data class Event(val title: String, val start: Long, val end: Long, val timezone: String, val repeat: String?)

    companion object {
        // Original a/a.F used 10000, which puts alerts for next-occurrence events in the past.
        // TN deliberately keeps the same Calendar interaction but makes delivery functional.
        const val EVENT_TIME_REMINDER_MINUTES = 0
        fun hasAccess(context: Context) = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

        fun event(payload: JSONObject, now: Calendar = Calendar.getInstance()): Event {
            require(payload.optString("type") == "Reminder")
            val title = payload.opt("task")
            require(title is String && title.isNotBlank() && title.codePointCount(0, title.length) <= 200)
            fun integer(name: String, range: IntRange): Int {
                val value = payload.opt(name)
                require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble() && value.toInt() in range)
                return (value as Number).toInt()
            }
            val date = (now.clone() as Calendar).apply {
                isLenient = false
                set(Calendar.YEAR, integer("year", 1970..9999)); set(Calendar.MONTH, integer("month", 1..12) - 1)
                set(Calendar.DAY_OF_MONTH, integer("day", 1..31)); set(Calendar.HOUR_OF_DAY, integer("hour", 0..23))
                set(Calendar.MINUTE, integer("minute", 0..59)); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            require(date.timeInMillis > now.timeInMillis)
            TnAlertTimeZone.requireCompatible(payload, date)
            val repeat = when (payload.optString("repeatType", "None")) {
                "None" -> null; "Daily" -> "FREQ=DAILY"; "Weekly" -> "FREQ=WEEKLY"
                "Weekdays" -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"; "Weekend" -> "FREQ=WEEKLY;BYDAY=SA,SU"
                else -> throw IllegalArgumentException("Unknown recurrence")
            }
            return Event(title as String, date.timeInMillis, (date.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 1) }.timeInMillis,
                now.timeZone.id, repeat)
        }

        fun operations(event: Event, calendar: Long): ArrayList<ContentProviderOperation> {
            require(calendar >= 0)
            val insert = ContentProviderOperation.newInsert(C.Events.CONTENT_URI)
                .withValue(C.Events.CALENDAR_ID, calendar).withValue(C.Events.TITLE, event.title)
                .withValue(C.Events.DTSTART, event.start).withValue(C.Events.EVENT_TIMEZONE, event.timezone)
                .withValue(C.Events.STATUS, C.Events.STATUS_CONFIRMED).withValue(C.Events.HAS_ALARM, 1)
            // Recurring Calendar events require DURATION rather than DTEND.
            if (event.repeat == null) insert.withValue(C.Events.DTEND, event.end)
            else insert.withValue(C.Events.RRULE, event.repeat).withValue(C.Events.DURATION, "PT1H")
            return arrayListOf(insert.build(), ContentProviderOperation.newInsert(C.Reminders.CONTENT_URI)
                .withValueBackReference(C.Reminders.EVENT_ID, 0).withValue(C.Reminders.MINUTES, EVENT_TIME_REMINDER_MINUTES)
                .withValue(C.Reminders.METHOD, C.Reminders.METHOD_ALERT).build())
        }
    }
}
