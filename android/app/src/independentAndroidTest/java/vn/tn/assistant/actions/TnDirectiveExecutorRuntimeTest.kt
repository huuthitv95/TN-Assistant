package vn.tn.assistant.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.ui.TnIsolatedActivityPreferences
import vn.tn.assistant.ui.TnNotificationAccessTestScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TnDirectiveExecutorRuntimeTest {
    private class Volume(var value: Int = 40, val max: Int = 100, var writable: Boolean = true) : TnDirectiveExecutor.Volume {
        var writes = 0
        override fun maximum() = max
        override fun current() = value
        override fun set(value: Int) { writes++; if (writable) this.value = value }
    }
    private fun action(type: String, id: String, payload: JSONObject = JSONObject()) =
        JSONObject().put("type", type).put("action_id", id).put("payload", payload)
    private fun batch(vararg actions: JSONObject) = JSONObject().put("directives", JSONArray(actions.toList()))
    private fun volume(command: String) = JSONObject().put("command", command)
    private data class Receipt(val id: String, val result: String, val details: JSONObject)
    private class Harness(val volume: Volume = Volume()) {
        var active = true
        val calls = mutableListOf<String>()
        val receipts = mutableListOf<Receipt>()
        fun executor() = TnDirectiveExecutor({ active },
            speech = { calls += "speech:$it"; true }, map = { calls += "map:$it"; true }, app = { calls += "app:$it"; true },
            volume = volume, receipt = { id, result, details -> receipts += Receipt(id, result, details) })
    }

    @Test fun volumeUsesTheOriginalFifteenPercentStepsAndReadsBackTheActualValue() {
        val h = Harness()
        h.executor().execute(batch(action("PlayerVolume", "up", volume("increase_volume")),
            action("PlayerVolume", "down", volume("decrease_volume"))))
        assertEquals(40, h.volume.value)
        assertEquals(listOf("success", "success"), h.receipts.map { it.result })
        assertEquals(55, h.receipts[0].details.getInt("volume_requested"))
        assertEquals(55, h.receipts[0].details.getInt("volume_observed"))
        assertFalse(h.receipts[0].details.getBoolean("intent_started"))
        val rounded = Harness(Volume(7, 15))
        rounded.executor().execute(batch(action("PlayerVolume", "up", volume("increase_volume"))))
        assertEquals("Original integer percentage truncates 2.25 to two steps", 9, rounded.volume.value)
    }

    @Test fun percentAndTenStepLevelRespectTheOriginalMinusOneSentinelAndClamp() {
        for ((level, percent, expected) in listOf(Triple(-1.0, 35.9, 35), Triple(7.0, -1.0, 70), Triple(-1.0, 150.0, 100), Triple(-1.0, -4.0, 0))) {
            val h = Harness()
            val p = volume("adjust_volume").put("adjust_level", level).put("adjust_percentage", percent)
            h.executor().execute(batch(action("PlayerVolume", "adjust", p)))
            assertEquals(expected, h.volume.value)
            assertEquals("success", h.receipts.single().result)
        }
    }

    @Test fun malformedOrUnknownVolumeNeverMutatesTheDevice() {
        for (p in listOf(volume("delete"), volume("adjust_volume").put("adjust_level", 2).put("adjust_percentage", 30),
            volume("adjust_volume").put("adjust_level", -1).put("adjust_percentage", -1),
            volume("adjust_volume").put("adjust_level", -1), volume("adjust_volume").put("volume", "50"))) {
            val h = Harness()
            h.executor().execute(batch(action("PlayerVolume", "bad", p)))
            assertEquals(0, h.volume.writes)
            assertEquals("failed", h.receipts.single().result)
        }
    }

    @Test fun refusedVolumeIsFailedAndDependentActionsAreCancelled() {
        val h = Harness(Volume(writable = false))
        val response = batch(action("PlayerVolume", "volume", volume("increase_volume")),
            action("SpeechSynthesizer", "speech", JSONObject().put("utter_text", "must not speak").put("required", JSONArray().put("volume|success"))))
        val summary = h.executor().execute(response)
        assertEquals(1, summary.failed)
        assertEquals(listOf("failed", "cancelled"), h.receipts.map { it.result })
        assertTrue(h.calls.isEmpty())
    }

    @Test fun sharedExecutorPreservesOrderAndUsesOneReceiptPerAction() {
        val h = Harness()
        val response = batch(action("OpenApp", "open", JSONObject().put("package_name", "TEST.package")),
            action("MapDirection", "map", JSONObject().put("url", "https://www.google.com/maps/")),
            action("PlayerVolume", "volume", volume("increase_volume")),
            action("SpeechSynthesizer", "speech", JSONObject().put("utter_text", "ready").put("required", JSONArray().put("volume|success"))))
        assertEquals(TnDirectiveExecutor.Summary(), h.executor().execute(response))
        assertEquals(listOf("app:TEST.package", "map:https://www.google.com/maps/", "speech:ready"), h.calls)
        assertEquals(listOf("open", "map", "volume", "speech"), h.receipts.map { it.id })
        assertTrue(h.receipts[0].details.getBoolean("intent_started"))
        assertTrue(h.receipts[1].details.getBoolean("intent_started"))
        assertFalse(h.receipts[3].details.getBoolean("intent_started"))
    }

    @Test fun duplicateActionIdsAndReplayedResponsesNeverExecuteAgain() {
        val h = Harness()
        val response = batch(action("PlayerVolume", "same", volume("increase_volume")), action("PlayerVolume", "same", volume("increase_volume")))
        h.executor().execute(response)
        assertEquals(1, h.volume.writes)
        assertEquals(1, h.receipts.size)
        h.executor().execute(response.put("replayed", true))
        assertEquals(1, h.volume.writes)
    }

    @Test fun cancelledOwnerCannotStartActionsOrSendOldReceipts() {
        val h = Harness().apply { active = false }
        assertTrue(h.executor().execute(batch(action("PlayerVolume", "old", volume("increase_volume")))).cancelled)
        assertEquals(0, h.volume.writes)
        assertTrue(h.receipts.isEmpty())
    }

    @Test fun cancelDuringDelayStopsTheWholeRemainingBatchPromptly() {
        val active = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val done = CountDownLatch(1)
        val h = Harness()
        var result: TnDirectiveExecutor.Summary? = null
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val executor = TnDirectiveExecutor({ entered.countDown(); active.get() }, { true }, { true }, { true }, h.volume,
            { id, r, d -> h.receipts += Receipt(id, r, d) })
        val start = SystemClock.elapsedRealtime()
        Thread {
            try { result = executor.execute(batch(action("Delay", "delay", JSONObject().put("duration", 30000)), action("PlayerVolume", "late", volume("increase_volume")))) }
            catch (error: Throwable) { errors += error } finally { done.countDown() }
        }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS)); Thread.sleep(80); active.set(false)
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(SystemClock.elapsedRealtime() - start < 2500)
        assertTrue(result!!.cancelled); assertEquals(0, h.volume.writes); assertTrue(h.receipts.isEmpty())
    }

    @Test fun unsupportedActionsAreNotReportedAsCompletedAndCannotUnlockSuccessPrerequisites() {
        val h = Harness()
        val response = batch(action("PlayerMP3", "missing"), action("PlayerVolume", "guarded",
            volume("increase_volume").put("required", JSONArray().put("missing|success"))))
        assertEquals(1, h.executor().execute(response).unsupported)
        assertEquals(listOf("unsupported", "cancelled"), h.receipts.map { it.result })
        assertEquals(0, h.volume.writes)
    }

    @Test fun receiptFailureStopsFurtherSideEffectsWithoutRetryingTheCompletedAction() {
        val h = Harness()
        val executor = TnDirectiveExecutor({ true }, { true }, { true }, { true }, h.volume, { _, _, _ -> throw java.io.IOException("TEST receipt outage") })
        try {
            executor.execute(batch(action("PlayerVolume", "first", volume("increase_volume")), action("PlayerVolume", "second", volume("increase_volume"))))
            fail("Receipt failure must be surfaced")
        } catch (_: java.io.IOException) { }
        assertEquals(1, h.volume.writes)
    }

    @Test fun realAudioManagerExecutesVolumeAndRestoresItsOriginalValue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val saved = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        assertTrue(max > 0)
        val receipts = mutableListOf<Receipt>()
        val executor = TnDirectiveExecutor({ true }, { true }, { true }, { true }, TnDirectiveExecutor.androidVolume(context),
            { id, r, d -> receipts += Receipt(id, r, d) })
        try {
            executor.execute(batch(action("PlayerVolume", "actual", volume("adjust_volume").put("adjust_level", -1).put("adjust_percentage", 35))))
            assertEquals(max * 35 / 100, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals("success", receipts.single().result)
            assertEquals(max * 35 / 100, receipts.single().details.getInt("volume_observed"))
        } finally { manager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
        assertEquals(saved, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun activityDispatchesThroughTheSharedExecutorAgainstTheRealAndroidVolumeService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val saved = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        TnIsolatedActivityPreferences(context).use {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                val method = MainActivity::class.java.getDeclaredMethod("executeDirectives", JSONObject::class.java, String::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                // No action ID: this component test deliberately does not send
                // a fabricated result to the production action receipt API.
                val input = batch(action("PlayerVolume", "", volume("adjust_volume").put("adjust_level", -1).put("adjust_percentage", 20)))
                val result = method.invoke(activity, input, "", "", { true }) as TnDirectiveExecutor.Summary
                assertEquals(TnDirectiveExecutor.Summary(), result)
                assertEquals(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 20 / 100, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
            } finally {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}

/** Real owned VPS action and receipt, explicitly enabled only on the isolated SDK AVD. */
class TnVpsDeviceActionsRuntimeTest {
    @Test fun realVpsVolumeDirectiveChangesAndroidAndAcknowledgesOnlyItsIssuedAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("tnLiveActions") == "true")
        val context = instrumentation.targetContext
        val avd = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu.avd_name"))
            .bufferedReader().use { it.readText().trim() }
        assertEquals("TNNative166", avd)
        val store = vn.tn.assistant.auth.TnSessionStore.get(context)
        assertTrue("A real signed-in VPS device is required", store.hasSession())
        val client = vn.tn.assistant.api.TnVoiceBackendClient(context)
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val original = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            val me = client.json("/v1/me")
            assertNotNull(me.optJSONObject("user"))
            val device = context.getSharedPreferences("tn-session-v1", 0).getString("device_id", null)
            assertFalse(device.isNullOrBlank())
            val input = JSONObject().put("text", "Âm lượng 35%").put("mode", "commands")
                .put("request_id", java.util.UUID.randomUUID().toString()).put("device_id", device)
            val response = client.json("/v1/assistant/text", input)
            val directives = response.getJSONArray("directives")
            assertEquals(1, directives.length())
            assertEquals("PlayerVolume", directives.getJSONObject(0).getString("type"))
            val actionId = directives.getJSONObject(0).getString("action_id")
            assertTrue(actionId.isNotBlank())
            var receipt: JSONObject? = null
            val owner = store.actionGeneration()
            val executor = TnDirectiveExecutor({ store.ownsActions(owner) }, { false }, { false }, { false },
                TnDirectiveExecutor.androidVolume(context), { id, result, details ->
                    assertEquals(actionId, id)
                    assertEquals("success", result)
                    val payload = JSONObject().put("request_id", response.getString("request_id")).put("action_id", id)
                        .put("result", result).put("details", details)
                    val ack = client.json("/v1/device/actions/results", payload)
                    assertTrue(ack.getBoolean("accepted")); assertFalse(ack.getBoolean("replayed"))
                    receipt = payload
                })
            assertEquals(TnDirectiveExecutor.Summary(), executor.execute(response))
            assertEquals(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 35 / 100, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
            val duplicateReceipt = client.json("/v1/device/actions/results", requireNotNull(receipt))
            assertTrue(duplicateReceipt.getBoolean("replayed"))
            val duplicateCommand = client.json("/v1/assistant/text", input)
            assertTrue(duplicateCommand.getBoolean("replayed"))
            assertEquals(TnDirectiveExecutor.Summary(), executor.execute(duplicateCommand))
        } finally {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            client.cancel()
        }
        assertEquals(original, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}

/** Real Android Binder/session callbacks; this is not partner-app playback acceptance. */
class TnMediaControlsRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val allActions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO or
        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private inner class Player : AutoCloseable {
        val session = MediaSession(context, "TN transport component verification")
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val state = java.util.concurrent.atomic.AtomicInteger(PlaybackState.STATE_PLAYING)
        var ignore = false
        var afterCall: (() -> Unit)? = null
        var actions = allActions
        var track = 1L

        init {
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { call("play") { update(PlaybackState.STATE_PLAYING) } }
                override fun onPause() { call("pause") { update(PlaybackState.STATE_PAUSED) } }
                override fun onSeekTo(pos: Long) { call("seek:$pos") { update(state.get(), pos) } }
                override fun onSkipToNext() { call("next") { track++; update(state.get()) } }
                override fun onSkipToPrevious() { call("previous") { track--; update(state.get()) } }
            }, Handler(Looper.getMainLooper()))
            update(PlaybackState.STATE_PLAYING)
            session.isActive = true
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = android.content.ComponentName(context, vn.tn.assistant.TnMediaAccessService::class.java)
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (manager.getActiveSessions(component).firstOrNull()?.sessionToken != session.sessionToken && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertEquals("Test player must be Android's actual priority controller", session.sessionToken, manager.getActiveSessions(component).firstOrNull()?.sessionToken)
        }

        fun update(value: Int, position: Long = 30000) {
            state.set(value)
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "TN-test-$track").build())
            session.setPlaybackState(PlaybackState.Builder().setActions(actions).setActiveQueueItemId(track)
                .setState(value, position, 0f, SystemClock.elapsedRealtime()).build())
        }
        private fun call(name: String, change: () -> Unit) {
            calls += name
            if (!ignore) change()
            afterCall?.invoke()
        }
        override fun close() { session.isActive = false; session.release() }
    }

    private fun execute(command: String, details: JSONObject = JSONObject(), active: () -> Boolean = { true }): String =
        TnMediaControls(context, active).execute(JSONObject().put("command", command), details)

    @Test fun fiveOriginalControlsReachRealSessionCallbacksAndConfirmedState() {
        TnNotificationAccessTestScope(context, true).use {
            Player().use { player ->
                for ((command, expected) in listOf("music_control_next" to listOf("next"),
                    "music_control_prev" to listOf("seek:0", "previous"),
                    "music_control_stop" to listOf("pause"), "music_control_resume" to listOf("play"),
                    "music_control_replay" to listOf("seek:0"))) {
                    player.calls.clear()
                    val details = JSONObject()
                    assertEquals(command, "success", execute(command, details))
                    assertEquals(expected, player.calls.toList())
                    assertEquals("media_state_confirmed", details.getString("reason"))
                    assertTrue(details.getBoolean("transport_dispatched"))
                    assertFalse("Do not include private track identity in receipts", details.toString().contains("TN-test-"))
                }
            }
        }
    }

    @Test fun unsupportedActionNeverDispatchesAndNeverFallsBackToAnotherPlayer() {
        TnNotificationAccessTestScope(context, true).use {
            Player().use { player ->
                player.actions = PlaybackState.ACTION_PLAY
                player.update(PlaybackState.STATE_PAUSED)
                val details = JSONObject()
                assertEquals("unsupported", execute("next", details))
                assertEquals("media_action_not_supported", details.getString("reason"))
                assertFalse(details.getBoolean("transport_dispatched"))
                assertTrue(player.calls.isEmpty())
            }
        }
    }

    @Test fun permissionDeniedOrUnknownCommandDoesNotControlMedia() {
        TnNotificationAccessTestScope(context, false).use {
            val denied = JSONObject()
            assertEquals("failed", execute("pause", denied))
            assertEquals("notification_access_required", denied.getString("reason"))
            val unknown = JSONObject()
            assertEquals("unsupported", execute("delete", unknown))
            assertEquals("media_command_not_implemented", unknown.getString("reason"))
        }
    }

    @Test fun noActiveSessionIsAnExplicitFailure() {
        TnNotificationAccessTestScope(context, true).use {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = android.content.ComponentName(context, vn.tn.assistant.TnMediaAccessService::class.java)
            assertTrue("Isolated AVD must not contain another media player", manager.getActiveSessions(component).isEmpty())
            val details = JSONObject()
            assertEquals("failed", execute("next", details))
            assertEquals("no_active_media_session", details.getString("reason"))
        }
    }

    @Test fun changedTrackWithPlayerErrorIsNotReportedAsSuccessfulPlayback() {
        TnNotificationAccessTestScope(context, true).use {
            Player().use { player ->
                player.afterCall = { player.update(PlaybackState.STATE_ERROR) }
                val details = JSONObject()
                assertEquals("failed", execute("next", details))
                assertEquals("media_player_error", details.getString("reason"))
            }
        }
    }

    @Test fun ignoredTransportTimesOutWithoutFakeSuccessOrAutomaticRetry() {
        TnNotificationAccessTestScope(context, true).use {
            Player().use { player ->
                player.ignore = true
                val details = JSONObject()
                val start = SystemClock.elapsedRealtime()
                assertEquals("failed", execute("next", details))
                assertEquals("media_result_unconfirmed", details.getString("reason"))
                assertTrue(SystemClock.elapsedRealtime() - start in 2400..5000)
                assertEquals(listOf("next"), player.calls.toList())
            }
        }
    }

    @Test fun ownerCancellationStopsWaitingAndNeverDispatchesLaterActionsOrReceipts() {
        TnNotificationAccessTestScope(context, true).use {
            Player().use { player ->
                player.ignore = true
                val active = AtomicBoolean(true)
                player.afterCall = { active.set(false) }
                var later = 0
                val executor = TnDirectiveExecutor({ active.get() }, { later++; true }, { true }, { true },
                    TnDirectiveExecutor.androidVolume(context), { _, _, _ -> later++ },
                    TnMediaControls(context) { active.get() }::execute)
                val response = JSONObject().put("directives", JSONArray()
                    .put(JSONObject().put("type", "PlayerMP3").put("action_id", "test-media").put("payload", JSONObject().put("command", "next")))
                    .put(JSONObject().put("type", "SpeechSynthesizer").put("action_id", "test-speech").put("payload", JSONObject().put("utter_text", "must not execute"))))
                val start = SystemClock.elapsedRealtime()
                assertTrue(executor.execute(response).cancelled)
                assertTrue(SystemClock.elapsedRealtime() - start < 1500)
                assertEquals(0, later)
                assertEquals(listOf("next"), player.calls.toList())
            }
        }
    }

    @Test fun sessionDisappearingAfterDispatchIsFailed() {
        TnNotificationAccessTestScope(context, true).use {
            Player().use { player ->
                player.ignore = true
                player.afterCall = { player.session.isActive = false }
                val details = JSONObject()
                assertEquals("failed", execute("next", details))
                assertEquals("media_session_ended", details.getString("reason"))
                assertEquals(listOf("next"), player.calls.toList())
            }
        }
    }

    @Test fun alreadyPausedIsConfirmedWithoutDuplicatingTransport() {
        TnNotificationAccessTestScope(context, true).use {
            Player().use { player ->
                player.update(PlaybackState.STATE_PAUSED)
                val details = JSONObject()
                assertEquals("success", execute("stop", details))
                assertEquals("media_state_already_satisfied", details.getString("reason"))
                assertFalse(details.getBoolean("transport_dispatched"))
                assertTrue(player.calls.isEmpty())
            }
        }
    }

    @Test fun activityRoutesPlayerMp3ToRealTransportThroughSharedExecutor() {
        TnNotificationAccessTestScope(context, true).use {
            Player().use { player ->
                TnIsolatedActivityPreferences(context).use {
                    val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                    try {
                        val method = MainActivity::class.java.getDeclaredMethod("executeDirectives", JSONObject::class.java, String::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                        // Deliberately no ID: component evidence must not fabricate production receipts.
                        val response = JSONObject().put("directives", JSONArray().put(JSONObject().put("type", "PlayerMP3")
                            .put("payload", JSONObject().put("command", "music_control_stop"))))
                        assertEquals(TnDirectiveExecutor.Summary(), method.invoke(activity, response, "", "", { true }))
                        assertEquals(listOf("pause"), player.calls.toList())
                        assertEquals(PlaybackState.STATE_PAUSED, player.state.get())
                    } finally { instrumentation.runOnMainSync { activity.finish() } }
                }
            }
        }
    }
}

/** Clock SDK contract plus one real persisted alarm on the isolated, empty SDK AVD. */
class TnPhoneContractRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun payload(phone: Any = "5551234567") = JSONObject().put("phone_number", phone)

    @Test fun publicCallIntentContainsOnlyValidatedDigitsAndNoUriControlData() {
        for (phone in listOf("5551234567", "+84900000000", "555")) {
            val intent = TnPhoneControls.intent(payload(phone))
            assertEquals(Intent.ACTION_CALL, intent.action)
            assertEquals("tel", intent.data!!.scheme)
            assertEquals(phone, intent.data!!.schemeSpecificPart)
            assertNull(intent.data!!.fragment)
            assertNull(intent.extras)
        }
        for (invalid in listOf<Any>("", "12", "1234567890123456", "555 1234567", "555;123", "tel:5551234567",
            "555?account=x", "*123#", "555\n1234567", "++84900000000", 5551234567L)) {
            try { TnPhoneControls.intent(payload(invalid)); fail("Invalid telephone data accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun absentCallPermissionOrCancelledOwnerNeverLaunchesTheTelephone() {
        assertFalse(TnPhoneControls.hasAccess(context))
        var launches = 0
        val details = JSONObject()
        assertEquals("failed", TnPhoneControls(context, { true }, { launches++; true }, { false }).execute(payload(), details))
        assertEquals("call_permission_required", details.getString("reason"))
        assertFalse(details.getBoolean("completion_verified"))
        // A UI callback claiming permission is insufficient without the OS grant.
        assertEquals("failed", TnPhoneControls(context, { true }, { launches++; true }, { true }).execute(payload(), JSONObject()))
        assertEquals("cancelled", TnPhoneControls(context, { false }, { launches++; true }).execute(payload(), JSONObject()))
        val owner = AtomicBoolean(true)
        assertEquals("cancelled", TnPhoneControls(context, owner::get, { launches++; true }, { owner.set(false); true }).execute(payload(), JSONObject()))
        assertEquals(0, launches)
    }

    @Test fun phoneDispatchSharesOrderingReceiptsDeduplicationAndPrerequisites() {
        val calls = mutableListOf<String>()
        val receipts = mutableListOf<Pair<String, JSONObject>>()
        val executor = TnDirectiveExecutor({ true }, { calls += "speech"; true }, { true }, { true },
            TnDirectiveExecutor.androidVolume(context), { _, result, details -> receipts += result to details },
            phone = { _, details -> calls += "call"; details.put("intent_started", true).put("interaction_required", true); "success" })
        val call = JSONObject().put("type", "PhoneCall").put("action_id", "TEST-call").put("payload", payload())
        val speech = JSONObject().put("type", "SpeechSynthesizer").put("action_id", "TEST-after")
            .put("payload", JSONObject().put("utter_text", "test").put("required", JSONArray().put("TEST-call|success")))
        assertEquals(TnDirectiveExecutor.Summary(interactionRequired = 1), executor.execute(JSONObject().put("directives", JSONArray().put(call).put(call).put(speech))))
        assertEquals(listOf("call", "speech"), calls)
        assertEquals(listOf("success", "success"), receipts.map { it.first })
        assertEquals("PhoneCall", receipts.first().second.getString("directive_type"))
        assertFalse(receipts.first().second.toString().contains("5551234567"))
    }
}

/** Host scopes CALL_PHONE grants; tests never place a call outside the SDK AVD. */
class TnPhoneGrantedRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun payload() = JSONObject().put("phone_number", "5551234567")

    @Test fun grantedAdapterDispatchesOnceWithoutClaimingAConnectedCallOrExposingItsNumber() {
        assertTrue(TnPhoneControls.hasAccess(context))
        var launches = 0
        val details = JSONObject()
        assertEquals("success", TnPhoneControls(context, { true }, {
            launches++; assertEquals(Intent.ACTION_CALL, it.action); assertEquals("5551234567", it.data!!.schemeSpecificPart); true
        }).execute(payload(), details))
        assertEquals(1, launches)
        assertTrue(details.getBoolean("intent_started"))
        assertFalse(details.getBoolean("completion_verified"))
        assertTrue(details.getBoolean("interaction_required"))
        assertEquals("call_request_dispatched", details.getString("reason"))
        assertFalse(details.toString().contains("5551234567"))
        assertEquals("failed", TnPhoneControls(context, { true }, { false }).execute(payload(), JSONObject()))
    }

    @Test fun changingOwnerImmediatelyBeforeDispatchDoesNotCall() {
        var checks = 0
        var launches = 0
        assertEquals("cancelled", TnPhoneControls(context, { ++checks == 1 }, { launches++; true }).execute(payload(), JSONObject()))
        assertEquals(0, launches)
    }

    @Test fun activityUsesTheCallAdapterAndCannotLaunchItFromTheBackground() {
        val seen = java.util.concurrent.LinkedBlockingQueue<Intent>()
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CALL) return null
                seen.put(Intent(intent))
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            TnIsolatedActivityPreferences(context).use {
                val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                try {
                    val method = MainActivity::class.java.getDeclaredMethod("executeDirectives", JSONObject::class.java, String::class.java,
                        String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                    val input = JSONObject().put("directives", JSONArray().put(JSONObject().put("type", "PhoneCall").put("payload", payload())))
                    assertEquals(TnDirectiveExecutor.Summary(interactionRequired = 1), method.invoke(activity, input, "", "", { true }))
                    assertEquals("5551234567", seen.poll(1, TimeUnit.SECONDS)!!.data!!.schemeSpecificPart)
                    instrumentation.runOnMainSync { assertTrue(activity.moveTaskToBack(true)) }
                    instrumentation.waitForIdleSync()
                    assertEquals(TnDirectiveExecutor.Summary(failed = 1), method.invoke(activity, input, "", "", { true }))
                    assertNull("Background call reached Android", seen.poll(200, TimeUnit.MILLISECONDS))
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}

class TnPhonePermissionGrantRuntimeTest {
    @Test fun acceptingTheActualPhonePermissionReturnsOnlyAfterTheActivityResumes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(TnPhoneControls.hasAccess(context))
        val automation = instrumentation.uiAutomation
        val flags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            this.flags = this.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        TnIsolatedActivityPreferences(context).use {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                instrumentation.waitForIdleSync()
                val method = MainActivity::class.java.getDeclaredMethod("requestCallAccess", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                val result = pool.submit<Boolean> { method.invoke(activity, { true }) as Boolean }
                fun allow() = automation.rootInActiveWindow?.let { root ->
                    listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
                        .firstNotNullOfOrNull { root.findAccessibilityNodeInfosByViewId("$it:id/permission_allow_button").firstOrNull() }
                }
                val deadline = SystemClock.elapsedRealtime() + 8000
                var button = allow()
                while (button == null && !result.isDone && SystemClock.elapsedRealtime() < deadline) { Thread.sleep(50); button = allow() }
                assertNotNull("The actual phone permission grant UI must be exercised", button)
                assertTrue(button!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(result.get(5, TimeUnit.SECONDS))
                assertTrue(TnPhoneControls.hasAccess(context))
                assertTrue("Approved call must return to the foreground before dispatch",
                    MainActivity::class.java.getDeclaredField("resumed").apply { isAccessible = true }.getBoolean(activity))
            } finally {
                pool.shutdownNow()
                instrumentation.runOnMainSync { activity.finish() }
                automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
            }
        }
    }
}

/** Outgoing radio is virtual, confined to this named SDK AVD; never run on physical phones. */
class TnEmulatedCallRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun sql(statement: String): String {
        require(!statement.contains('"') && !statement.contains('$') && !statement.contains('`') && !statement.contains('\n'))
        return shell("su 0 sqlite3 /data/user/0/com.android.providers.contacts/databases/calllog.db ${statement.replace(" ", "/**/")}")
    }
    private fun states() = Regex("mCallState=([0-9]+)").findAll(shell("dumpsys telephony.registry")).map { it.groupValues[1] }.toList()

    @Test fun realAndroidTelecomAcceptsTheVirtualCallAndItsQaLogIsRemoved() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("tnEmulatedCall"))
        assertEquals("TNNative166", shell("getprop ro.kernel.qemu.avd_name"))
        assertEquals("1", shell("getprop ro.kernel.qemu"))
        assertTrue(TnPhoneControls.hasAccess(context))
        assertEquals("Do not interfere with any existing call", listOf("0"), states())
        assertEquals("Use only the isolated empty call log", "0", sql("SELECT count(*) FROM calls;"))
        val began = System.currentTimeMillis()
        var attempted = false
        TnIsolatedActivityPreferences(context).use {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                val method = MainActivity::class.java.getDeclaredMethod("executeDirectives", JSONObject::class.java, String::class.java,
                    String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                val input = JSONObject().put("directives", JSONArray().put(JSONObject().put("type", "PhoneCall")
                    .put("payload", JSONObject().put("phone_number", "5551234567"))))
                attempted = true
                assertEquals(TnDirectiveExecutor.Summary(interactionRequired = 1), method.invoke(activity, input, "", "", { true }))
                val deadline = SystemClock.elapsedRealtime() + 10000
                while (states().all { it == "0" } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                assertTrue("Android's emulated modem never received the outgoing call", states().any { it == "2" })
            } finally {
                if (attempted) {
                    shell("input keyevent 6")
                    val idleDeadline = SystemClock.elapsedRealtime() + 5000
                    while (states().any { it != "0" } && SystemClock.elapsedRealtime() < idleDeadline) Thread.sleep(100)
                    assertEquals("Virtual call must be ended", listOf("0"), states())
                    val logDeadline = SystemClock.elapsedRealtime() + 8000
                    val where = "number='5551234567' AND date>=$began"
                    while (sql("SELECT count(*) FROM calls WHERE $where;") == "0" && SystemClock.elapsedRealtime() < logDeadline) Thread.sleep(100)
                    try {
                        assertEquals("Call log must record the exact outgoing QA target", "1", sql("SELECT count(*) FROM calls WHERE $where AND type=2;"))
                    } finally {
                        sql("DELETE FROM calls WHERE $where;")
                        assertEquals("QA call log remains", "0", sql("SELECT count(*) FROM calls;"))
                        shell("am force-stop com.android.dialer")
                    }
                }
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}

class TnCalendarContractRuntimeTest {
    private fun now() = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")).apply {
        clear(); set(2026, java.util.Calendar.NOVEMBER, 5, 8, 0, 0)
    }
    private fun payload() = JSONObject().put("type", "Reminder").put("task", "TN calendar contract")
        .put("year", 2026).put("month", 11).put("day", 15).put("hour", 10).put("minute", 30).put("repeatType", "None")

    @Test fun calendarPreservesTheOriginalOneHourDurationAndEveryRecurrence() {
        val expected = mapOf("None" to null, "Daily" to "FREQ=DAILY", "Weekly" to "FREQ=WEEKLY",
            "Weekdays" to "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", "Weekend" to "FREQ=WEEKLY;BYDAY=SA,SU")
        for ((repeat, rule) in expected) {
            val event = TnCalendarControls.event(payload().put("repeatType", repeat), now())
            assertEquals(rule, event.repeat)
            assertEquals(3600000, event.end - event.start)
            assertEquals("Asia/Ho_Chi_Minh", event.timezone)
            assertEquals("TN calendar contract", event.title)
        }
        assertEquals("TN alerts at the requested event time", 0, TnCalendarControls.EVENT_TIME_REMINDER_MINUTES)
    }

    @Test fun invalidDatesNumbersTitlesAndRepeatsAreRejectedBeforeAnyWrite() {
        for (input in listOf(payload().put("day", 31).put("month", 2), payload().put("hour", 24),
            payload().put("minute", "30"), payload().put("minute", 1.5), payload().put("year", 2025),
            payload().put("repeatType", "bad"), payload().put("task", "  "), payload().put("task", 7),
            payload().put("task", "x".repeat(201)), payload().put("type", "Alarm"))) {
            try { TnCalendarControls.event(input, now()); fail("Invalid calendar event accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}

/** Run without calendar grants, before the separately scoped Provider tests. */
class TnCalendarPermissionRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var savedAccessibilityFlags: Int? = null
    @org.junit.After fun restoreAccessibilityConfiguration() {
        savedAccessibilityFlags?.let { saved ->
            val automation = instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply { flags = saved }
        }
    }
    private fun payload() = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_MONTH, 10) }.let {
        JSONObject().put("type", "Reminder").put("task", "TN_QA_permission")
            .put("year", it.get(java.util.Calendar.YEAR)).put("month", it.get(java.util.Calendar.MONTH) + 1)
            .put("day", it.get(java.util.Calendar.DAY_OF_MONTH)).put("hour", it.get(java.util.Calendar.HOUR_OF_DAY))
            .put("minute", it.get(java.util.Calendar.MINUTE)).put("repeatType", "None")
    }

    @Test fun permissionDenialAndOwnershipCancellationNeverClaimAStoredEvent() {
        assertFalse("Run this group before granting Calendar permissions", TnCalendarControls.hasAccess(context))
        var calls = 0
        val details = JSONObject()
        assertEquals("failed", TnCalendarControls(context, { true }) { calls++; false }.execute(payload(), details))
        assertEquals(1, calls)
        assertEquals("calendar_permission_required", details.getString("reason"))
        assertFalse(details.getBoolean("completion_verified"))
        assertEquals("cancelled", TnCalendarControls(context, { false }) { calls++; true }.execute(payload(), JSONObject()))
        assertEquals(1, calls)
        val owner = AtomicBoolean(true)
        assertEquals("cancelled", TnCalendarControls(context, owner::get) { owner.set(false); true }.execute(payload(), JSONObject()))
    }

    private fun permissionButton(): android.view.accessibility.AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        if (savedAccessibilityFlags == null) savedAccessibilityFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val root = automation.rootInActiveWindow ?: return null
        return listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller").firstNotNullOfOrNull { pkg ->
            listOf("permission_deny_button", "permission_deny_and_dont_ask_again_button")
                .firstNotNullOfOrNull { root.findAccessibilityNodeInfosByViewId("$pkg:id/$it").firstOrNull() }
        }
    }

    private fun closePermissionDialog() {
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith("permissioncontroller") == true &&
            SystemClock.elapsedRealtime() < deadline) {
            permissionButton()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(50)
        }
        assertFalse("Permission controller was left over the next UI test",
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith("permissioncontroller") == true)
    }

    @Test fun osPermissionDenialReturnsToTheSameCommandWithoutGrantingAccess() {
        assertFalse(TnCalendarControls.hasAccess(context))
        TnIsolatedActivityPreferences(context).use {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                instrumentation.waitForIdleSync()
                assertTrue(MainActivity::class.java.getDeclaredField("resumed").apply { isAccessible = true }.getBoolean(activity))
                val method = MainActivity::class.java.getDeclaredMethod("requestCalendarAccess", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                val result = pool.submit<Boolean> { method.invoke(activity, { true }) as Boolean }
                val deadline = SystemClock.elapsedRealtime() + 8000
                var deny = permissionButton()
                while (deny == null && !result.isDone && SystemClock.elapsedRealtime() < deadline) { Thread.sleep(50); deny = permissionButton() }
                if (deny != null) assertTrue(deny.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                else assertTrue("OS must either show a prompt or return its previously fixed denial", result.isDone)
                assertFalse(result.get(5, TimeUnit.SECONDS))
                assertFalse(TnCalendarControls.hasAccess(context))
            } finally {
                closePermissionDialog()
                pool.shutdownNow()
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test fun aCancelledQueuedPermissionRequestCannotDisplayALateDialog() {
        assertFalse(TnCalendarControls.hasAccess(context))
        TnIsolatedActivityPreferences(context).use {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            val release = CountDownLatch(1)
            val blocked = CountDownLatch(1)
            val owner = AtomicBoolean(true)
            try {
                instrumentation.waitForIdleSync()
                Handler(Looper.getMainLooper()).post { blocked.countDown(); release.await(4, TimeUnit.SECONDS) }
                assertTrue(blocked.await(1, TimeUnit.SECONDS))
                val method = MainActivity::class.java.getDeclaredMethod("requestCalendarAccess", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                val started = CountDownLatch(1)
                val result = pool.submit<Boolean> { started.countDown(); method.invoke(activity, owner::get) as Boolean }
                assertTrue(started.await(1, TimeUnit.SECONDS))
                Thread.sleep(100)
                owner.set(false)
                assertFalse(result.get(1, TimeUnit.SECONDS))
                release.countDown()
                instrumentation.waitForIdleSync()
                assertNull("Cancelled request displayed a late permission dialog", permissionButton())
                assertFalse(TnCalendarControls.hasAccess(context))
            } finally {
                release.countDown(); pool.shutdownNow()
                closePermissionDialog()
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test fun aCalendarCallbackCannotAuthorizeThePendingPhoneCommand() {
        assertFalse(TnPhoneControls.hasAccess(context))
        TnIsolatedActivityPreferences(context).use {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                instrumentation.waitForIdleSync()
                val method = MainActivity::class.java.getDeclaredMethod("requestCallAccess", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                val result = pool.submit<Boolean> { method.invoke(activity, { true }) as Boolean }
                val deadline = SystemClock.elapsedRealtime() + 8000
                var deny = permissionButton()
                while (deny == null && !result.isDone && SystemClock.elapsedRealtime() < deadline) { Thread.sleep(50); deny = permissionButton() }
                if (deny == null) {
                    // Repeated QA runs may already have a fixed OS denial.
                    // In that state there is no pending dialog to cross-wire.
                    assertTrue(result.isDone)
                    assertFalse(result.get(1, TimeUnit.SECONDS))
                    assertFalse(TnPhoneControls.hasAccess(context))
                    return@use
                }
                instrumentation.runOnMainSync {
                    activity.onRequestPermissionsResult(42, arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR),
                        intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED, android.content.pm.PackageManager.PERMISSION_GRANTED))
                }
                assertFalse("Wrong request code released the phone command", result.isDone)
                assertTrue(deny!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                assertFalse(result.get(5, TimeUnit.SECONDS))
                assertFalse(TnPhoneControls.hasAccess(context))
            } finally {
                closePermissionDialog(); pool.shutdownNow()
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}

/** Host grants/restores Calendar permissions outside instrumentation to avoid killing its process. */
class TnCalendarProviderRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private val c get() = android.provider.CalendarContract.Calendars.CONTENT_URI
    private val e get() = android.provider.CalendarContract.Events.CONTENT_URI
    private val r get() = android.provider.CalendarContract.Reminders.CONTENT_URI
    private fun count(uri: android.net.Uri, selection: String? = null, args: Array<String>? = null) =
        requireNotNull(resolver.query(uri, arrayOf("_id"), selection, args, null)).use { it.count }
    private fun payload(label: String, repeat: String = "None") = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_MONTH, 10); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.let { date -> JSONObject().put("type", "Reminder").put("task", label).put("year", date.get(java.util.Calendar.YEAR))
        .put("month", date.get(java.util.Calendar.MONTH) + 1).put("day", date.get(java.util.Calendar.DAY_OF_MONTH))
        .put("hour", date.get(java.util.Calendar.HOUR_OF_DAY)).put("minute", date.get(java.util.Calendar.MINUTE)).put("repeatType", repeat) }
    private fun calendar(name: String, supportsAlert: Boolean = true): android.net.Uri {
        val uri = c.buildUpon().appendQueryParameter("caller_is_syncadapter", "true")
            .appendQueryParameter("account_name", name).appendQueryParameter("account_type", "LOCAL").build()
        return requireNotNull(resolver.insert(uri, android.content.ContentValues().apply {
            put("account_name", name); put("account_type", "LOCAL"); put("name", name); put("calendar_displayName", name)
            put("ownerAccount", name); put("calendar_access_level", 700); put("visible", 1); put("sync_events", 1)
            put("calendar_timezone", java.util.TimeZone.getDefault().id); put("maxReminders", if (supportsAlert) 5 else 0)
            put("allowedReminders", "1"); put("isPrimary", 1)
        }))
    }
    private fun deleteCalendar(uri: android.net.Uri, name: String) = resolver.delete(uri.buildUpon()
        .appendQueryParameter("caller_is_syncadapter", "true").appendQueryParameter("account_name", name)
        .appendQueryParameter("account_type", "LOCAL").build(), null, null)
    private fun eventId(label: String): Long? = resolver.query(e, arrayOf("_id"), "title=?", arrayOf(label), null)?.use {
        if (it.moveToFirst()) it.getLong(0) else null
    }

    @Test fun realProviderPersistsEventsAndRecurrencesAtomicallyCancelsOwnedWritesAndLeavesNoFixtures() {
        assertEquals("TNNative166", shell("getprop ro.kernel.qemu.avd_name"))
        assertTrue(TnCalendarControls.hasAccess(context))
        assertEquals("Use only the isolated empty Calendar provider", 0, count(c))
        val baselineEvents = count(e)
        val baselineReminders = count(r)
        val name = "TN_QA_" + java.util.UUID.randomUUID().toString().replace("-", "")
        var unsupported: android.net.Uri? = null
        var writable: android.net.Uri? = null
        try {
            val absent = JSONObject()
            assertEquals("failed", TnCalendarControls(context, { true }).execute(payload(name), absent))
            assertEquals("calendar_no_writable_calendar", absent.getString("reason"))
            unsupported = calendar(name + "_unsupported", false)
            val incompatible = JSONObject()
            assertEquals("failed", TnCalendarControls(context, { true }).execute(payload(name), incompatible))
            assertEquals("calendar_alert_not_supported", incompatible.getString("reason"))
            writable = calendar(name)
            val calendarId = android.content.ContentUris.parseId(writable)
            // First primary calendar cannot alert; choose the next eligible calendar.
            for (repeat in listOf("None", "Daily", "Weekly", "Weekdays", "Weekend")) {
                val label = name + "_" + repeat
                val p = payload(label, repeat)
                val details = JSONObject()
                assertEquals("success", TnCalendarControls(context, { true }).execute(p, details))
                assertEquals("calendar_event_and_reminder_verified", details.getString("reason"))
                assertTrue(details.getBoolean("completion_verified"))
                assertFalse("Receipt must not expose a personal event title", details.toString().contains(label))
                val event = TnCalendarControls.event(p)
                val id = requireNotNull(eventId(label))
                resolver.query(android.content.ContentUris.withAppendedId(e, id),
                    arrayOf("calendar_id", "dtstart", "dtend", "duration", "rrule", "eventTimezone", "hasAlarm"), null, null, null)!!.use {
                    assertTrue(it.moveToFirst()); assertEquals(calendarId, it.getLong(0)); assertEquals(event.start, it.getLong(1))
                    if (repeat == "None") assertEquals(event.end, it.getLong(2)) else assertEquals("PT1H", it.getString(3))
                    assertEquals(event.repeat, it.getString(4)); assertEquals(event.timezone, it.getString(5)); assertEquals(1, it.getInt(6))
                }
                resolver.query(r, arrayOf("minutes", "method"), "event_id=?", arrayOf(id.toString()), null)!!.use {
                    assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(1, it.getInt(1))
                }
            }
            val rejectedLabel = name + "_atomic_rejection"
            val ops = TnCalendarControls.operations(TnCalendarControls.event(payload(rejectedLabel)), calendarId)
            ops[1] = android.content.ContentProviderOperation.newInsert(r).withValueBackReference("event_id", 0)
                .withValue("not_a_calendar_column", 1).build()
            try { resolver.applyBatch(android.provider.CalendarContract.AUTHORITY, ops); fail("Invalid batch succeeded") }
            catch (_: android.content.OperationApplicationException) { }
            catch (_: android.database.sqlite.SQLiteException) { }
            catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("not_a_calendar_column")) }
            assertNull("Failed reminder must roll back its event", eventId(rejectedLabel))
            val cancelledLabel = name + "_cancelled"
            assertEquals("cancelled", TnCalendarControls(context, { eventId(cancelledLabel) == null }).execute(payload(cancelledLabel), JSONObject()))
            assertNull("Cancelled owner left a persisted event", eventId(cancelledLabel))
            // Exercise the actual foreground adapter wiring without sending fabricated receipts to VPS.
            TnIsolatedActivityPreferences(context).use {
                val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                try {
                    val label = name + "_activity"
                    val method = MainActivity::class.java.getDeclaredMethod("executeDirectives", JSONObject::class.java, String::class.java,
                        String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                    val input = JSONObject().put("directives", JSONArray().put(JSONObject().put("type", "Alerts").put("payload", payload(label))))
                    assertEquals(TnDirectiveExecutor.Summary(), method.invoke(activity, input, "", "", { true }))
                    assertNotNull("Activity did not persist its reminder", eventId(label))
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        } finally {
            writable?.let { deleteCalendar(it, name) }
            unsupported?.let { deleteCalendar(it, name + "_unsupported") }
            assertEquals("Calendar fixture was not removed", 0, count(c))
            assertEquals("Calendar events were not restored", baselineEvents, count(e))
            assertEquals("Calendar reminders were not restored", baselineReminders, count(r))
        }
    }
}

class TnAlarmControlsRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun payload(date: java.util.Calendar, repeat: String = "None") = JSONObject().put("type", "Alarm")
        .put("year", date.get(java.util.Calendar.YEAR)).put("month", date.get(java.util.Calendar.MONTH) + 1)
        .put("day", date.get(java.util.Calendar.DAY_OF_MONTH)).put("hour", date.get(java.util.Calendar.HOUR_OF_DAY))
        .put("minute", date.get(java.util.Calendar.MINUTE)).put("repeatType", repeat)
    private fun now() = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")).apply {
        clear(); set(2026, java.util.Calendar.NOVEMBER, 5, 8, 0, 0)
    }

    @Test fun originalAlarmExtrasAndAllRepeatGroupsUseTheNextRepresentableLocalDate() {
        val clock = now()
        val expected = mapOf("None" to emptyList(), "Daily" to listOf(2, 3, 4, 5, 6, 7, 1),
            "Weekly" to listOf(5), "Weekdays" to listOf(2, 3, 4, 5, 6), "Weekend" to listOf(7, 1))
        for ((repeat, days) in expected) {
            val date = (clock.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, 10)
                if (repeat == "Weekend") add(java.util.Calendar.DAY_OF_MONTH, 2)
            }
            val intent = requireNotNull(TnAlarmControls.intent(payload(date, repeat), clock))
            assertEquals(android.provider.AlarmClock.ACTION_SET_ALARM, intent.action)
            assertEquals(10, intent.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1))
            assertEquals(0, intent.getIntExtra(android.provider.AlarmClock.EXTRA_MINUTES, -1))
            assertEquals(days, intent.getIntegerArrayListExtra(android.provider.AlarmClock.EXTRA_DAYS))
            assertTrue(intent.getBooleanExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false))
        }
    }

    @Test fun invalidOrUnrepresentableDatesNeverSilentlyBecomeAnEarlierAlarm() {
        val clock = now()
        val later = (clock.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, 2) }
        val valid = (clock.clone() as java.util.Calendar).apply { add(java.util.Calendar.HOUR_OF_DAY, 2) }
        for (input in listOf(payload(later), payload(valid).put("hour", 24), payload(valid).put("minute", -1),
            payload(valid).put("month", 2).put("day", 31), payload(valid).put("repeatType", "typo"),
            payload(valid).put("hour", 2.5), payload(valid).put("minute", "30"))) {
            try { TnAlarmControls.intent(input, clock); fail("Invalid alarm was translated") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun timerShowAndDismissUseBoundedClockActionsAndExplicitAmPm() {
        val timer = requireNotNull(TnAlarmControls.intent(JSONObject().put("type", "Timer").put("duration", 120)))
        assertEquals(android.provider.AlarmClock.ACTION_SET_TIMER, timer.action)
        assertEquals(120, timer.getIntExtra(android.provider.AlarmClock.EXTRA_LENGTH, -1))
        assertEquals(android.provider.AlarmClock.ACTION_SHOW_ALARMS, TnAlarmControls.intent(JSONObject().put("type", "ShowAlarms"))!!.action)
        val dismiss = requireNotNull(TnAlarmControls.intent(JSONObject().put("type", "DismissAlarm").put("searchMode", "time").put("hour", 13).put("minute", 30)))
        assertEquals(android.provider.AlarmClock.ACTION_DISMISS_ALARM, dismiss.action)
        assertEquals(1, dismiss.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1))
        assertTrue(dismiss.getBooleanExtra(android.provider.AlarmClock.EXTRA_IS_PM, false))
        assertFalse(dismiss.hasExtra(android.provider.AlarmClock.EXTRA_SKIP_UI))
        assertNull(TnAlarmControls.intent(JSONObject().put("type", "Reminder")))
        for (duration in listOf(0, -1, 86401)) {
            try { TnAlarmControls.intent(JSONObject().put("type", "Timer").put("duration", duration)); fail("Unbounded timer accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun cancelledOrUnsupportedRequestsNeverLaunchAndDispatchFailureDoesNotClaimCreation() {
        var calls = 0
        val cancelled = TnAlarmControls(context, { false }) { calls++; true }
        assertEquals("cancelled", cancelled.execute(JSONObject().put("type", "ShowAlarms"), JSONObject()))
        val adapter = TnAlarmControls(context, { true }) { calls++; false }
        assertEquals("unsupported", adapter.execute(JSONObject().put("type", "Reminder"), JSONObject()))
        assertEquals(0, calls)
        val details = JSONObject()
        assertEquals("failed", adapter.execute(JSONObject().put("type", "ShowAlarms"), details))
        assertEquals(1, calls)
        assertFalse(details.getBoolean("completion_verified"))
        assertFalse(details.getBoolean("intent_started"))
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun sql(statement: String): String {
        require(!statement.contains('"') && !statement.contains('$') && !statement.contains('`') && !statement.contains('\n'))
        // UiAutomation on API 30 uses Runtime.exec tokenization, not shell
        // quote parsing. These fixed test queries contain no spaced literals;
        // SQL token comments keep the statement in one argv entry.
        val argument = statement.replace(" ", "/**/")
        return shell("su 0 sqlite3 /data/user_de/0/com.google.android.deskclock/databases/alarms.db $argument")
    }
    private fun templateDigest() = java.security.MessageDigest.getInstance("SHA-256").digest(sql(
        "SELECT _id,external_uuid,hour,minutes,daysofweek,enabled,vibrate,hex(label),hex(ringtone),delete_after_use,hex(workflow_label),hex(workflow_data) FROM alarm_templates ORDER BY _id;").toByteArray()).toList()

    @Test fun sharedActivityExecutorCreatesOneRealClockAlarmThenRemovesOnlyThatTestAlarm() {
        assertEquals("TNNative166", shell("getprop ro.kernel.qemu.avd_name"))
        assertTrue(android.os.Build.FINGERPRINT.contains("generic"))
        shell("am start -a android.intent.action.SHOW_ALARMS -p com.google.android.deskclock")
        assertEquals("Do not interfere with any enabled alarm", "0", sql("SELECT count(*) FROM alarm_templates WHERE enabled=1;"))
        val baseline = templateDigest()
        val label = "TN_QA_" + java.util.UUID.randomUUID().toString().replace("-", "")
        assertEquals("0", sql("SELECT count(*) FROM alarm_templates WHERE label='$label';"))
        TnIsolatedActivityPreferences(context).use {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                val date = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 2); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
                val p = payload(date).put("message", label)
                val input = JSONObject().put("directives", JSONArray().put(JSONObject().put("type", "Alerts").put("payload", p)))
                val method = MainActivity::class.java.getDeclaredMethod("executeDirectives", JSONObject::class.java, String::class.java, String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
                val result = method.invoke(activity, input, "", "", { true }) as TnDirectiveExecutor.Summary
                assertEquals(TnDirectiveExecutor.Summary(interactionRequired = 1), result)
                val deadline = SystemClock.elapsedRealtime() + 8000
                while (sql("SELECT count(*) FROM alarm_templates WHERE label='$label' AND enabled=1;") != "1" && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
                assertEquals("Real Clock must persist the requested alarm", "1", sql("SELECT count(*) FROM alarm_templates WHERE label='$label' AND enabled=1;"))
                assertEquals("${date.get(java.util.Calendar.HOUR_OF_DAY)}|${date.get(java.util.Calendar.MINUTE)}|0", sql("SELECT hour,minutes,daysofweek FROM alarm_templates WHERE label='$label';"))
            } finally {
                // The isolated Clock had zero enabled alarms before this test.
                // Stop its test schedule, delete only the unique TN label, and
                // compare every pre-existing template without printing labels.
                shell("am force-stop com.google.android.deskclock")
                sql("PRAGMA foreign_keys=ON; BEGIN; DELETE FROM alarm_instances WHERE alarm_id IN (SELECT _id FROM alarm_templates WHERE label='$label'); DELETE FROM alarm_templates WHERE label='$label'; COMMIT;")
                assertEquals("0", sql("SELECT count(*) FROM alarm_templates WHERE label='$label';"))
                assertEquals("Pre-existing Clock templates changed", baseline, templateDigest())
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}
