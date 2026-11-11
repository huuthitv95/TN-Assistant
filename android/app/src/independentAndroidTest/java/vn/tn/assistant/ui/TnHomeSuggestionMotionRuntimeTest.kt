package vn.tn.assistant.ui

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.R

/** Observe actual attached frames; a final-position screenshot cannot prove a transition. */
class TnHomeSuggestionMotionRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun clickedSuggestionMovesToTranscriptAndResetCancelsOldAnimation() = withHome { home ->
        var submitted = ""
        instrumentation.runOnMainSync {
            home.setSuggestions(listOf(TnHomeSuggestion("Mở nhạc", R.drawable.tn_home_settings, "Mở nhạc"))) {
                submitted = it
                home.showStatus(it)
            }
        }
        instrumentation.waitForIdleSync()
        val scroll = home.findViewWithTag<HorizontalScrollView>("tnHomeSuggestionScroll")
        val chip = (scroll.getChildAt(0) as ViewGroup).getChildAt(0)
        instrumentation.runOnMainSync { chip.performClick() }
        var moving = false
        val deadline = SystemClock.uptimeMillis() + 300
        while (SystemClock.uptimeMillis() < deadline && !moving) {
            instrumentation.runOnMainSync { moving = home.status.alpha in .31f.. .99f }
            if (!moving) Thread.sleep(8)
        }
        assertTrue("Transcript must animate through intermediate frames", moving)
        assertEquals("Mở nhạc", submitted)
        Thread.sleep(400)
        instrumentation.runOnMainSync {
            assertEquals("Mở nhạc", home.status.text.toString())
            assertEquals(1f, home.status.alpha, .01f)
            assertEquals(0f, home.status.translationX, .01f)
            assertEquals(0f, home.status.translationY, .01f)
            assertEquals(1f, home.status.scaleX, .01f)
            assertEquals(View.INVISIBLE, scroll.visibility)
            home.showStatus("")
            chip.performClick()
            home.showStatus("Không có mạng; chưa thực hiện lệnh.")
        }
        Thread.sleep(400)
        instrumentation.runOnMainSync {
            assertEquals("Không có mạng; chưa thực hiện lệnh.", home.status.text.toString())
            assertEquals(1f, home.status.alpha, .01f)
            assertEquals(0f, home.status.translationY, .01f)
            home.showStatus("")
            assertEquals(View.VISIBLE, scroll.visibility)
            assertEquals(1f, scroll.alpha, .01f)
            assertEquals(1f, scroll.scaleY, .01f)
        }
    }

    @Test fun touchPressShrinksAndCancelReturnsWithoutSubmitting() = withHome { home ->
        var submitted = 0
        instrumentation.runOnMainSync {
            home.setSuggestions(listOf(TnHomeSuggestion("Mở nhạc", R.drawable.tn_home_settings, "Mở nhạc"))) { submitted++ }
        }
        instrumentation.waitForIdleSync()
        val scroll = home.findViewWithTag<HorizontalScrollView>("tnHomeSuggestionScroll")
        val chip = (scroll.getChildAt(0) as ViewGroup).getChildAt(0)
        val start = SystemClock.uptimeMillis()
        instrumentation.runOnMainSync {
            MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,chip.width/2f,chip.height/2f,0).let {
                chip.dispatchTouchEvent(it); it.recycle()
            }
        }
        Thread.sleep(450)
        instrumentation.runOnMainSync {
            assertEquals("Source pressed scale is .875", .875f, chip.scaleX, .01f)
            MotionEvent.obtain(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,0f,0f,0).let {
                chip.dispatchTouchEvent(it); it.recycle()
            }
        }
        Thread.sleep(450)
        instrumentation.runOnMainSync {
            assertEquals(1f, chip.scaleX, .01f)
            assertEquals(1f, chip.scaleY, .01f)
            assertEquals("A scroll/cancel must not send a command", 0, submitted)
        }
    }

    private fun withHome(test: (TnAssistantHomeSurface) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1500)
        lateinit var home: TnAssistantHomeSurface
        try {
            instrumentation.runOnMainSync { home = TnAssistantHomeSurface(activity); activity.setContentView(home) }
            instrumentation.waitForIdleSync()
            test(home)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
