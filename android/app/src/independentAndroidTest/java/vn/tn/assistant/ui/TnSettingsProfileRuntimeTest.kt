package vn.tn.assistant.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.R

/** Original s2.h: a changed profile needs confirmation; cancel never commits. */
class TnSettingsProfileRuntimeTest {
    @Test fun advancedProfileRequiresConfirmAndIgnoresUnchangedSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-profile-test-only", Context.MODE_PRIVATE)
            prefs.edit().clear().putString("record_mode", "default").putString("playback_mode", "default").commit()
            val saved = mutableListOf<Pair<String, Any>>()
            val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, {}, { key, value ->
                saved += key to value
                prefs.edit().putString(key, value.toString()).commit()
            })
            measure(surface)
            descendants(surface).first { it.contentDescription == "Cài đặt nâng cao" }.performClick()
            fun choose(title: String, value: String) {
                descendants(surface).first { it.contentDescription == title }.performClick(); measure(surface)
                descendants(surface).single { it.tag == "tnSettingsDrawerOption:$value" }.performClick(); measure(surface)
            }
            fun button(label: String) = descendants(surface).filterIsInstance<TextView>().single { it.text.toString() == label }
            choose("Chế độ ghi âm", "advanced")
            assertTrue("Opening the confirmation cannot change a profile", saved.isEmpty())
            val cancel = button("HỦY"); val confirm = button("XÁC NHẬN")
            assertTrue("Original dialog places cancel left and confirm right", cancel.left < confirm.left)
            cancel.performClick(); measure(surface)
            assertEquals("default", prefs.getString("record_mode", null))
            assertTrue(saved.isEmpty())
            choose("Chế độ ghi âm", "default")
            assertFalse("Selecting the current value opens no confirmation", descendants(surface).filterIsInstance<TextView>().any { it.text.toString() == "Thay đổi cài đặt?" })
            assertTrue(saved.isEmpty())
            choose("Chế độ ghi âm", "advanced")
            button("XÁC NHẬN").performClick(); measure(surface)
            assertEquals(listOf("record_mode" to "advanced"), saved)
            assertEquals("advanced", prefs.getString("record_mode", null))
            choose("Chế độ ghi âm", "advanced")
            assertEquals("No duplicate save for the already active profile", 1, saved.size)
            choose("Chế độ phát giọng nói", "advanced")
            assertTrue(surface.back())
            assertEquals("Back cancels the independent playback profile", "default", prefs.getString("playback_mode", null))
            prefs.edit().clear().commit()
        }
    }

    @Test fun sourceCardCornersAndDisabledOpacityArePreserved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-profile-style-only", Context.MODE_PRIVATE)
            prefs.edit().clear().putBoolean("floating_enabled", false).commit()
            val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, {}, { _, _ -> })
            measure(surface)
            val row = descendants(surface).single { it.contentDescription == "Cảnh báo giao thông" }
            assertFalse(row.isEnabled)
            descendants(row).filter { it is TextView || (it is ImageView && it.tag != "tnSettingsFeaturePremiumBadge") }.forEach { child ->
                assertEquals("o2.i uses 25 percent opacity for disabled row content", .25f, child.alpha, .001f)
            }
            assertEquals("The original feature badge has its own 30 percent disabled opacity", .3f,
                descendants(row).single { it.tag == "tnSettingsFeaturePremiumBadge" }.alpha, .001f)
            val card = row.parent.parent as View
            val shape = card.background as GradientDrawable
            assertEquals("Source card radius is literal 4dp, independent of _4dp qualifiers", 4f * context.resources.displayMetrics.density, shape.cornerRadius, .001f)
            prefs.edit().clear().commit()
        }
    }

    private fun measure(v: View) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1731, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 1731)
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
}
