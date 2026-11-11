package vn.tn.assistant.audio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.api.TnVoiceBackendClient
import vn.tn.assistant.auth.TnSessionStore

/** Run only on the isolated authorized AVD after real TN login; no synthetic entitlement. */
class TnWakeServiceRuntimeTest {
    @Test fun realAdminSwitchStartsAnActualBackgroundMicrophoneAndStopReleasesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Explicit live microphone test required", InstrumentationRegistry.getArguments().getString("tnLiveWake") == "true")
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertTrue("Use a real signed-in session", TnSessionStore.get(context).hasSession())
        assertFalse("Never interrupt an already running microphone session", TnWakeService.isRunning())
        val client = TnVoiceBackendClient(context)
        val prior = client.json("/v1/settings").optBoolean("wake_word_enabled")
        var activity: Activity? = null
        try {
            client.json("/v1/settings", JSONObject().put("wake_word_enabled", false), "PUT")
            context.getSharedPreferences("tn-session-v1", 0).edit().putBoolean("wake_word_enabled", false).commit()
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            fun find(description: String) = views(requireNotNull(activity).findViewById(android.R.id.content)).firstOrNull { it.contentDescription == description }
            await("Authenticated home must be ready") { find("CÀI ĐẶT") != null }
            instrumentation.runOnMainSync { find("CÀI ĐẶT")!!.performClick() }
            await("Account details must come from the real VPS") { find("Premium") != null }
            instrumentation.runOnMainSync { find("TN ơi")!!.performClick() }
            instrumentation.runOnMainSync { requireNotNull(find("Kích hoạt TN bằng “TN ơi”: Bật")).performClick() }
            await("Actual AudioRecord must start, not just a saved switch; error=${TnWakeService.lastError(context)}", 60000) {
                TnWakeService.isRecording() && TnWakeService.capturedFrames() > 2
            }
            assertTrue(TnWakeService.consented(context))
            assertTrue(client.json("/v1/settings").optBoolean("wake_word_enabled"))
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            val before = TnWakeService.capturedFrames()
            await("A backgrounded Activity must not stop the opt-in service") { TnWakeService.capturedFrames() > before + 10 }
            instrumentation.runOnMainSync { activity?.finish() }
            val afterFinish = TnWakeService.capturedFrames()
            await("Finishing the screen keeps the explicitly enabled microphone foreground service") { TnWakeService.capturedFrames() > afterFinish + 10 }
            TnWakeService.disable(context)
            await("Stop releases microphone and foreground service") { !TnWakeService.isRunning() && !TnWakeService.isRecording() && !TnRecordingSessionGate.process.isActive }
            assertFalse(TnWakeService.consented(context))
        } finally {
            TnWakeService.disable(context)
            instrumentation.runOnMainSync { activity?.finish() }
            client.json("/v1/settings", JSONObject().put("wake_word_enabled", prior), "PUT")
            context.getSharedPreferences("tn-session-v1", 0).edit().putBoolean("wake_word_enabled", false).commit()
            client.cancel()
        }
    }
    private fun await(message: String, timeout: Long = 15000, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            var done = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { done = ready() }
            if (done) return
            Thread.sleep(50)
        }
        fail(message)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
