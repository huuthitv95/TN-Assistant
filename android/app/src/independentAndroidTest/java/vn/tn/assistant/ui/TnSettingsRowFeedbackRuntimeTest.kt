package vn.tn.assistant.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.R

/** Read-only original observations: item_settings_switch uses 0x33ffffff;
 * floating detail navigation rows have no background. These are different controls. */
class TnSettingsRowFeedbackRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun utilityRowsKeepOriginalWhiteFeedbackInPortrait() = utilityRows(false)
    @Test fun utilityRowsKeepOriginalWhiteFeedbackInLandscape() = utilityRows(true)
    @Test fun floatingDetailKeepsOriginalPlainNavigationInPortrait() = navigationRows(false)
    @Test fun floatingDetailKeepsOriginalPlainNavigationInLandscape() = navigationRows(true)

    private fun utilityRows(landscape: Boolean) = withSurface(landscape) { surface, expectedTextSize ->
        for (label in listOf("Tiện ích nổi", "Cảnh báo giao thông", "TN ơi")) {
            val row = views(surface).single { it.contentDescription == label }
            assertTrue("Source utility $label is clickable", row.isClickable)
            assertTrue("Source item_settings_switch has selectable feedback", row.background is RippleDrawable)
            val state = requireNotNull(row.background.constantState)
            val colors = state.javaClass.declaredFields.filter { it.type == android.content.res.ColorStateList::class.java }
                .mapNotNull { it.isAccessible = true; it.get(state) as? android.content.res.ColorStateList }
            assertTrue("Source utility $label ripple must be white at 20% opacity", colors.any { it.defaultColor == 0x33ffffff })
            val text = views(row).filterIsInstance<TextView>().single { it.text.toString() == label }
            assertEquals("Original responsive label size", expectedTextSize, text.textSize, .01f)
            assertEquals(400, text.typeface.weight)
            assertFalse(text.includeFontPadding)
        }
    }

    private fun navigationRows(landscape: Boolean) = withSurface(landscape) { surface, expectedTextSize ->
        surface.floating()
        for (label in listOf("Kích cỡ", "Độ đậm hiển thị")) {
            val row = views(surface).single { it.contentDescription == label }
            assertTrue("Source $label remains a selection target", row.isClickable)
            assertNull("Original floating navigation $label does not have a ripple/background", row.background)
            assertNull("Original floating navigation $label does not have a foreground", row.foreground)
            val text = views(row).filterIsInstance<TextView>().single { it.text.toString() == label }
            assertEquals("Original responsive label size", expectedTextSize, text.textSize, .01f)
            assertEquals(400, text.typeface.weight)
            assertTrue(text.includeFontPadding)
            row.performClick()
            assertEquals("Removing the invented background must keep drawer navigation working", 1,
                views(surface).filterIsInstance<TnSettingsDrawerSurface>().size)
            assertTrue(surface.back())
        }
    }

    private fun withSurface(landscape: Boolean, check: (TnUserSettingsSurface, Float) -> Unit) {
        val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
            fontScale = 1f
            smallestScreenWidthDp = 411
            screenWidthDp = if (landscape) 683 else 411
            screenHeightDp = if (landscape) 411 else 683
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.TnAssistantOverlayTheme)
        assertEquals(2.625f, context.resources.displayMetrics.density, .001f)
        val scope = TnOverlayAppOpTestScope(context, "allow")
        try { instrumentation.runOnMainSync {
            val prefs = context.getSharedPreferences("tn-source-row-feedback-only", Context.MODE_PRIVATE)
            prefs.edit().clear().putBoolean("floating_enabled", true).commit()
            try {
                val surface = TnUserSettingsSurface(context, prefs, "row-oracle", {}, {}, { _, _ -> })
                check(surface, if (landscape) 40f else 44f)
            } finally { prefs.edit().clear().commit() }
        } } finally { scope.close() }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
