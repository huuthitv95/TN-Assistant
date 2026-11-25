package vn.tn.assistant.api

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

private val timestamp = Regex("""([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:[.,]([0-9]+))?(Z|[+-][0-9]{2}:[0-9]{2})""")

/** Parse Go RFC3339Nano on API 21 and newer, retaining millisecond precision. */
internal fun serverTimeMillis(value: String): Long? {
    val match = timestamp.matchEntire(value) ?: return null
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    val hour = match.groupValues[4].toInt()
    val minute = match.groupValues[5].toInt()
    val second = match.groupValues[6].toInt()
    val fraction = match.groupValues[7]
    val offset = match.groupValues[8]
    val offsetMinutes = if (offset == "Z") 0 else {
        val hours = offset.substring(1, 3).toInt()
        val minutes = offset.substring(4, 6).toInt()
        if (hours > 23 || minutes > 59) return null
        (hours * 60 + minutes) * if (offset[0] == '-') -1 else 1
    }
    return try {
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            // Go uses a proleptic Gregorian calendar; java.util otherwise switches to Julian dates in 1582.
            gregorianChange = java.util.Date(Long.MIN_VALUE)
            isLenient = false
            clear()
            set(Calendar.ERA, if (year == 0) GregorianCalendar.BC else GregorianCalendar.AD)
            set(Calendar.YEAR, if (year == 0) 1 else year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, fraction.take(3).padEnd(3, '0').toIntOrNull() ?: 0)
        }
        calendar.timeInMillis - offsetMinutes * 60_000L
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}
