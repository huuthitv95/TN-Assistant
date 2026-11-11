package vn.tn.assistant.ui

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.audio.TnWakeService

/** Real VPS + model + AudioRecord startup. This is not human wake-word acceptance. */
class TnWakeLiveStartupRuntimeTest {
    @Test fun visibleMicrophoneTapStartsDirectCommandCaptureWithoutRequestingAWakePhrase() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnWakeLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val avd = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu.avd_name"))
            .bufferedReader().use { it.readText().trim() }
        assertEquals("TNNative166", avd)
        assertTrue(vn.tn.assistant.auth.TnSessionStore.get(context).hasSession())
        assertTrue(TnWakeService.microphoneAllowed(context))
        assertFalse("Do not interfere with an existing background recording", TnWakeService.isRunning())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val recorderField = MainActivity::class.java.getDeclaredField("audioRecorder").apply { isAccessible = true }
        val captureField = MainActivity::class.java.getDeclaredField("foregroundCapture").apply { isAccessible = true }
        try {
            Thread.sleep(1000)
            instrumentation.runOnMainSync { MainActivity::class.java.getDeclaredMethod("startVoice").apply { isAccessible = true }.invoke(activity) }
            val deadline = SystemClock.elapsedRealtime() + 10000
            while ((recorderField.get(activity) as? android.media.AudioRecord)?.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertEquals(android.media.AudioRecord.RECORDSTATE_RECORDING, (recorderField.get(activity) as? android.media.AudioRecord)?.recordingState)
            Thread.sleep(150)
            instrumentation.runOnMainSync {
                fun texts(v: View): List<TextView> = (if (v is TextView) listOf(v) else emptyList()) +
                    if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList()
                assertFalse("A microphone tap must not instruct the user to repeat a wake phrase", texts(activity.window.decorView).any { it.text.contains("Bắt đầu bằng") })
                assertTrue("Move the real activity to the background", activity.moveTaskToBack(true))
            }
            val releaseDeadline = SystemClock.elapsedRealtime() + 5000
            while (recorderField.get(activity) != null && SystemClock.elapsedRealtime() < releaseDeadline) Thread.sleep(50)
            assertNull("A visible microphone tap must not keep recording in the background", recorderField.get(activity))
        } finally {
            (captureField.get(activity) as? vn.tn.assistant.audio.TnRecordingSessionGate.Capture)?.cancel()
            runCatching { (recorderField.get(activity) as? android.media.AudioRecord)?.stop() }
            instrumentation.runOnMainSync { activity.finish() }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (recorderField.get(activity) != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertNull("Closing the visible capture must release AudioRecord", recorderField.get(activity))
        }
    }
    @Test fun enableWaitsForRealAudioAndReportsStateInsideTheWakePage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnWakeLive") == "true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val avd=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu.avd_name")).bufferedReader().use {it.readText().trim()}
        assertEquals("TNNative166",avd)
        assertTrue("Seed authenticated TN session first",vn.tn.assistant.auth.TnSessionStore.get(context).hasSession())
        assertTrue("Grant microphone explicitly on the isolated test AVD",TnWakeService.microphoneAllowed(context))
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        Thread.sleep(1500)
        val enable=MainActivity::class.java.getDeclaredMethod("setWakeEnabled",Boolean::class.javaPrimitiveType).apply {isAccessible=true}
        try {
            fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("renderSettings",android.os.Bundle::class.java).apply {isAccessible=true}.invoke(activity,null)
            }
            var loaded=false
            val loadDeadline=SystemClock.elapsedRealtime()+25000
            while(!loaded && SystemClock.elapsedRealtime()<loadDeadline) {
                instrumentation.runOnMainSync { loaded=views(activity.window.decorView).any {it.contentDescription=="Premium"} }
                if(!loaded)Thread.sleep(100)
            }
            assertTrue("Actual VPS Admin settings must load",loaded)
            instrumentation.runOnMainSync {
                views(activity.window.decorView).single {it.contentDescription=="TN ơi"}.performClick()
                views(activity.window.decorView).single {it.contentDescription=="Kích hoạt TN bằng “TN ơi”: Bật"}.performClick()
            }
            val deadline=SystemClock.elapsedRealtime()+60000
            while(!TnWakeService.isReady() && SystemClock.elapsedRealtime()<deadline && TnWakeService.lastError(context)==null) Thread.sleep(100)
            assertTrue("Real wake startup failed: ${TnWakeService.lastError(context) ?: TnWakeService.status(context)}",TnWakeService.isReady())
            val frames=TnWakeService.capturedFrames();Thread.sleep(300)
            assertTrue("AudioRecord must actually receive new frames",TnWakeService.capturedFrames()>frames)
            assertTrue(context.getSharedPreferences("tn-session-v1",0).getBoolean("wake_word_enabled",false))
            instrumentation.runOnMainSync {
                val prefs=context.getSharedPreferences("tn-wake-state-test",0)
                val surface=TnUserSettingsSurface(context,prefs,"wake-state",{},{},{_,_->})
                surface.account(org.json.JSONObject().put("user",org.json.JSONObject().put("role","admin")))
                surface.restoreUiState(android.os.Bundle().apply {putString("detail","TN ơi")})
                fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
                assertTrue(views(surface).filterIsInstance<TextView>().any {it.text.toString()==TnWakeService.status(context)})
            }
        } finally {
            instrumentation.runOnMainSync {enable.invoke(activity,false)}
            val deadline=SystemClock.elapsedRealtime()+5000
            while(TnWakeService.isRecording() && SystemClock.elapsedRealtime()<deadline)Thread.sleep(50)
            assertFalse("Disable must release microphone",TnWakeService.isRecording())
            instrumentation.runOnMainSync {activity.finish()}
        }
    }
}
