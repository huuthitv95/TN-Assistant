package vn.tn.assistant.ui

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity

/** Compares the original recovery popover with TN at the same live display size. */
class TnZaloRecoveryReferenceBoundsRuntimeTest {
    @Test fun recoveryCardAndTextBoundsMatchTheOriginalLiveWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnOriginalRecoveryMenu") == "true")
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        val sourceRoot = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow) {
            "Open the original account-step recovery menu before running this comparison"
        }
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { i -> node.getChild(i)?.let(::nodes) ?: emptyList() }
        val sourceNodes = nodes(sourceRoot)
        fun sourceNode(text: String) = requireNotNull(sourceNodes.firstOrNull { it.text?.toString() == text }) {
            "The original recovery menu must show '$text'"
        }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        val sourceTitle = sourceNode("Bạn gặp lỗi đăng nhập?")
        val sourceAction = sourceNode("THỬ CÁCH KHÁC")
        val sourceCardNode = requireNotNull(sourceTitle.parent)
        assertEquals("Both original labels belong to the same recovery card", bounds(sourceCardNode), bounds(sourceAction.parent))
        val sourceCard = bounds(sourceCardNode)
        val sourceTitleBounds = bounds(sourceTitle)
        val sourceActionBounds = bounds(sourceAction)

        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("tn-session-v1", 0)
        val savedPreferences = preferences.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else requireNotNull(value) }
        var activity: MainActivity? = null
        try {
            assertEquals("This comparison uses the live VPS-backed preview", "https://assistant.tnfastvn.com", vn.tn.assistant.BuildConfig.API_BASE_URL)
            assertTrue(preferences.edit().clear()
                .putInt("tn_welcome_step_v2", 4)
                .putBoolean("tn_welcome_completed_v2", false)
                .putBoolean("tn_setup_voice_muted", true)
                .commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity

            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            fun allViews() = descendants(requireNotNull(activity).findViewById(android.R.id.content))
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            var overflow: ImageButton? = null
            while (android.os.SystemClock.uptimeMillis() < deadline && overflow == null) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    overflow = allViews().filterIsInstance<ImageButton>()
                        .firstOrNull { it.contentDescription == "Tùy chọn đăng nhập" && it.isShown }
                }
                if (overflow == null) Thread.sleep(100)
            }
            assertNotNull("The corresponding TN account-step menu control must be visible", overflow)
            instrumentation.runOnMainSync { requireNotNull(overflow).performClick() }

            var tnCard: View? = null
            var tnTitle: TextView? = null
            var tnAction: TextView? = null
            val menuDeadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (android.os.SystemClock.uptimeMillis() < menuDeadline && tnCard == null) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    tnCard = allViews().firstOrNull { it.tag == "tnZaloRecoveryMenu" && it.isShown }
                    tnTitle = allViews().filterIsInstance<TextView>().firstOrNull { it.tag == "tnZaloRecoveryTitle" && it.isShown }
                    tnAction = allViews().filterIsInstance<TextView>().firstOrNull { it.tag == "tnZaloRecoveryAction" && it.isShown }
                }
                if (tnCard == null) Thread.sleep(50)
            }
            assertNotNull("TN recovery card must open", tnCard)
            assertNotNull("TN recovery heading must be visible", tnTitle)
            assertNotNull("TN recovery action must be visible", tnAction)
            assertEquals("Recovery heading text matches the original", sourceTitle.text.toString(), tnTitle!!.text.toString())
            assertEquals("Recovery action text matches the original", sourceAction.text.toString(), tnAction!!.text.toString())

            fun screenBounds(view: View) = Rect().also { rect ->
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                rect.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }
            instrumentation.runOnMainSync {
                val contentRoot = requireNotNull(activity).findViewById<View>(android.R.id.content)
                val rootInsets = contentRoot.rootWindowInsets
                val statusInset = rootInsets?.let {
                    WindowInsetsCompat.toWindowInsetsCompat(it, contentRoot).getInsets(WindowInsetsCompat.Type.statusBars()).top
                } ?: -1
                assertEquals("Recovery card bounds match the original live screen; TN content=${screenBounds(contentRoot)} statusInset=$statusInset",
                    sourceCard, screenBounds(tnCard!!))
                assertEquals("Recovery title bounds match the original live screen", sourceTitleBounds, screenBounds(tnTitle!!))
                assertEquals("Recovery action bounds match the original live screen", sourceActionBounds, screenBounds(tnAction!!))
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
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
            assertTrue("TN preview preferences must be restored", editor.commit())
        }
    }
}
