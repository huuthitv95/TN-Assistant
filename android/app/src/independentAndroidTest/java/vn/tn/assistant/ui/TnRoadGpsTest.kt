package vn.tn.assistant.ui

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.driving.TnRoadMatcher
import vn.tn.assistant.driving.TnRoadMonitor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit SDK-emulator tests. The harness restores runtime permissions/appops. */
class TnRoadGpsTest {
    @Test fun deniedPrecisePermissionStopsBeforeLocationOrNetwork() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roadGps")=="denied")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertFalse(TnRoadMonitor.preciseAllowed(context))
        instrumentation.runOnMainSync {
            val states=mutableListOf<String>()
            val monitor=TnRoadMonitor(context,{states+=it.state},authorization={true},fetch={_,_->error("No request allowed")})
            try {monitor.start();assertEquals(listOf("permission_required"),states)} finally {monitor.close()}
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    @Test fun systemGpsFeedsVisibleDialogAndDismissStopsDelivery() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roadGps")=="granted")
        if (android.os.Build.VERSION.SDK_INT<26) { assumeTrue(false);return }
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertTrue(TnRoadMonitor.preciseAllowed(context))
        val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val input=JSONObject(File(context.getExternalFilesDir(null),"road-acceptance.json").readText())
        val query=input.getJSONObject("query")
        val matched=CountDownLatch(1)
        var events=0
        lateinit var dialog:TnRoadDialog
        var dialogCreated=false
        var registered=false
        fun location()=Location(LocationManager.GPS_PROVIDER).apply {
            latitude=query.getDouble("lat");longitude=query.getDouble("lon")
            accuracy=5f;bearing=query.getDouble("bearing").toFloat();speed=15f
            if (android.os.Build.VERSION.SDK_INT>=26) {bearingAccuracyDegrees=1f;speedAccuracyMetersPerSecond=1f}
            time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
        }
        TnIsolatedActivityPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        @Suppress("DEPRECATION")
                        manager.addTestProvider(LocationManager.GPS_PROVIDER,false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE)
                        registered=true;manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true)
                        dialog=TnRoadDialog(activity) { show ->
                            TnRoadMonitor(activity,{result -> events++;show(result);if(result.state=="matched") matched.countDown()},
                                authorization={true},fetch={_,now ->TnRoadMatcher.parse(input.getJSONObject("response"),now)}).also {it.start()}
                        }
                        dialogCreated=true;dialog.show()
                    }
                    repeat(5) {if(matched.count>0) {manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,location());matched.await(1,TimeUnit.SECONDS)}}
                    assertTrue("Real LocationManager callback did not reach the dialog",matched.await(5,TimeUnit.SECONDS))
                    var before=0
                    scenario.onActivity {
                        val sign=dialog.window!!.decorView.findViewWithTag<TextView>("tnRoadSign")
                        assertEquals("30",sign.text.toString())
                        dialog.dismiss();before=events
                    }
                    manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,location())
                    SystemClock.sleep(1200);instrumentation.waitForIdleSync()
                    scenario.onActivity {assertEquals("Closed screen received location/timer updates",before,events)}
                } finally {
                    scenario.onActivity {if(dialogCreated) dialog.dismiss()}
                    if(registered) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
                }
            }
        }
    }
}
