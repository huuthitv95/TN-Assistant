package vn.tn.assistant.actions

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.CalendarContract as C
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import vn.tn.assistant.ui.TnIsolatedActivityPreferences
import java.io.File
import java.net.Socket
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in: real VPS staging HTTP through adb/SSH, real Android persistence and receipts. */
class TnJoinedAlertsVpsRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver

    private fun relay(method: String, path: String, body: JSONObject? = null, authorization: String = ""): JSONObject {
        val request = JSONObject().put("method", method).put("path", path).put("authorization", authorization)
        if (body != null) request.put("body", body)
        Socket("127.0.0.1", 18185).use { socket ->
            socket.soTimeout = 30000
            socket.getOutputStream().write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
            require(response != null && response.length < 300000)
            return JSONObject(response)
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun sql(statement: String): String {
        require(!statement.contains('"') && !statement.contains('$') && !statement.contains('`') && !statement.contains('\n'))
        return shell("su 0 sqlite3 /data/user_de/0/com.google.android.deskclock/databases/alarms.db " + statement.replace(" ", "/**/"))
    }

    private fun alarmDigest() = java.security.MessageDigest.getInstance("SHA-256").digest(sql(
        "SELECT _id,external_uuid,hour,minutes,daysofweek,enabled,vibrate,hex(label),hex(ringtone),delete_after_use,hex(workflow_label),hex(workflow_data) FROM alarm_templates ORDER BY _id;").toByteArray()).toList()

    private val clockPreferences = "/data/user_de/0/com.google.android.deskclock/shared_prefs/com.google.android.deskclock_preferences.xml"
    private fun clockPreferencesText() = shell("su 0 cat $clockPreferences")
    private fun timerIds(preferences: String): List<String> {
        val block = Regex("""<set name="timers_list">([\s\S]*?)</set>""").find(preferences)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<string>(\d+)</string>""").findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun count(uri: android.net.Uri) = requireNotNull(resolver.query(uri, arrayOf("_id"), null, null, null)).use { it.count }
    private fun texts(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()

    @Test fun realStagedBackendConfirmedTimerAlarmDismissAndReminderPersistAndAcknowledgeWithoutProductionWrites() {
        assertEquals("TNUpdate190", shell("getprop ro.kernel.qemu.avd_name"))
        assertTrue(android.os.Build.FINGERPRINT.contains("generic"))
        val verifyTimerDelivery = InstrumentationRegistry.getArguments().getString("verifyTimerDelivery") == "true"
        val verifyAlarmDelivery = InstrumentationRegistry.getArguments().getString("verifyAlarmDelivery") == "true"
        val verifyReminderDelivery = InstrumentationRegistry.getArguments().getString("verifyReminderDelivery") == "true"
        val timerDurationSeconds = if (verifyTimerDelivery) 60 else 420
        assertTrue("Grant only the dedicated temporary package Calendar access before this opt-in test", TnCalendarControls.hasAccess(context))
        val fixture = "TN_JOINED_" + UUID.randomUUID().toString().replace("-", "")
        shell("am start -a android.intent.action.SHOW_ALARMS -p com.google.android.deskclock")
        assertEquals("Use an isolated Clock with no enabled alarms", "0", sql("SELECT count(*) FROM alarm_templates WHERE enabled=1;"))
        assertEquals("Use an isolated empty Calendar provider", 0, count(C.Calendars.CONTENT_URI))
        val baseline = alarmDigest()
        shell("am force-stop com.google.android.deskclock")
        val baselineTimerPreferences = clockPreferencesText()
        assertTrue("Use an isolated Clock with no retained timer", timerIds(baselineTimerPreferences).isEmpty())
        val timerBackup = "/data/local/tmp/${fixture}_clock_preferences.xml"
        val clockOwner = shell("su 0 stat -c %u:%g $clockPreferences")
        assertTrue("Clock preference owner must be numeric", Regex("""\d+:\d+""").matches(clockOwner))
        var timerBackupCreated = false
        val beforeEvents = count(C.Events.CONTENT_URI)
        val beforeReminders = count(C.Reminders.CONTENT_URI)
        var calendarUri: android.net.Uri? = null
        var alarmId: Long? = null
        var ownedAlarmTime: Pair<Int, Int>? = null
        var originalFailure: Throwable? = null
        var activity: MainActivity? = null
        var session: JSONObject? = null
        var client: okhttp3.OkHttpClient? = null
        val receipts = LinkedBlockingQueue<JSONObject>()
        val approved = AtomicReference<JSONObject>()
        val evidence = JSONObject().put("measurement", "real VPS isolated185 HTTP over adb/SSH; real MainActivity confirmation/executor and Android persistence")
            .put("production_backend_used", false).put("tts_provider_calls", 0)
        val results = JSONArray()
        val isolation = TnIsolatedActivityPreferences(context)
        try {
            shell("su 0 cp $clockPreferences $timerBackup")
            timerBackupCreated = true
            assertTrue(shell("su 0 sha256sum $timerBackup").endsWith(timerBackup))
            val guest = relay("POST", "/v1/auth/guest", JSONObject().put("device_id", fixture).put("device_name", "Dedicated joined acceptance AVD"))
            assertEquals(200, guest.getInt("status"))
            session = JSONObject(guest.getString("body"))
            val token = session.getString("access_token")
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            client = okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(35, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    val body = if (buffer.size > 0) JSONObject(buffer.readUtf8()) else null
                    val response = relay(request.method, request.url.encodedPath, body, request.header("Authorization").orEmpty())
                    if (request.url.encodedPath == "/v1/assistant/text" && body?.has("confirmation_token") == true && response.getInt("status") == 200) {
                        approved.set(JSONObject(response.getString("body")))
                    }
                    if (request.url.encodedPath == "/v1/device/actions/results") {
                        receipts.put(JSONObject().put("request", body).put("http", response.getInt("status"))
                            .put("ack", JSONObject(response.getString("body"))))
                    }
                    okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(response.getInt("status"))
                        .message("isolated VPS test relay").body(response.getString("body").toResponseBody("application/json".toMediaType())).build()
                }.build()
            MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, client)
            TnSessionStore.get(context).accept(session)
            val dialogField = MainActivity::class.java.getDeclaredField("actionConfirmation").apply { isAccessible = true }
            val send = MainActivity::class.java.getDeclaredMethod("sendCommand", String::class.java).apply { isAccessible = true }
            fun dialog(): android.app.Dialog {
                val deadline = SystemClock.elapsedRealtime() + 30000
                while (SystemClock.elapsedRealtime() < deadline) {
                    val found = AtomicReference<android.app.Dialog>()
                    instrumentation.runOnMainSync { (dialogField.get(activity) as? android.app.Dialog)?.takeIf { it.isShowing }?.let(found::set) }
                    found.get()?.let { return it }
                    Thread.sleep(100)
                }
                error("Real staging proposal did not reach a visible MainActivity confirmation")
            }
            fun confirm(command: String, beforeConsent: () -> Unit): Pair<JSONObject, JSONObject> {
                approved.set(null)
                receipts.clear()
                instrumentation.runOnMainSync { send.invoke(activity, command) }
                val window = dialog()
                beforeConsent()
                instrumentation.runOnMainSync { texts(window.window!!.decorView).single { it.text.toString() == "XÁC NHẬN" }.performClick() }
                val end = SystemClock.elapsedRealtime() + 30000
                while (approved.get() == null && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
                val response = requireNotNull(approved.get())
                val directive = response.getJSONArray("directives").getJSONObject(0)
                // The excluded TTS directive also has its own server-issued ID
                // and failure receipt. Match the approved action, not queue order.
                val receiptDeadline = SystemClock.elapsedRealtime() + 25000
                var matched: JSONObject? = null
                while (matched == null && SystemClock.elapsedRealtime() < receiptDeadline) {
                    val candidate = receipts.poll(250, TimeUnit.MILLISECONDS)
                    if (candidate?.getJSONObject("request")?.optString("action_id") == directive.getString("action_id")) matched = candidate
                }
                val receipt = requireNotNull(matched)
                assertEquals(200, receipt.getInt("http"))
                assertTrue(receipt.getJSONObject("ack").getBoolean("accepted"))
                assertEquals(directive.getString("action_id"), receipt.getJSONObject("request").getString("action_id"))
                assertEquals("success", receipt.getJSONObject("request").getString("result"))
                return directive.getJSONObject("payload") to receipt.getJSONObject("request").getJSONObject("details")
            }

            val timerCommand = if (verifyTimerDelivery) "đặt hẹn giờ một phút" else "đặt hẹn giờ bảy phút"
            val (timer, timerDetails) = confirm(timerCommand) {
                assertEquals(baseline, alarmDigest())
                assertTrue("No Clock timer may exist before consent", timerIds(clockPreferencesText()).isEmpty())
            }
            assertEquals("Timer", timer.getString("type")); assertEquals(timerDurationSeconds, timer.getInt("duration"))
            assertTrue(timerDetails.getBoolean("intent_started")); assertFalse(timerDetails.getBoolean("completion_verified"))
            val timerDeadline = SystemClock.elapsedRealtime() + 10000
            var timerPreferences = ""
            var timerId = ""
            while (SystemClock.elapsedRealtime() < timerDeadline) {
                timerPreferences = clockPreferencesText()
                timerId = timerIds(timerPreferences).singleOrNull().orEmpty()
                if (timerId.isNotBlank()) break
                Thread.sleep(100)
            }
            assertTrue("Clock must persist exactly one test timer", timerId.isNotBlank())
            assertTrue(timerPreferences.contains("<int name=\"timer_state_$timerId\" value=\"1\" />"))
            assertTrue(timerPreferences.contains("<long name=\"timer_original_timet_$timerId\" value=\"${timerDurationSeconds * 1000L}\" />"))
            assertTrue(timerPreferences.contains("<long name=\"timer_setup_timet_$timerId\" value=\"${timerDurationSeconds * 1000L}\" />"))
            assertTrue(timerPreferences.contains("<boolean name=\"delete_after_use_$timerId\" value=\"true\" />"))
            var expiredTimerActivityObserved = false
            var expiredTimerStateObserved = false
            var timerNotificationObserved = false
            if (verifyTimerDelivery) {
                val deliveryDeadline = SystemClock.elapsedRealtime() + 90000
                while (SystemClock.elapsedRealtime() < deliveryDeadline && !(expiredTimerStateObserved && timerNotificationObserved)) {
                    expiredTimerActivityObserved = shell("dumpsys activity activities").contains("com.android.deskclock.timer.ExpiredTimersActivity")
                    val observedPreferences = clockPreferencesText()
                    expiredTimerStateObserved = observedPreferences.contains("<int name=\"timer_state_$timerId\" value=\"3\" />")
                    timerNotificationObserved = shell("dumpsys notification --noredact").contains("com.google.android.deskclock")
                    if (!(expiredTimerStateObserved && timerNotificationObserved)) Thread.sleep(250)
                }
                assertTrue("The real Clock must transition the accepted one-minute timer to its expired state", expiredTimerStateObserved)
                assertTrue("The real Clock must retain an observable timer notification while ringing", timerNotificationObserved)
            }
            results.put(JSONObject().put("type", "Timer").put("before_consent_unchanged", true).put("clock_persistence_verified", true)
                .put("duration_seconds", timerDurationSeconds).put("receipt_accepted_by_candidate", true).put("receipt_completion_verified", false)
                .put("expiration_delivery_requested", verifyTimerDelivery).put("expired_timer_state_observed", expiredTimerStateObserved)
                .put("expired_timer_activity_observed", expiredTimerActivityObserved)
                .put("timer_notification_observed", timerNotificationObserved))
            shell("am force-stop com.google.android.deskclock")
            shell("am start -n ${context.packageName}/vn.tn.assistant.MainActivity")
            val resumed = MainActivity::class.java.getDeclaredField("resumed").apply { isAccessible = true }
            var resumeDeadline = SystemClock.elapsedRealtime() + 5000
            while (resumed.getBoolean(activity).not() && SystemClock.elapsedRealtime() < resumeDeadline) Thread.sleep(100)
            assertTrue(resumed.getBoolean(activity))

            val date = Calendar.getInstance().apply {
                if (verifyAlarmDelivery) add(Calendar.MINUTE, 2) else add(Calendar.HOUR_OF_DAY, 3)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            var available = false
            repeat(30) {
                if (!available) {
                    val h = date.get(Calendar.HOUR_OF_DAY); val m = date.get(Calendar.MINUTE)
                    if (sql("SELECT count(*) FROM alarm_templates WHERE hour=$h AND minutes=$m;") == "0") available = true else date.add(Calendar.MINUTE, 1)
                }
            }
            assertTrue("Find an unused Clock time without modifying an existing template", available)
            val hour = date.get(Calendar.HOUR_OF_DAY); val minute = date.get(Calendar.MINUTE)
            ownedAlarmTime = hour to minute
            val (alarm, alarmDetails) = confirm("đặt báo thức lúc $hour:$minute") { assertEquals(baseline, alarmDigest()) }
            assertEquals("Alarm", alarm.getString("type")); assertEquals(hour, alarm.getInt("hour")); assertEquals(minute, alarm.getInt("minute"))
            assertTrue(alarmDetails.getBoolean("intent_started")); assertFalse(alarmDetails.getBoolean("completion_verified"))
            val persistedDeadline = SystemClock.elapsedRealtime() + 10000
            var persistedText = ""
            while (SystemClock.elapsedRealtime() < persistedDeadline) {
                persistedText = sql("SELECT _id,hour,minutes,daysofweek,enabled FROM alarm_templates WHERE hour=$hour AND minutes=$minute;")
                if (persistedText.isNotBlank()) break
                Thread.sleep(100)
            }
            val persisted = persistedText.split('|')
            assertEquals(5, persisted.size); alarmId = persisted[0].toLong()
            assertEquals(listOf(hour.toString(), minute.toString(), "0", "1"), persisted.drop(1))
            var firedAlarmStateObserved = false
            var alarmNotificationObserved = false
            if (verifyAlarmDelivery) {
                val deliveryDeadline = SystemClock.elapsedRealtime() + 150000
                while (SystemClock.elapsedRealtime() < deliveryDeadline && !(firedAlarmStateObserved && alarmNotificationObserved)) {
                    firedAlarmStateObserved = sql("SELECT alarm_state FROM alarm_instances WHERE alarm_id=$alarmId;") == "5"
                    alarmNotificationObserved = shell("dumpsys notification --noredact").contains("com.google.android.deskclock")
                    if (!(firedAlarmStateObserved && alarmNotificationObserved)) Thread.sleep(250)
                }
                assertTrue("The real Clock must transition the accepted near-future alarm to fired state5", firedAlarmStateObserved)
                assertTrue("The real Clock must expose a notification while the accepted alarm is firing", alarmNotificationObserved)
            }
            results.put(JSONObject().put("type", "Alarm").put("before_consent_unchanged", true).put("clock_persistence_verified", true)
                .put("receipt_accepted_by_candidate", true).put("receipt_completion_verified", false)
                .put("firing_delivery_requested", verifyAlarmDelivery).put("fired_alarm_state_observed", firedAlarmStateObserved)
                .put("alarm_notification_observed", alarmNotificationObserved))
            shell("am force-stop com.google.android.deskclock")
            shell("am start -n ${context.packageName}/vn.tn.assistant.MainActivity")
            resumeDeadline = SystemClock.elapsedRealtime() + 5000
            while (resumed.getBoolean(activity).not() && SystemClock.elapsedRealtime() < resumeDeadline) Thread.sleep(100)
            assertTrue(resumed.getBoolean(activity))

            val (dismiss, dismissDetails) = confirm("tắt báo thức lúc $hour:$minute") {
                assertEquals("1", sql("SELECT enabled FROM alarm_templates WHERE _id=$alarmId;"))
            }
            assertEquals("DismissAlarm", dismiss.getString("type")); assertEquals("time", dismiss.getString("searchMode"))
            assertEquals(hour, dismiss.getInt("hour")); assertEquals(minute, dismiss.getInt("minute"))
            assertTrue(dismissDetails.getBoolean("intent_started")); assertFalse(dismissDetails.getBoolean("completion_verified"))
            val dismissedDeadline = SystemClock.elapsedRealtime() + 10000
            var dismissed = "1"
            while (SystemClock.elapsedRealtime() < dismissedDeadline) {
                dismissed = sql("SELECT enabled FROM alarm_templates WHERE _id=$alarmId;")
                if (dismissed.isBlank() || dismissed == "0") break
                Thread.sleep(100)
            }
            assertTrue("One-time alarm must be removed or disabled by the real Clock", dismissed.isBlank() || dismissed == "0")
            assertEquals("Dismissed alarm must not retain a scheduled instance", "0", sql("SELECT count(*) FROM alarm_instances WHERE alarm_id=$alarmId;"))
            results.put(JSONObject().put("type", "DismissAlarm").put("before_consent_alarm_enabled", true)
                .put("clock_dismissal_verified", true).put("row_removed", dismissed.isBlank())
                .put("receipt_accepted_by_candidate", true).put("receipt_completion_verified", false))
            shell("am force-stop com.google.android.deskclock")
            shell("am start -n ${context.packageName}/vn.tn.assistant.MainActivity")
            resumeDeadline = SystemClock.elapsedRealtime() + 5000
            while (resumed.getBoolean(activity).not() && SystemClock.elapsedRealtime() < resumeDeadline) Thread.sleep(100)
            assertTrue(resumed.getBoolean(activity))

            val syncUri = C.Calendars.CONTENT_URI.buildUpon().appendQueryParameter("caller_is_syncadapter", "true")
                .appendQueryParameter("account_name", fixture).appendQueryParameter("account_type", "LOCAL").build()
            calendarUri = requireNotNull(resolver.insert(syncUri, ContentValues().apply {
                put("account_name", fixture); put("account_type", "LOCAL"); put("name", fixture); put("calendar_displayName", fixture)
                put("ownerAccount", fixture); put("calendar_access_level", 700); put("visible", 1); put("sync_events", 1)
                put("calendar_timezone", java.util.TimeZone.getDefault().id); put("maxReminders", 5); put("allowedReminders", "1"); put("isPrimary", 1)
            }))
            val reminderDate = if (verifyReminderDelivery) Calendar.getInstance().apply {
                add(Calendar.MINUTE, 2); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            } else date
            val reminderHour = reminderDate.get(Calendar.HOUR_OF_DAY)
            val reminderMinute = reminderDate.get(Calendar.MINUTE)
            val (reminder, details) = confirm("nhắc tôi $fixture lúc $reminderHour:$reminderMinute") {
                assertEquals(beforeEvents, count(C.Events.CONTENT_URI)); assertEquals(beforeReminders, count(C.Reminders.CONTENT_URI))
            }
            assertEquals("Reminder", reminder.getString("type")); assertEquals(fixture, reminder.getString("task"))
            assertTrue(details.getBoolean("completion_verified")); assertTrue(details.getBoolean("calendar_event_saved"))
            val event = TnCalendarControls.event(reminder)
            val eventId = resolver.query(C.Events.CONTENT_URI, arrayOf("_id", "calendar_id", "dtstart", "dtend", "eventTimezone"),
                "title=?", arrayOf(fixture), null)!!.use { cursor ->
                assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst())
                assertEquals(ContentUris.parseId(calendarUri), cursor.getLong(1)); assertEquals(event.start, cursor.getLong(2)); assertEquals(event.end, cursor.getLong(3))
                assertEquals(event.timezone, cursor.getString(4)); cursor.getLong(0)
            }
            resolver.query(C.Reminders.CONTENT_URI, arrayOf("minutes", "method"), "event_id=?", arrayOf(eventId.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(C.Reminders.METHOD_ALERT, it.getInt(1))
            }
            var calendarNotificationObserved = false
            if (verifyReminderDelivery) {
                val deliveryDeadline = SystemClock.elapsedRealtime() + 150000
                while (SystemClock.elapsedRealtime() < deliveryDeadline && !calendarNotificationObserved) {
                    val notifications = shell("dumpsys notification --noredact")
                    calendarNotificationObserved = notifications.contains("com.google.android.calendar") && notifications.contains(fixture)
                    if (!calendarNotificationObserved) Thread.sleep(250)
                }
                assertTrue("Google Calendar must expose a notification containing the accepted Reminder title", calendarNotificationObserved)
            }
            results.put(JSONObject().put("type", "Reminder").put("before_consent_unchanged", true).put("event_and_reminder_persistence_verified", true)
                .put("receipt_accepted_by_candidate", true).put("receipt_completion_verified", true).put("reminder_minutes", 0)
                .put("notification_delivery_requested", verifyReminderDelivery).put("calendar_notification_observed", calendarNotificationObserved))
            evidence.put("status", "passed").put("results", results)
        } catch (error: Throwable) {
            originalFailure = error
            evidence.put("status", "failed").put("failure_class", error.javaClass.simpleName).put("results", results)
            throw error
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            val cleanup = JSONObject()
            fun clean(name: String, action: () -> Unit) {
                try { action(); cleanup.put(name, true) } catch (error: Throwable) { cleanupErrors += error; cleanup.put(name, false) }
            }
            try {
                clean("activity_finished") { activity?.let { current -> instrumentation.runOnMainSync { current.finish() } } }
                client?.dispatcher?.cancelAll()
                clean("clock_templates_restored") {
                    shell("am force-stop com.google.android.deskclock")
                    if (alarmId == null) ownedAlarmTime?.let { (hour, minute) ->
                        val candidates = sql("SELECT _id FROM alarm_templates WHERE hour=$hour AND minutes=$minute;")
                        if (candidates.isNotBlank()) { require(!candidates.contains('\n')); alarmId = candidates.toLong() }
                    }
                    alarmId?.let { id ->
                        sql("PRAGMA foreign_keys=ON; BEGIN; DELETE FROM alarm_instances WHERE alarm_id=$id; DELETE FROM alarm_templates WHERE _id=$id; COMMIT;")
                    }
                    assertEquals("Existing Clock templates must be preserved", baseline, alarmDigest())
                }
                clean("clock_timer_preferences_restored") {
                    if (timerBackupCreated) {
                        shell("su 0 cp $timerBackup $clockPreferences")
                        shell("su 0 chown $clockOwner $clockPreferences")
                        shell("su 0 chmod 660 $clockPreferences")
                        shell("su 0 restorecon $clockPreferences")
                        assertEquals("Existing Clock preferences must be restored byte-for-byte", baselineTimerPreferences, clockPreferencesText())
                        shell("su 0 rm $timerBackup")
                        timerBackupCreated = false
                    }
                }
                clean("calendar_rows_restored") {
                    calendarUri?.let { resolver.delete(it.buildUpon().appendQueryParameter("caller_is_syncadapter", "true")
                        .appendQueryParameter("account_name", fixture).appendQueryParameter("account_type", "LOCAL").build(), null, null) }
                    assertEquals(0, count(C.Calendars.CONTENT_URI)); assertEquals(beforeEvents, count(C.Events.CONTENT_URI)); assertEquals(beforeReminders, count(C.Reminders.CONTENT_URI))
                }
                clean("staging_guest_deleted") {
                    session?.let {
                        val deletion = relay("DELETE", "/v1/me", authorization = "Bearer " + it.getString("access_token"))
                        assertEquals(200, deletion.getInt("status")); assertTrue(JSONObject(deletion.getString("body")).getBoolean("ok"))
                    }
                }
            } finally {
                isolation.close()
                evidence.put("cleanup", cleanup)
                if (cleanupErrors.isNotEmpty()) evidence.put("status", "failed_cleanup")
                File(context.cacheDir, "joined185-android194.json").writeText(evidence.toString(2))
            }
            if (cleanupErrors.isNotEmpty()) {
                val failure = originalFailure ?: cleanupErrors.first()
                cleanupErrors.filter { it !== failure }.forEach(failure::addSuppressed)
                if (originalFailure == null) throw failure
            }
        }
    }
}
