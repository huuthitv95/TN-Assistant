package vn.tn.assistant.ui

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.R

/** The OAuth error hint reuses the source card's whole-body action and 500ms guard. */
class TnZaloWebRecoveryRuntimeTest {
    @Test fun cardAndCtaShareOneDebouncedAlternativeAndHiddenCardCannotDispatch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var hint: TnZaloRecoveryHint
        lateinit var action: Button
        var calls = 0
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            hint = TnZaloRecoveryHint(context) { calls++ }
            action = descendants(hint).filterIsInstance<Button>().single()
            hint.performClick()
            assertEquals("A hidden error hint cannot start recovery", 0, calls)
            hint.reveal()
            hint.performClick()
            action.performClick()
            assertEquals("Body and CTA share the source guard", 1, calls)
        }
        android.os.SystemClock.sleep(550)
        instrumentation.runOnMainSync {
            action.performClick()
            hint.performClick()
            assertEquals("The alternative becomes available after 500ms", 2, calls)
            hint.dismiss()
            action.performClick()
            assertEquals("A dismissed hint cannot launch another attempt", 2, calls)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
