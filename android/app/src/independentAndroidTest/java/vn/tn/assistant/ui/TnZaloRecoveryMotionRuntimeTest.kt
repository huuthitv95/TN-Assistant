package vn.tn.assistant.ui

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Attached views must reproduce source motion; detached construction tests cannot prove it. */
class TnZaloRecoveryMotionRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun attachedRecoveryMenuSlidesInAndKeepsTheSourceDismissDelay() = withSurface { surface ->
        requireNormalAnimationScale()
        val frames = CopyOnWriteArrayList<Float>()
        val exitFrames = CopyOnWriteArrayList<Pair<Long, Float>>()
        val delaySamples = mutableListOf<Triple<Long, Float, Boolean>>()
        var exitRequestedAt = Long.MAX_VALUE
        lateinit var panel: View
        lateinit var observer: ViewTreeObserver.OnPreDrawListener
        lateinit var observedTree: ViewTreeObserver
        onMain {
            descendants(surface).filterIsInstance<ImageButton>().single().performClick()
            panel = descendants(surface).single { it.tag == "tnZaloRecoveryMenu" }
            observer = ViewTreeObserver.OnPreDrawListener {
                if (panel.visibility == View.VISIBLE) {
                    frames += panel.translationX
                    if (exitRequestedAt != Long.MAX_VALUE) {
                        exitFrames += SystemClock.uptimeMillis() - exitRequestedAt to panel.translationX
                    }
                }
                true
            }
            observedTree = panel.viewTreeObserver
            observedTree.addOnPreDrawListener(observer)
        }
        try {
            await("Recovery entrance must finish") {
                frames.any { it > 1f } && panel.visibility == View.VISIBLE && abs(panel.translationX) < .5f &&
                    descendants(surface).filterIsInstance<ImageButton>().single().visibility == View.INVISIBLE
            }
            onMain {
                val offset = TnZaloReferenceMetrics(surface.context).px(32f)
                assertEquals("The first painted frame starts beyond the right edge", panel.width + offset.toFloat(), frames.first(), .5f)
                assertTrue("At least one real intermediate frame must slide toward rest", frames.any { it > .5f && it < frames.first() - .5f })
                assertEquals("Source recovery-card elevation is _12dp", TnZaloReferenceMetrics(surface.context).px(12f).toFloat(), panel.elevation, 0f)
                val overflow = descendants(surface).filterIsInstance<ImageButton>().single()
                assertEquals("Source overflow fades and shrinks while the card opens", View.INVISIBLE, overflow.visibility)
                assertEquals(0f, overflow.alpha, .001f)
                assertEquals(0f, overflow.scaleX, .001f)
                assertEquals(0f, overflow.scaleY, .001f)
            }
            val dismissedAt = onMain {
                val now = SystemClock.uptimeMillis()
                exitRequestedAt = now
                assertTrue(surface.dismissOverflowMenu())
                assertFalse("Repeated Back cannot restart the exit delay", surface.dismissOverflowMenu())
                assertTrue("An attached card is retained during its exit", panel.isAttachedToWindow)
                now
            }
            await("The recovery card must eventually be removed") {
                delaySamples += Triple(SystemClock.uptimeMillis() - dismissedAt, panel.translationX, panel.isAttachedToWindow)
                !panel.isAttachedToWindow
            }
            assertTrue("Dismiss cannot bypass the source delay", SystemClock.uptimeMillis() - dismissedAt >= 400)
            assertTrue("The attached card must remain at rest during the delay", delaySamples.any { it.first in 1L..399L && abs(it.second) < .5f && it.third })
            assertTrue("The delayed exit must never move early", exitFrames.none { it.first < 400 && it.second > .5f })
            assertTrue("The exit must contain a painted movement after the delay", exitFrames.any { it.first >= 400 && it.second > .5f })
            onMain {
                assertEquals("The overflow is usable after dismissal", View.VISIBLE,
                    descendants(surface).filterIsInstance<ImageButton>().single().visibility)
            }
        } finally {
            onMain { if (observedTree.isAlive) observedTree.removeOnPreDrawListener(observer) }
        }
    }

    @Test fun recoveryActionRunsOnceEvenWhileTheExitAnimationIsPending() = withSurface { surface ->
        requireNormalAnimationScale()
        var calls = 0
        onMain {
            // Exercise the shipped surface without starting a real OAuth attempt in a UI test.
            surface.setOverflowAction { calls++ }
            descendants(surface).filterIsInstance<ImageButton>().single().performClick()
        }
        await("The recovery action must be painted at rest") {
            val panel = descendants(surface).firstOrNull { it.tag == "tnZaloRecoveryMenu" }
            panel != null && panel.visibility == View.VISIBLE && abs(panel.translationX) < .5f
        }
        onMain {
            val action = descendants(surface).filterIsInstance<TextView>().single { it.tag == "tnZaloRecoveryAction" }
            action.performClick()
            action.performClick()
            assertEquals("A pending exit must not launch recovery twice", 1, calls)
        }
        await("Recovery action also completes animated dismissal") {
            descendants(surface).none { it.tag == "tnZaloRecoveryMenu" }
        }
    }

    @Test fun overflowKeepsTheCardOpenAfterTheSource500msDoubleTapGuard() = withSurface { surface ->
        requireNormalAnimationScale()
        onMain {
            val overflow = descendants(surface).filterIsInstance<ImageButton>().single()
            overflow.performClick()
            overflow.performClick()
            assertTrue("Source c3.r ignores the second overflow tap instead of starting dismissal",
                descendants(surface).any { it.tag == "tnZaloRecoveryMenu" })
        }
        await("A quick double tap must leave the recovery card painted and open") {
            val panel = descendants(surface).firstOrNull { it.tag == "tnZaloRecoveryMenu" }
            panel != null && panel.visibility == View.VISIBLE && abs(panel.translationX) < .5f &&
                descendants(surface).filterIsInstance<ImageButton>().single().visibility == View.INVISIBLE
        }
    }

    @Test fun entireSourceRecoveryCardDispatchesTheAlternativeOnce() = withSurface { surface ->
        requireNormalAnimationScale()
        var calls = 0
        onMain {
            surface.setOverflowAction { calls++ }
            descendants(surface).filterIsInstance<ImageButton>().single().performClick()
        }
        await("Recovery card must finish entering before clicking its body") {
            val panel = descendants(surface).firstOrNull { it.tag == "tnZaloRecoveryMenu" }
            panel != null && panel.visibility == View.VISIBLE && abs(panel.translationX) < .5f
        }
        onMain {
            val panel = descendants(surface).single { it.tag == "tnZaloRecoveryMenu" }
            panel.performClick()
            panel.performClick()
            assertEquals("j2.i registers its 500ms handler on the whole card, including the title area", 1, calls)
        }
        await("Clicking the card body completes the same delayed dismissal as the CTA") {
            descendants(surface).none { it.tag == "tnZaloRecoveryMenu" }
        }
    }

    private fun requireNormalAnimationScale() {
        val context = instrumentation.targetContext
        assumeTrue("This motion comparison requires system animations", android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled())
        assumeTrue("This timing comparison preserves the owner's animation settings",
            abs(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) - 1f) < .001f)
    }

    private fun withSurface(run: (TnOnboardingSurface) -> Unit) {
        val context = instrumentation.targetContext
        TnIsolatedActivityPreferences(context).use {
            val preferences = context.getSharedPreferences("tn-session-v1", 0)
            check(preferences.edit().putInt("tn_welcome_step_v2", 4)
                .putBoolean("tn_welcome_completed_v2", false).putBoolean("tn_setup_voice_muted", true).commit())
            var activity: MainActivity? = null
            try {
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                lateinit var surface: TnOnboardingSurface
                await("The isolated welcome account card must attach") {
                    val candidate = descendants(requireNotNull(activity).findViewById(android.R.id.content))
                        .filterIsInstance<TnOnboardingSurface>().firstOrNull()
                    if (candidate != null) surface = candidate
                    candidate != null && candidate.isAttachedToWindow && candidate.isLaidOut && candidate.width > 0 && candidate.height > 0 &&
                        descendants(candidate).any { it is ImageButton }
                }
                run(surface)
            } finally {
                onMain { activity?.finish() }
                instrumentation.waitForIdleSync()
            }
        }
    }

    private fun await(message: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(ready)) return
            Thread.sleep(16)
        }
        fail(message)
    }

    private fun <T> onMain(run: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(run()) }
        return result.get()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
