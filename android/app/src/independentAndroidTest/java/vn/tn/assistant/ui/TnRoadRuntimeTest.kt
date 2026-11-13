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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TnRoadRuntimeTest {
    private val road=TnRoadMatcher.Road("fixture","fixture",TnRoadMatcher.Point(10.0,106.0),0.0,
        TnRoadMatcher.Point(9.9998,106.0),TnRoadMatcher.Point(10.0002,106.0),50.0,System.currentTimeMillis(),"Đường kiểm thử")

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
