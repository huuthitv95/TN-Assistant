package vn.tn.assistant.ui

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import vn.tn.assistant.driving.TnRoadMatcher
import vn.tn.assistant.driving.TnRoadMonitor
import vn.tn.assistant.driving.TnRoadService
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real SDK location/FGS/notification lifecycle; road response is a replay, not production acceptance. */
class TnRoadServiceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    private fun await(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+8000
        while(SystemClock.elapsedRealtime()<deadline) {
            var ready=false
            instrumentation.runOnMainSync {ready=condition()}
            if(ready) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    @Test fun tripSurvivesActivityStopAndStopsFromNotificationLogoutAndMode() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roadService")=="granted")
        if(Build.VERSION.SDK_INT<26) {assumeTrue(false);return}
        assertTrue(TnRoadMonitor.preciseAllowed(context))
        assertTrue(TnRoadService.notificationsAllowed(context))
        val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val notifications=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val input=JSONObject(File(context.getExternalFilesDir(null),"road-acceptance.json").readText())
        val query=input.getJSONObject("query")
        fun location()=Location(LocationManager.GPS_PROVIDER).apply {
            latitude=query.getDouble("lat");longitude=query.getDouble("lon")
            accuracy=5f;bearing=query.getDouble("bearing").toFloat();speed=15f
            if(Build.VERSION.SDK_INT>=26) {bearingAccuracyDegrees=1f;speedAccuracyMetersPerSecond=1f}
            time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
        }
        for (ending in listOf("notification","logout","mode")) {
            TnIsolatedActivityPreferences(context).use {
                ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                    var registered=false
                    var subscription:AutoCloseable?=null
                    val requests=AtomicInteger()
                    var matches=0
                    var dialog:TnRoadDialog?=null
                    try {
                        scenario.onActivity {activity ->
                            // Local ownership marker only. Never passed to a network client.
                            context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE).edit()
                                .putString("refresh_ciphertext","instrumentation-only-no-network")
                                .putString("traffic_warning_mode",if(ending=="notification") "sound" else "voice")
                                .putString("agent_voice",if(ending=="mode") "north" else "south")
                                .putInt("traffic_overspeed_offset",0).commit()
                            TnRoadService.testFetch={_,now ->requests.incrementAndGet();TnRoadMatcher.parse(input.getJSONObject("response"),now)}
                            @Suppress("DEPRECATION")
                            manager.addTestProvider(LocationManager.GPS_PROVIDER,false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE)
                            registered=true;manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true)
                            assertTrue(TnRoadService.start(activity))
                        }
                        await("Location FGS did not start") {TnRoadService.isRunning()}
                        scenario.onActivity {activity ->
                            subscription=TnRoadService.observeIfRunning {if(it.state=="matched") matches++}
                            assertNotNull(subscription)
                            dialog=TnRoadDialog(activity).also {it.show();it.dismiss()}
                            assertTrue("Closing viewer stopped shared trip",TnRoadService.isRunning())
                        }
                        scenario.moveToState(Lifecycle.State.CREATED)
                        repeat(3) {manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,location());SystemClock.sleep(1100)}
                        await("Background GPS did not match replayed road") {matches>0}
                        assertTrue(requests.get()>0)
                        val notification=notifications.activeNotifications.single {it.id==4601}.notification
                        val text=notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                        assertTrue(text,text.contains("30 km/h"))
                        assertTrue("Overspeed policy did not run in background: $text",text.contains("ngưỡng"))
                        assertFalse("Background sound failed: $text",text.contains("chưa phát"))
                        assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                        when(ending) {
                            "notification" -> notification.actions.single {it.title=="DỪNG"}.actionIntent.send()
                            "logout" -> instrumentation.runOnMainSync {TnSessionStore.get(context).clear()}
                            else -> context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE).edit().putString("traffic_warning_mode","none").commit()
                        }
                        await("Trip did not stop after $ending") {!TnRoadService.isRunning()}
                        await("Foreground notification remained after $ending") {notifications.activeNotifications.none {it.id==4601}}
                        var before=0
                        instrumentation.runOnMainSync {before=matches}
                        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,location());SystemClock.sleep(1200)
                        instrumentation.runOnMainSync {assertEquals("GPS delivered after $ending",before,matches)}
                    } finally {
                        instrumentation.runOnMainSync {
                            dialog?.dismiss();subscription?.close();TnRoadService.stop(context)
                            TnRoadService.testFetch=null
                            // Clear the marker before bringing ActivityScenario back to foreground.
                            context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE).edit().remove("refresh_ciphertext").commit()
                        }
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        if(registered) manager.removeTestProvider(LocationManager.GPS_PROVIDER)
                    }
                }
            }
        }
    }

    @Test fun serviceRefusesMissingSessionAndMissingLocationPermission() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roadService")=="denied")
        assertFalse(TnRoadMonitor.preciseAllowed(context))
        TnIsolatedActivityPreferences(context).use {
            instrumentation.runOnMainSync {
                assertFalse(TnRoadService.start(context))
                context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE).edit()
                    .putString("refresh_ciphertext","instrumentation-only-no-network").commit()
                assertFalse(TnRoadService.start(context));assertFalse(TnRoadService.isRunning())
            }
        }
    }
}
