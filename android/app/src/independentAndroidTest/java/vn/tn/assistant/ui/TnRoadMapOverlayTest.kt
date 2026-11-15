package vn.tn.assistant.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.driving.TnMapActivityMonitor
import vn.tn.assistant.driving.TnRoadMapOverlay
import vn.tn.assistant.driving.TnRoadMatcher

/** Uses real UsageEvents and a real overlay window above installed Google Maps.
 * Complete Maps onboarding and dismiss its permission prompts before this test.
 * Road data is a local fixture; this is not GPS/backend or entitlement acceptance.
 */
class TnRoadMapOverlayTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun await(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+10000
        while(SystemClock.elapsedRealtime()<deadline) {
            var ready=false
            instrumentation.runOnMainSync {ready=condition()}
            if(ready) return
            SystemClock.sleep(100)
        }
        fail(message)
    }
    private fun fix()=TnRoadMatcher.Fix(10.0,106.0,5.0,0.0,15.0,SystemClock.elapsedRealtime())
    private fun matched()=TnRoadMatcher.Result("matched",TnRoadMatcher.Road("test","test",TnRoadMatcher.Point(10.0,106.0),0.0,
        TnRoadMatcher.Point(9.9998,106.0),TnRoadMatcher.Point(10.0002,106.0),30.0,System.currentTimeMillis(),"Test"),fix())
    private fun home()=context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    @Test fun realMapTransitionsClearUnknownDisabledAndClosedOverlay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roadMap")=="granted")
        assertTrue(TnFloatingControlService.hasPermission(context))
        assertTrue(TnMapActivityMonitor.hasPermission(context))
        val maps=requireNotNull(context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps"))
        val floating=TnFloatingControlService.settings(context)
        val foregroundKey="tn_assistant_foreground_suppresses_overlay"
        val saved=floating.all.filterKeys {it in setOf(foregroundKey,TnFloatingControlService.KEY_HIDDEN)}
        var overlay:TnRoadMapOverlay?=null
        TnIsolatedActivityPreferences(context).use {
            val prefs=context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE)
            try {
                instrumentation.runOnMainSync {
                    floating.edit().putBoolean(foregroundKey,false).putBoolean(TnFloatingControlService.KEY_HIDDEN,false).commit()
                    prefs.edit().putBoolean("traffic_always_on_map",true).putString("traffic_warning_mode","display").commit()
                    overlay=TnRoadMapOverlay(context)
                    context.startActivity(maps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                await("Real Maps resume did not display the road window") {overlay!!.update(matched());overlay!!.isVisible}
                if(InstrumentationRegistry.getArguments().getString("roadMapScreenshot")=="true") {
                    val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    java.io.File(context.getExternalFilesDir(null),"road-map-acceptance.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    }
                    bitmap.recycle()
                }
                instrumentation.runOnMainSync {home()}
                await("Road window remained above Home") {overlay!!.update(matched());!overlay!!.isVisible}
                instrumentation.runOnMainSync {context.startActivity(maps)}
                await("Returning to Maps did not restore the road window") {overlay!!.update(matched());overlay!!.isVisible}
                instrumentation.runOnMainSync {
                    overlay!!.update(TnRoadMatcher.Result("limit_unknown"));assertFalse(overlay!!.isVisible)
                    overlay!!.update(matched().copy(fix=fix().copy(elapsedMs=SystemClock.elapsedRealtime()-10001)))
                    assertFalse("Old GPS retained a speed limit",overlay!!.isVisible)
                }
                await("Fresh data did not restore the window") {overlay!!.update(matched());overlay!!.isVisible}
                instrumentation.runOnMainSync {
                    prefs.edit().putBoolean("traffic_always_on_map",false).commit()
                    overlay!!.update(matched());assertFalse(overlay!!.isVisible)
                    prefs.edit().putBoolean("traffic_always_on_map",true).putString("traffic_warning_mode","none").commit()
                    overlay!!.update(matched());assertFalse(overlay!!.isVisible)
                    prefs.edit().putString("traffic_warning_mode","display").commit()
                }
                await("Display mode did not restore the window") {overlay!!.update(matched());overlay!!.isVisible}
                instrumentation.runOnMainSync {
                    floating.edit().putBoolean(TnFloatingControlService.KEY_HIDDEN,true).commit()
                    overlay!!.update(matched());assertFalse(overlay!!.isVisible)
                    floating.edit().putBoolean(TnFloatingControlService.KEY_HIDDEN,false).commit()
                    overlay!!.close()
                }
                SystemClock.sleep(1500)
                instrumentation.runOnMainSync {overlay!!.update(matched());assertFalse("Late callback reopened a closed window",overlay!!.isVisible)}
            } finally {
                instrumentation.runOnMainSync {overlay?.close();home()}
                val editor=floating.edit().remove(foregroundKey).remove(TnFloatingControlService.KEY_HIDDEN)
                saved.forEach {(key,value) ->editor.putBoolean(key,value as Boolean)}
                editor.commit()
            }
        }
    }
    @Test fun missingSpecialAccessCannotShowWindow() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roadMap")=="denied")
        assertFalse(TnMapActivityMonitor.hasPermission(context))
        TnIsolatedActivityPreferences(context).use {
            val prefs=context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE)
            var overlay:TnRoadMapOverlay?=null
            try {
                instrumentation.runOnMainSync {
                    prefs.edit().putBoolean("traffic_always_on_map",true).putString("traffic_warning_mode","display").commit()
                    overlay=TnRoadMapOverlay(context);overlay!!.update(matched())
                }
                SystemClock.sleep(1500)
                instrumentation.runOnMainSync {assertFalse(overlay!!.isVisible)}
            } finally {instrumentation.runOnMainSync {overlay?.close()}}
        }
    }
}
