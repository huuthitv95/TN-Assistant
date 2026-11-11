package vn.tn.assistant.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity

/** Real attached-window motion; constructor-only fixtures cannot prove an entry/exit effect. */
class TnSettingsMotionRuntimeTest {
    @Test fun settingsEntersFromBelowAndClosesOnlyAfterSlidingOut() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnMotionUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1_500)
        lateinit var surface: TnUserSettingsSurface
        var closedAt = 0L
        var closeStarted = 0L
        instrumentation.runOnMainSync {
            surface = TnUserSettingsSurface(activity,
                context.getSharedPreferences("tn-ui-motion-test-only", Context.MODE_PRIVATE),
                "motion-fixture", { closedAt = android.os.SystemClock.uptimeMillis() }, {}, { _, _ -> })
            activity.setContentView(surface)
        }
        fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
        var enteredFromBelow = false
        val deadline = android.os.SystemClock.uptimeMillis() + 1_500
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val nav = descendants(surface).first { it.tag == "tnSettingsNavigation" }
                val sidebar = nav.parent as View
                if (sidebar.translationY > surface.height / 4) enteredFromBelow = true
            }
            if (enteredFromBelow) break
            Thread.sleep(16)
        }
        assertTrue("Entry must visibly start below the viewport", enteredFromBelow)
        Thread.sleep(500)
        instrumentation.runOnMainSync {
            val nav = descendants(surface).first { it.tag == "tnSettingsNavigation" }
            assertEquals("Entry must settle at source position", 0f, (nav.parent as View).translationY, .1f)
            assertEquals("Background must finish fully opaque", 255, surface.background.alpha)
            closeStarted = android.os.SystemClock.uptimeMillis()
            surface.closeWithAnimation()
            assertEquals("Close callback must wait for the exit animation", 0L, closedAt)
        }
        Thread.sleep(100)
        instrumentation.runOnMainSync { assertTrue("Exit must move the panel downward", surface.translationY > 0f) }
        Thread.sleep(450)
        instrumentation.runOnMainSync {
            assertTrue("Close callback must run after the source 450ms exit", closedAt - closeStarted >= 400)
            assertEquals(surface.height.toFloat(), surface.translationY, 1f)
            java.io.File(context.cacheDir, "tn131-settings-motion-check.json").writeText(org.json.JSONObject()
                .put("version", vn.tn.assistant.BuildConfig.VERSION_NAME)
                .put("attached_window_entry_observed", enteredFromBelow)
                .put("entry_settled", true).put("exit_callback_ms", closedAt - closeStarted)
                .put("exit_translation_equals_height", true).toString(2))
            activity.finish()
        }
        context.getSharedPreferences("tn-ui-motion-test-only", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
