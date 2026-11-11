package vn.tn.assistant.ui

import android.animation.AnimatorInflater
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Animatable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.R

class TnSettingsAccountBadgeRuntimeTest {
    @Test fun goldBadgeUsesClippedPillAndFollowsTheSuppliedSpHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            for (height in listOf(28, 44, 66)) {
                for (premium in listOf(false, true)) {
                    val badge = TnSettingsAccountBadgeView(context, premium, height)
                    badge.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
                    badge.layout(0, 0, badge.measuredWidth, badge.measuredHeight)
                    val bitmap = Bitmap.createBitmap(badge.width, badge.height, Bitmap.Config.ARGB_8888)
                    badge.draw(Canvas(bitmap)); bitmap.recycle()
                    assertEquals(height, badge.height)
                    assertEquals(badge.drawable.intrinsicWidth * height / badge.drawable.intrinsicHeight, badge.width)
                    assertTrue("Gold vector corners must be clipped by the account ImageView", badge.clipToOutline)
                    assertSame(ViewOutlineProvider.BACKGROUND, badge.outlineProvider)
                    val outline = Outline()
                    badge.outlineProvider.getOutline(badge, outline)
                    val bounds = Rect()
                    assertTrue("Source outline must be a rounded rectangle", outline.getRect(bounds))
                    assertEquals(Rect(0, 0, badge.width, badge.height), bounds)
                    assertEquals("14sp radius is half the 28sp badge height", height / 2f, outline.radius, .01f)
                    assertFalse(badge.isClickable)
                    assertFalse(badge.isFocusable)
                    if (premium) {
                        assertTrue(badge.drawable is Animatable)
                        assertFalse("Detached infinite gradient animation must stay stopped", (badge.drawable as Animatable).isRunning)
                    } else assertFalse("Basic badge has no animation", badge.drawable is Animatable)
                }
            }
        }
    }

    @Test fun gradientAnimatorsPreserveTheOriginalTwoSecondInfiniteSweep() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val ids = listOf(R.animator.tn_settings_badge_premium_translate_0,
                R.animator.tn_settings_badge_premium_translate_1, R.animator.tn_settings_badge_premium_translate_2)
            ids.forEach { id ->
                val animator = AnimatorInflater.loadAnimator(context, id) as ObjectAnimator
                assertEquals("translateX", animator.propertyName)
                assertEquals(2000L, animator.duration)
                assertEquals(ValueAnimator.INFINITE, animator.repeatCount)
                assertEquals(ValueAnimator.RESTART, animator.repeatMode)
            }
        }
    }

    /** Real View attachment/visibility checks; isolated preferences avoid touching saved TN login. */
    @Test fun premiumAnimationStopsWhenHiddenOrDetachedAndRestartsWhenShown() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnBadgeWindowUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        TnIsolatedActivityPreferences(instrumentation.targetContext).use {
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                lateinit var badge: TnSettingsAccountBadgeView
                lateinit var host: FrameLayout
                instrumentation.runOnMainSync {
                    host = FrameLayout(activity)
                    badge = TnSettingsAccountBadgeView(activity, true, 44)
                    host.addView(badge, FrameLayout.LayoutParams(badge.badgeWidth, badge.badgeHeight))
                    activity.setContentView(host)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertTrue(badge.isAttachedToWindow)
                }
                assertAnimationState(badge, true, "Visible attached Premium badge must animate")
                instrumentation.runOnMainSync { badge.visibility = View.INVISIBLE }
                assertAnimationState(badge, false, "Invisible badge must release its infinite animation work")
                instrumentation.runOnMainSync { badge.visibility = View.VISIBLE }
                assertAnimationState(badge, true, "Showing the badge must restart the animation")
                instrumentation.runOnMainSync { host.visibility = View.GONE }
                assertAnimationState(badge, false, "Hiding an ancestor must stop the badge")
                instrumentation.runOnMainSync { host.visibility = View.VISIBLE }
                assertAnimationState(badge, true, "Showing the ancestor must restart the animation")
                instrumentation.runOnMainSync { host.removeView(badge); assertFalse(badge.isAttachedToWindow) }
                assertAnimationState(badge, false, "Detaching account UI cannot leave its animation running")
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
    }

    private fun assertAnimationState(badge: TnSettingsAccountBadgeView, expected: Boolean, message: String) {
        // AnimatedVectorDrawable's RenderThread callback may arrive after the
        // visibility callback returns. Observe actual state across frame fences.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = android.os.SystemClock.uptimeMillis() + 1000
        var actual = !expected
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { actual = (badge.drawable as Animatable).isRunning }
            if (actual == expected) break
            android.os.SystemClock.sleep(16)
        }
        assertEquals(message, expected, actual)
    }
}
