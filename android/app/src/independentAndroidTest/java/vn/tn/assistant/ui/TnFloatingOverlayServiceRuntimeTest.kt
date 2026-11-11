package vn.tn.assistant.ui

import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the actual WindowManager overlay lifecycle on the isolated SDK AVD. */
class TnFloatingOverlayServiceRuntimeTest {
    @Test
    fun overlayAttachesWithTheFirstUseHintAndStopsCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val overlayAppOp = TnOverlayAppOpTestScope(context, "allow")
        try {
            val preferences = TnFloatingControlService.settings(context)
            val savedPreferences = preferences.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else requireNotNull(value) }
            check(preferences.edit()
                .putBoolean(TnFloatingControlService.KEY_ENABLED, false)
                .putBoolean(TnFloatingControlService.KEY_HIDDEN, false)
                .putBoolean(TnFloatingControlService.KEY_SUGGESTION_VISIBLE, true)
                .putString(TnFloatingControlService.KEY_SIZE, "standard")
                .putString(TnFloatingControlService.KEY_OPACITY, "auto")
                .commit())
            val foregroundWasSuppressed = TnFloatingControlService.isAppForegroundSuppressed(context)
            try {
                assertTrue("The test AppOp must permit overlay access", Settings.canDrawOverlays(context))
                // Instrumentation can force-stop the foreground Activity without delivering onPause.
                // Model the real post-background state before asserting WindowManager attachment.
                TnFloatingControlService.setAppForeground(context, false)
                try {
                    assertTrue("The foreground utility service should start", TnFloatingControlService.enable(context))
                    val deadline = System.currentTimeMillis() + 5_000
                    while (!TnFloatingControlService.isVisible() && System.currentTimeMillis() < deadline) Thread.sleep(50)
                    assertTrue("The overlay must attach to WindowManager", TnFloatingControlService.isVisible())
                    assertTrue("The first-use suggestion state remains enabled", preferences.getBoolean(TnFloatingControlService.KEY_SUGGESTION_VISIBLE, false))
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val instrumentation = InstrumentationRegistry.getInstrumentation()
                        lateinit var overlay: TnFloatingClusterView
                        instrumentation.runOnMainSync {
                            overlay = android.view.inspector.WindowInspector.getGlobalWindowViews()
                                .filterIsInstance<TnFloatingClusterView>().single { it.isAttachedToWindow }
                            org.junit.Assert.assertEquals("Auto begins fully visible", 1f, overlay.alpha, .01f)
                        }
                        fun awaitOpacity(expected: Float, timeout: Long) {
                            val until = android.os.SystemClock.uptimeMillis() + timeout
                            var alpha = -1f
                            do {
                                instrumentation.runOnMainSync { alpha = overlay.alpha }
                                if (kotlin.math.abs(alpha - expected) <= .015f) return
                                Thread.sleep(50)
                            } while (android.os.SystemClock.uptimeMillis() < until)
                            org.junit.Assert.fail("Expected actual overlay opacity $expected, got $alpha")
                        }
                        awaitOpacity(.6f, 5500)
                        instrumentation.runOnMainSync {
                            val time = android.os.SystemClock.uptimeMillis()
                            val x = overlay.anchorCenterX.toFloat(); val y = overlay.anchorCenterY.toFloat()
                            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_CANCEL)) {
                                val event = android.view.MotionEvent.obtain(time, time, action, x, y, 0)
                                try { overlay.dispatchTouchEvent(event) } finally { event.recycle() }
                            }
                        }
                        awaitOpacity(1f, 1200)
                        awaitOpacity(.6f, 5500)
                        check(preferences.edit().putString(TnFloatingControlService.KEY_OPACITY, "normal").commit())
                        awaitOpacity(1f, 1200)
                        Thread.sleep(4300)
                        instrumentation.runOnMainSync {
                            org.junit.Assert.assertEquals("Fixed Normal must not inherit the Auto idle timer", 1f, overlay.alpha, .01f)
                        }
                    }
                } finally {
                    TnFloatingControlService.disable(context)
                }

                val stopDeadline = System.currentTimeMillis() + 5_000
                while (TnFloatingControlService.isVisible() && System.currentTimeMillis() < stopDeadline) Thread.sleep(50)
                assertFalse("Disabling the utility must remove the overlay", TnFloatingControlService.isVisible())
            } finally {
                TnFloatingControlService.setAppForeground(context, foregroundWasSuppressed)
                val editor = preferences.edit().clear()
                savedPreferences.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                check(editor.commit())
            }
        } finally {
            overlayAppOp.close()
        }
    }
}
