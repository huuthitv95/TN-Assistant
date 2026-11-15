package vn.tn.assistant.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.driving.TnMapActivityMonitor
import vn.tn.assistant.driving.TnRoadMapOverlay
import vn.tn.assistant.driving.TnRoadMatcher

/** Physical input and rotation against a real WindowManager overlay; local road fixture only. */
class TnRoadMapInteractionTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val automation get()=instrumentation.uiAutomation
    private fun findSign(node:AccessibilityNodeInfo?):Rect? {
        if(node==null) return null
        if(node.contentDescription?.startsWith("TN: giới hạn tham khảo")==true)
            return Rect().also {node.getBoundsInScreen(it)}
        for(i in 0 until node.childCount) findSign(node.getChild(i))?.let {return it}
        return null
    }
    private fun bounds():Rect?=automation.windows.firstNotNullOfOrNull {findSign(it.root)}
    private fun usageAccess(mode:String) {
        require(mode in setOf("allow","ignore"))
        check(context.packageName=="vn.tn.assistant.preview")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "appops set vn.tn.assistant.preview GET_USAGE_STATS $mode")).use {it.readBytes()}
    }
    private fun waitFor(message:String,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+12000
        while(SystemClock.elapsedRealtime()<deadline) {if(condition()) return;SystemClock.sleep(100)}
        fail(message)
    }
    private fun touch(fromX:Float,fromY:Float,toX:Float=fromX,toY:Float=fromY) {
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply {source=InputDevice.SOURCE_TOUCHSCREEN}
            try {assertTrue(automation.injectInputEvent(event,true))} finally {event.recycle()}
        }
        send(MotionEvent.ACTION_DOWN,fromX,fromY)
        if(fromX!=toX || fromY!=toY) repeat(12) {index ->
            SystemClock.sleep(20);val f=(index+1)/12f
            send(MotionEvent.ACTION_MOVE,fromX+(toX-fromX)*f,fromY+(toY-fromY)*f)
        }
        send(MotionEvent.ACTION_UP,toX,toY)
    }
    private fun matched():TnRoadMatcher.Result {
        val fix=TnRoadMatcher.Fix(10.0,106.0,5.0,0.0,15.0,SystemClock.elapsedRealtime())
        return TnRoadMatcher.Result("matched",TnRoadMatcher.Road("test","test",TnRoadMatcher.Point(10.0,106.0),0.0,
            TnRoadMatcher.Point(9.9998,106.0),TnRoadMatcher.Point(10.0002,106.0),30.0,System.currentTimeMillis(),"Test"),fix)
    }
    @Test fun dragRotateAndTapUseRealWindowAndPreserveLoginGate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roadMap")=="granted")
        assertTrue(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        assertTrue(TnMapActivityMonitor.hasPermission(context))
        val info=automation.serviceInfo
        val oldFlags=info.flags
        val autoRotate=Settings.System.getInt(context.contentResolver,Settings.System.ACCELEROMETER_ROTATION,0)
        val oldRotation=Settings.System.getInt(context.contentResolver,Settings.System.USER_ROTATION,0)
        val floating=TnFloatingControlService.settings(context)
        val key="tn_assistant_foreground_suppresses_overlay"
        val saved=floating.all.filterKeys {it in setOf(key,TnFloatingControlService.KEY_HIDDEN)}
        val main=Handler(Looper.getMainLooper())
        var overlay:TnRoadMapOverlay?=null
        val tick=object:Runnable {override fun run() {overlay?.update(matched());main.postDelayed(this,500)}}
        TnIsolatedActivityPreferences(context).use {
            try {
                info.flags=oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                automation.serviceInfo=info
                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1000)
                instrumentation.runOnMainSync {
                    floating.edit().putBoolean(key,false).putBoolean(TnFloatingControlService.KEY_HIDDEN,false).commit()
                    context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE).edit()
                        .putBoolean("traffic_always_on_map",true).putString("traffic_warning_mode","display").commit()
                    overlay=TnRoadMapOverlay(context);main.post(tick)
                    context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                waitFor("Map sign was not attached") {
                    var visible=false
                    instrumentation.runOnMainSync {visible=overlay?.isVisible==true}
                    visible
                }
                waitFor("Map sign did not appear in accessibility windows") {bounds()?.isEmpty==false}
                val original=requireNotNull(bounds())
                val bitmap=requireNotNull(automation.takeScreenshot())
                val screenWidth=bitmap.width;val screenHeight=bitmap.height;bitmap.recycle()
                touch(original.exactCenterX(),original.exactCenterY(),screenWidth*.7f,screenHeight*.75f)
                waitFor("Dragging did not move the window") {bounds()?.let {it.top>original.top+100 && it.left>original.left+100}==true}
                usageAccess("ignore")
                assertFalse(TnMapActivityMonitor.hasPermission(context))
                waitFor("Revoked usage access left a speed sign on screen") {bounds()==null}
                SystemClock.sleep(1200)
                assertNull("A delayed callback restored the sign without usage access",bounds())
                usageAccess("allow")
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                SystemClock.sleep(1000)
                context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitFor("Granting usage access and reopening Maps did not restore the sign") {bounds()?.isEmpty==false}
                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
                waitFor("Rotation left the sign outside the usable screen") {
                    val shot=automation.takeScreenshot() ?: return@waitFor false
                    val w=shot.width;val h=shot.height;shot.recycle()
                    val b=bounds()
                    w>h && b!=null && !b.isEmpty && b.left>=0 && b.top>=0 && b.right<=w && b.bottom<=h
                }
                val rotated=requireNotNull(bounds())
                // Give rotation/layout completion an extra frame before physical input.
                SystemClock.sleep(300)
                touch(rotated.exactCenterX(),rotated.exactCenterY())
                waitFor("Tapping the sign did not open TN through the traffic intent") {
                    var opened=false
                    instrumentation.runOnMainSync {
                        opened=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().any {it.intent.getBooleanExtra(TnFloatingControlService.EXTRA_OPEN_TRAFFIC,false)}
                    }
                    opened
                }
                waitFor("Sign covered the TN activity") {bounds()==null}
                assertFalse("Tap fabricated an authenticated session",
                    context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE).contains("refresh_ciphertext"))
            } finally {
                instrumentation.runOnMainSync {
                    main.removeCallbacksAndMessages(null);overlay?.close()
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().forEach {it.finish()}
                }
                usageAccess("allow") // The invoking host restores its original appop after the test.
                automation.setRotation(oldRotation)
                if(autoRotate==1) automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
                // UiAutomation unfreezes rotation but may retain the last test angle
                // in USER_ROTATION. Restore the saved setting as well as auto-rotate.
                check(oldRotation in 0..3)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                    "settings put system user_rotation $oldRotation")).use {it.readBytes()}
                assertEquals(oldRotation,Settings.System.getInt(context.contentResolver,Settings.System.USER_ROTATION,0))
                assertEquals(autoRotate,Settings.System.getInt(context.contentResolver,Settings.System.ACCELEROMETER_ROTATION,0))
                info.flags=oldFlags;automation.serviceInfo=info
                val editor=floating.edit().remove(key).remove(TnFloatingControlService.KEY_HIDDEN)
                saved.forEach {(k,v) ->editor.putBoolean(k,v as Boolean)};editor.commit()
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
