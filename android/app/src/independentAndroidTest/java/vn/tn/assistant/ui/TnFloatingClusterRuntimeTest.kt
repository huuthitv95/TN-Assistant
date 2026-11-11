package vn.tn.assistant.ui

import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.R

/** Native geometry, hit-target, hint-state and accessibility checks without image capture. */
class TnFloatingClusterRuntimeTest {
    @Test
    fun floatingControlMatchesSingleMicrophoneAndFirstUseHint() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val unit = TnFloatingGeometry.unit(context.resources.displayMetrics.widthPixels,
                context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE, "standard")
            val view = TnFloatingClusterView(context).apply {
                this.unit = unit
                suggestionVisible = true
            }
            view.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)

            assertEquals("The microphone keeps the source 80% inner artwork within a 90% slot circle", (unit * .72f + .5f).toInt(), view.microButton.width)
            assertEquals("The saved anchor follows the source artwork, rather than the window center", view.microButton.left + view.microButton.width / 2, view.anchorCenterX)
            assertTrue("The first-use hint is present", view.suggestionHint.visibility == View.VISIBLE)
            assertEquals("Nghe điểm báo AI", view.suggestionHint.text.toString())
            assertFalse("The hint is informational; the original floating action is the mic", view.suggestionHint.isClickable)
            assertEquals("Before account loading only the mic and passive hint are visible", 2,
                (0 until view.childCount).count {view.getChildAt(it).visibility == View.VISIBLE})
            assertEquals("No account badge is assumed before account loading", View.GONE, view.accountBadge.visibility)

            var microphoneClicks = 0
            var dismisses = 0
            view.microButton.setOnClickListener { microphoneClicks++ }
            view.dismiss = { dismisses++ }

            val mx = view.microButton.left + view.microButton.width / 2f
            val my = view.microButton.top + view.microButton.height / 2f
            view.microEnabled = false
            view.clickAt(mx, my)
            assertEquals("Disabled microphone cannot fire", 0, microphoneClicks)
            view.microEnabled = true
            view.pressAt(mx, my)
            view.releasePress()
            view.clickAt(mx, my)
            assertEquals("Enabled microphone forwards exactly one action", 1, microphoneClicks)
            assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, Bundle()))
            assertEquals("Accessibility dismiss reaches the service callback", 1, dismisses)

            view.suggestionVisible = false
            view.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertEquals("After first use the original outer microphone slot remains", (unit * 1.1269841f).toInt(), view.measuredWidth)
            assertEquals("The saved bubble anchor is the mic center", view.microButton.top + view.microButton.height / 2, view.anchorCenterY)
            assertTrue(context.getDrawable(R.drawable.tn_floating_microphone) != null)
            assertTrue(context.getDrawable(R.drawable.tn_floating_microphone_pressed) != null)
            assertTrue(context.getDrawable(R.drawable.tn_floating_news) != null)
        }
    }
}
