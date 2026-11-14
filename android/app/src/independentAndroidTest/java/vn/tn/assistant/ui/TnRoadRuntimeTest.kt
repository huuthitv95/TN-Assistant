package vn.tn.assistant.ui

import android.location.Location
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.driving.TnRoadMatcher
import vn.tn.assistant.driving.TnRoadMonitor
import vn.tn.assistant.driving.TnRoadSound
import vn.tn.assistant.driving.TnRoadAlertAudio
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TnRoadRuntimeTest {
    private val road=TnRoadMatcher.Road("fixture","fixture",TnRoadMatcher.Point(10.0,106.0),0.0,
        TnRoadMatcher.Point(9.9998,106.0),TnRoadMatcher.Point(10.0002,106.0),50.0,System.currentTimeMillis(),"Đường kiểm thử")

    @Test fun packagedVoicesPlayBothAccentsAndCancelOnInvalidDataOrModeChange() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val errors=java.util.concurrent.atomic.AtomicInteger()
        TnIsolatedActivityPreferences(instrumentation.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                lateinit var player:TnRoadAlertAudio
                scenario.onActivity {player=TnRoadAlertAudio(it) {errors.incrementAndGet()}}
                try {
                    for(accent in listOf("south","north")) {
                        scenario.onActivity {
                            player.retain(true,"voice",accent)
                            assertTrue(player.play("voice",accent))
                            assertFalse("Duplicate voice must not queue",player.play("voice",accent))
                        }
                        var playing=false
                        val deadline=SystemClock.elapsedRealtime()+2000
                        while(!playing && SystemClock.elapsedRealtime()<deadline) {
                            SystemClock.sleep(25);scenario.onActivity {playing=player.playingVoice}
                        }
                        assertTrue("Packaged voice did not start: $accent",playing)
                        scenario.onActivity {
                            if(accent=="south") player.retain(false,"voice",accent)
                            else player.retain(true,"display",accent)
                            assertFalse(player.playingVoice)
                            // Closing while preparation is pending cannot start late audio.
                            player.retain(true,"voice",accent);assertTrue(player.play("voice",accent));player.stop()
                        }
                        SystemClock.sleep(250)
                        scenario.onActivity {assertFalse(player.playingVoice)}
                    }
                    scenario.onActivity {player.close();assertFalse(player.play("voice","south"));assertFalse(player.play("sound","south"))}
                    assertEquals(0,errors.get())
                } finally {scenario.onActivity {player.close()}}
            }
        }
    }

    @Test fun consumerCanCloseMonitorInsideLoadingCallback() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            lateinit var monitor:TnRoadMonitor
            monitor=TnRoadMonitor(instrumentation.targetContext,{monitor.close()},authorization={true},fetch={_,_->
                error("Closed monitor must not start network work")
            })
            try {
                monitor.onLocationChanged(Location("fixture").apply {
                    latitude=10.0;longitude=106.0;accuracy=5f;bearing=0f;speed=15f
                    elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                    if(android.os.Build.VERSION.SDK_INT>=26) {bearingAccuracyDegrees=1f;speedAccuracyMetersPerSecond=1f}
                })
            } finally {monitor.close()}
        }
    }

    @Test fun terminalBackendRejectionsStopFurtherLocationRequests() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for ((status,code,state) in listOf(Triple(401,"UNAUTHORIZED","session_expired"),
            Triple(403,"PREMIUM_REQUIRED","premium_required"),Triple(503,"DRIVING_DISABLED","driving_disabled"))) {
            val rejected=CountDownLatch(1)
            val requests=java.util.concurrent.atomic.AtomicInteger()
            lateinit var monitor:TnRoadMonitor
            fun location()=Location("fixture").apply {
                latitude=10.0;longitude=106.0;accuracy=5f;bearing=0f;speed=15f
                elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                if(android.os.Build.VERSION.SDK_INT>=26) {bearingAccuracyDegrees=1f;speedAccuracyMetersPerSecond=1f}
            }
            instrumentation.runOnMainSync {
                monitor=TnRoadMonitor(instrumentation.targetContext,{if(it.state==state) rejected.countDown()},authorization={true},fetch={_,_->
                    requests.incrementAndGet();throw vn.tn.assistant.api.TnServiceFailure(status,code)
                })
                monitor.onLocationChanged(location())
            }
            try {
                assertTrue("Missing terminal state $state",rejected.await(5,TimeUnit.SECONDS))
                instrumentation.runOnMainSync {monitor.onLocationChanged(location())}
                SystemClock.sleep(100)
                assertEquals(1,requests.get())
            } finally {instrumentation.runOnMainSync {monitor.close()}}
        }
    }

    @Test fun savedOffsetAndModeControlVisibleWarningAndUnknownDataClearsIt() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        TnIsolatedActivityPreferences(instrumentation.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                val preferences=activity.getSharedPreferences("tn-session-v1",android.content.Context.MODE_PRIVATE)
                preferences.edit().putString("traffic_warning_mode","display").putInt("traffic_overspeed_offset",5).commit()
                lateinit var callback:(TnRoadMatcher.Result)->Unit
                val dialog=TnRoadDialog(activity) { show -> callback=show;AutoCloseable {} }
                dialog.show()
                try {
                    val alert=descendants(dialog.window!!.decorView).single {it.tag=="tnRoadAlert"} as TextView
                    fun update(kmh:Double) {callback(TnRoadMatcher.Result("matched",road,
                        TnRoadMatcher.Fix(10.0,106.0,5.0,0.0,kmh/3.6,SystemClock.elapsedRealtime())))}
                    update(54.0);assertEquals("",alert.text.toString())
                    update(55.0);assertTrue(alert.text.contains("ngưỡng"))
                    callback(TnRoadMatcher.Result("ambiguous"));assertEquals("",alert.text.toString())
                    preferences.edit().putString("traffic_warning_mode","none").commit()
                    update(60.0);assertEquals("",alert.text.toString())
                } finally {dialog.dismiss()}
            } }
        }
    }

    @Test fun localSoundReleasesFocusAndCannotRestartAfterClose() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        TnIsolatedActivityPreferences(instrumentation.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var player:TnRoadSound
                scenario.onActivity { activity ->
                    player=TnRoadSound(activity)
                    try {
                        assertTrue(player.play());assertFalse(player.play())
                        player.stop();assertTrue(player.play())
                    } finally {player.close()}
                    assertFalse(player.play())
                }
                // Wait beyond both scheduled beeps; closing must remain terminal.
                SystemClock.sleep(1000)
                scenario.onActivity {assertFalse(player.play());player.close()}
            }
        }
    }

    @Test fun missingDataClearsSignAndDismissDiscardsLateCallback() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        TnIsolatedActivityPreferences(instrumentation.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
                lateinit var callback: (TnRoadMatcher.Result)->Unit
                var closes=0
                val dialog=TnRoadDialog(activity) { show -> callback=show;AutoCloseable {closes++} }
                dialog.show()
                try {
                    val views=descendants(dialog.window!!.decorView)
                    val sign=views.single {it.tag=="tnRoadSign"} as TextView
                    val state=views.single {it.tag=="tnRoadStatus"} as TextView
                    callback(TnRoadMatcher.Result("matched",road));assertEquals("50",sign.text);assertEquals(View.VISIBLE,sign.visibility)
                    callback(TnRoadMatcher.Result("ambiguous"));assertEquals(View.GONE,sign.visibility)
                    callback(TnRoadMatcher.Result("premium_required"));assertTrue(state.text.contains("chưa có quyền"))
                    val previous=state.text.toString()
                    dialog.dismiss();assertEquals(1,closes)
                    callback(TnRoadMatcher.Result("matched",road));assertEquals(previous,state.text.toString())
                    assertEquals(View.GONE,sign.visibility)
                } finally {dialog.dismiss()}
                assertEquals(1,closes)
            } }
        }
    }

    @Test fun monitorRejectsDelayedReplyAfterCloseAndAccountChange() {
        // Explicit test-location input exercises the real monitor worker/lifecycle,
        // without registering a provider, changing permissions or contacting VPS.
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for (logout in listOf(false,true)) {
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val completed=CountDownLatch(1)
            val allowed=java.util.concurrent.atomic.AtomicBoolean(true)
            val states=mutableListOf<String>()
            lateinit var monitor:TnRoadMonitor
            instrumentation.runOnMainSync {
                monitor=TnRoadMonitor(instrumentation.targetContext,{states+=it.state},authorization={allowed.get()},fetch={_,time ->
                    entered.countDown()
                    // A server may finish even after cancellation; simulate that case.
                    while (release.count>0) try {release.await(50,TimeUnit.MILLISECONDS)} catch (_:InterruptedException) {}
                    completed.countDown();TnRoadMatcher.Snapshot(listOf(road),true,time)
                })
                monitor.onLocationChanged(Location("fixture").apply {
                    latitude=10.0;longitude=106.0;accuracy=5f;bearing=0f;speed=15f
                    elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                    if (android.os.Build.VERSION.SDK_INT>=26) {bearingAccuracyDegrees=1f;speedAccuracyMetersPerSecond=1f}
                })
            }
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                instrumentation.runOnMainSync {if (logout) allowed.set(false) else monitor.close()}
                release.countDown();assertTrue(completed.await(5,TimeUnit.SECONDS));SystemClock.sleep(200)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {assertFalse(states.contains("matched"))}
            } finally {release.countDown();instrumentation.runOnMainSync {monitor.close()}}
        }
    }
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {descendants(view.getChildAt(it))} else emptyList()
}
