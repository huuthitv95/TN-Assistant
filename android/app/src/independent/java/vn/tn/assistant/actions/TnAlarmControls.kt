package vn.tn.assistant.actions

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import org.json.JSONObject
import java.util.Calendar

/** Android Clock contract only. Calendar writes and phone calls use separate adapters. */
internal class TnAlarmControls(
    private val context: Context,
    private val active: () -> Boolean,
    private val launch: (Intent) -> Boolean,
) {
    fun execute(payload: JSONObject, details: JSONObject): String {
        if (!active() || Thread.currentThread().isInterrupted) return "cancelled"
        if (payload.optString("type") == "DismissAlarm" && Build.VERSION.SDK_INT < 23) {
            details.put("reason", "alarm_action_requires_android_6")
            return "unsupported"
        }
        val intent = try { intent(payload) } catch (_: IllegalArgumentException) {
            details.put("reason", "invalid_or_unrepresentable_alarm")
            return "failed"
        } ?: run { details.put("reason", "alert_type_not_implemented"); return "unsupported" }
        if (intent.resolveActivity(context.packageManager) == null) {
            details.put("reason", "clock_application_unavailable")
            return "failed"
        }
        if (!active() || Thread.currentThread().isInterrupted) return "cancelled"
        val started = launch(intent)
        details.put("intent_started", started).put("completion_verified", false)
            .put("interaction_required", started).put("reason", if (started) "clock_request_dispatched" else "clock_request_not_started")
        // Success acknowledges OS dispatch only. Clock owns the persisted alarm.
        return if (started) "success" else "failed"
    }

    companion object {
        fun intent(payload: JSONObject, now: Calendar = Calendar.getInstance()): Intent? {
            val intent = when (payload.optString("type")) {
                "ShowAlarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                "DismissAlarm" -> {
                    if (Build.VERSION.SDK_INT < 23) return null
                    TnAlertTimeZone.requireCompatible(payload, now)
                    val mode = when (payload.optString("searchMode")) {
                        "next" -> AlarmClock.ALARM_SEARCH_MODE_NEXT
                        "time" -> AlarmClock.ALARM_SEARCH_MODE_TIME
                        else -> throw IllegalArgumentException("Unknown search mode")
                    }
                    Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, mode).apply {
                        if (mode == AlarmClock.ALARM_SEARCH_MODE_TIME) {
                            val hour = integer(payload, "hour", 0..23)
                            putExtra(AlarmClock.EXTRA_HOUR, hour % 12)
                            putExtra(AlarmClock.EXTRA_IS_PM, hour >= 12)
                            putExtra(AlarmClock.EXTRA_MINUTES, integer(payload, "minute", 0..59))
                        }
                    }
                }
                "Timer" -> Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, integer(payload, "duration", 1..86400))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                "Alarm" -> {
                    val hour = integer(payload, "hour", 0..23)
                    val minute = integer(payload, "minute", 0..59)
                    val target = (now.clone() as Calendar).apply {
                        isLenient = false
                        set(Calendar.YEAR, integer(payload, "year", 1970..9999))
                        set(Calendar.MONTH, integer(payload, "month", 1..12) - 1)
                        set(Calendar.DAY_OF_MONTH, integer(payload, "day", 1..31))
                        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    require(target.timeInMillis > now.timeInMillis)
                    TnAlertTimeZone.requireCompatible(payload, target)
                    val days = when (payload.optString("repeatType", "None")) {
                        "None" -> arrayListOf()
                        "Daily" -> arrayListOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
                        "Weekly" -> arrayListOf(target.get(Calendar.DAY_OF_WEEK))
                        "Weekdays" -> arrayListOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
                        "Weekend" -> arrayListOf(Calendar.SATURDAY, Calendar.SUNDAY)
                        else -> throw IllegalArgumentException("Unknown repeat type")
                    }
                    val next = (now.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_MONTH, 1)
                        while (days.isNotEmpty() && get(Calendar.DAY_OF_WEEK) !in days) add(Calendar.DAY_OF_MONTH, 1)
                    }
                    // SET_ALARM has no calendar-date extra. Do not silently set
                    // tomorrow's alarm for a request targeting a later date.
                    require(next.get(Calendar.YEAR) == target.get(Calendar.YEAR) && next.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR))
                    Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute).putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                }
                else -> return null
            }
            if (payload.has("message")) {
                val message = payload.get("message")
                require(message is String && message.length <= 200)
                intent.putExtra(AlarmClock.EXTRA_MESSAGE, message as String)
            }
            return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        private fun integer(payload: JSONObject, name: String, range: IntRange): Int {
            val value = payload.opt(name)
            require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble() && value.toInt() in range)
            return (value as Number).toInt()
        }
    }
}
