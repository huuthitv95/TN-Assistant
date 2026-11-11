package vn.tn.assistant.actions

import android.provider.AlarmClock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TnAlertTimezoneRuntimeTest {
    private fun now(zone: String) = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
        clear(); set(2026, Calendar.NOVEMBER, 10, 5, 0, 0)
    }

    private fun proposal(type: String = "Alarm") = JSONObject()
        .put("type", type).put("year", 2026).put("month", 11).put("day", 10)
        .put("hour", 7).put("minute", 0).put("repeatType", "None").put("task", "TN timezone QA")
        .put("timezone", "Asia/Ho_Chi_Minh").put("utc_offset_seconds", 25200)

    @Test fun equivalentTimezoneAliasesPreserveTheConfirmedLocalClock() {
        val intent = requireNotNull(TnAlarmControls.intent(proposal(), now("Asia/Bangkok")))
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(7, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        val event = TnCalendarControls.event(proposal("Reminder"), now("Asia/Bangkok"))
        assertEquals(7, Calendar.getInstance(TimeZone.getTimeZone("Asia/Bangkok")).apply { timeInMillis = event.start }.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun changingTimezoneAfterConfirmationCannotCreateAnAlarmOrReminder() {
        reject { TnAlarmControls.intent(proposal(), now("America/New_York")) }
        reject { TnCalendarControls.event(proposal("Reminder"), now("America/New_York")) }
        reject { TnAlarmControls.intent(proposal("DismissAlarm").put("searchMode", "time"), now("America/New_York")) }
    }

    @Test fun unknownPartialOrInconsistentTimezoneMetadataIsRejected() {
        reject { TnAlarmControls.intent(proposal().put("timezone", "Unknown/Zone").put("utc_offset_seconds", 0), now("UTC")) }
        reject { TnAlarmControls.intent(proposal().put("utc_offset_seconds", 0), now("Asia/Bangkok")) }
        reject { TnAlarmControls.intent(proposal().put("utc_offset_seconds", 25200.5), now("Asia/Bangkok")) }
        reject { TnAlarmControls.intent(proposal().apply { remove("utc_offset_seconds") }, now("Asia/Bangkok")) }
    }

    @Test fun equalCurrentOffsetsWithDifferentFutureDSTRulesCannotChangeARecurringProposal() {
        val requested = proposal().put("timezone", "America/New_York").put("utc_offset_seconds", -18000)
            .put("repeatType", "Daily")
        reject { TnAlarmControls.intent(requested, now("America/Lima")) }
        reject { TnCalendarControls.event(requested.put("type", "Reminder"), now("America/Lima")) }
    }

    @Test fun olderServerMetadataWithoutTimezoneRetainsItsExistingValidation() {
        val old = proposal().apply { remove("timezone"); remove("utc_offset_seconds") }
        assertNotNull(TnAlarmControls.intent(old, now("Asia/Bangkok")))
        assertEquals(7, Calendar.getInstance(TimeZone.getTimeZone("Asia/Bangkok")).apply {
            timeInMillis = TnCalendarControls.event(old.put("type", "Reminder"), now("Asia/Bangkok")).start
        }.get(Calendar.HOUR_OF_DAY))
    }

    private fun reject(action: () -> Any?) {
        try { action() } catch (_: IllegalArgumentException) { return }
        fail("A changed or invalid timezone must not become a device action")
    }
}
