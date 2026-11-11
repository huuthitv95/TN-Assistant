package vn.tn.assistant.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.MainActivity

/** Checks the shipped welcome card's accessible controls without capturing a screenshot. */
class TnWelcomeControlsRuntimeTest {
    @Test
    fun firstCardHasOnlyItsPrimaryActionAndNoTopCornerControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("tn-session-v1", 0)
        val savedPreferences = snapshot(preferences)
        var activity: MainActivity? = null

        try {
            // Keep this visual test independent of any saved TN login on the emulator.
            assertTrue(preferences.edit().clear().commit())
            assertTrue(preferences.edit()
                .putInt("tn_welcome_step_v2", 0)
                .putBoolean("tn_welcome_completed_v2", false)
                .putBoolean("tn_setup_voice_muted", true)
                .commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity

            val deadline = System.nanoTime() + 10_000_000_000L
            var title: TextView? = null
            var primary: Button? = null
            var imageButtons = 0
            var statusBarsVisible: Boolean? = null
            var buttons = emptyList<Button>()
            while (System.nanoTime() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val root = activity?.findViewById<View>(android.R.id.content) ?: return@runOnMainSync
                    val all = descendants(root)
                    title = all.filterIsInstance<TextView>().firstOrNull { it.text == "Cài đặt TN Assistant" }
                    buttons = all.filterIsInstance<Button>()
                    primary = buttons.firstOrNull { it.text == "BẮT ĐẦU" }
                    imageButtons = all.count { it is ImageButton }
                    val decor = activity?.window?.decorView
                    statusBarsVisible = decor?.rootWindowInsets?.let {
                        WindowInsetsCompat.toWindowInsetsCompat(it, decor)
                            .isVisible(WindowInsetsCompat.Type.statusBars())
                    }
                }
                if (title != null && primary != null) break
                Thread.sleep(100)
            }

            assertNotNull("First welcome title must render", title)
            assertEquals("Welcome card has one actionable button", 1, buttons.size)
            assertNotNull("Primary start button must render", primary)
            assertEquals("The first card has no extra corner image buttons", 0, imageButtons)
            assertEquals("Welcome hides the status bar like the original onboarding", false, statusBarsVisible)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            restore(preferences, savedPreferences)
        }
    }

    @Test
    fun accountChoiceUsesTheOwnerSuppliedMark() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("tn-session-v1", 0)
        val savedPreferences = snapshot(preferences)
        var activity: MainActivity? = null

        try {
            // No access/refresh session should trigger a live backend refresh during this UI-only check.
            assertTrue(preferences.edit().clear().commit())
            assertTrue(preferences.edit()
                .putBoolean("tn_welcome_completed_v2", true)
                .putBoolean("tn_setup_voice_muted", true)
                .commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity

            val deadline = System.nanoTime() + 10_000_000_000L
            var appIcon: android.widget.ImageView? = null
            var zaloButton: Button? = null
            while (System.nanoTime() < deadline + 10_000_000_000L) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val root = activity?.findViewById<View>(android.R.id.content) ?: return@runOnMainSync
                    val all = descendants(root)
                    appIcon = all.filterIsInstance<android.widget.ImageView>()
                        .firstOrNull { it.tag == "tnAccountChoiceMark" }
                    zaloButton = all.filterIsInstance<Button>().firstOrNull { it.text == "ĐĂNG NHẬP BẰNG ZALO" }
                }
                if (appIcon != null && zaloButton != null) break
                Thread.sleep(100)
            }
            assertNotNull("Account choice must show Zalo login", zaloButton)
            assertNotNull("Account choice must show the owner-supplied TN mark", appIcon)
            assertTrue("Account-choice artwork must use the responsive owner-supplied Drawable", appIcon!!.drawable is TnWelcomeAppMark)
            assertEquals("Decorative account mark must not take keyboard focus", false, appIcon!!.isFocusable)
            assertEquals("Decorative account mark must not be clickable", false, appIcon!!.isClickable)
            assertEquals("Decorative account mark must not expose a redundant accessibility node", View.IMPORTANT_FOR_ACCESSIBILITY_NO, appIcon!!.importantForAccessibility)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            restore(preferences, savedPreferences)
        }
    }

    private fun snapshot(preferences: android.content.SharedPreferences): Map<String, Any> =
        preferences.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else requireNotNull(value) }

    private fun restore(preferences: android.content.SharedPreferences, values: Map<String, Any>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported SharedPreferences value type: ${value::class.java.name}")
            }
        }
        check(editor.commit())
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
}
