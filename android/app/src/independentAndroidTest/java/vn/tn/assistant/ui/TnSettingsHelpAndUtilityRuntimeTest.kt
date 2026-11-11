package vn.tn.assistant.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.R
import kotlin.math.roundToInt

/** Original b3/a.java defines eight categories; item_settings_switch.xml owns the utility geometry. */
class TnSettingsHelpAndUtilityRuntimeTest {
    @Test fun helpIsItsOwnEighthCategoryAndDispatchesTheRealSupportActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-help-category-test-only", Context.MODE_PRIVATE)
            val actions = mutableListOf<String>()
            try {
                val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, { actions += it }, { _, _ -> })
                measure(surface)
                val navigation = views(surface).single { it.tag == "tnSettingsNavigation" } as LinearLayout
                assertEquals("The source category list includes Help after Other", 8, navigation.childCount)
                val help = navigation.getChildAt(7)
                assertEquals("Nhóm Trợ giúp", help.contentDescription)
                help.performClick()
                assertTrue(views(help).filterIsInstance<TextView>().single().isSelected)
                val diagnostics = views(surface).single { it.contentDescription == "Kiểm tra hệ thống" }
                val support = views(surface).single { it.contentDescription == "Hướng dẫn và liên hệ hỗ trợ" }
                val advanced = views(surface).single { it.contentDescription == "Cài đặt nâng cao" }
                assertSame("Help entries share their own card", diagnostics.parent.parent, support.parent.parent)
                assertNotSame("Help is not nested in Other", diagnostics.parent.parent, advanced.parent.parent)
                diagnostics.performClick()
                support.performClick()
                assertEquals(listOf("diagnostics", "support"), actions)
                assertFalse("No invented default-media selector appears in the source entertainment card",
                    views(surface).filterIsInstance<TextView>().any { it.text == "Ứng dụng media mặc định" })
            } finally { prefs.edit().clear().commit() }
        }
    }

    @Test fun rootUtilitiesUseSourceSwitchGeometryAndKeepDetailRowsUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-root-utility-geometry-test-only", Context.MODE_PRIVATE)
            try {
                val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, {}, { _, _ -> })
                measure(surface)
                val root = views(surface).single { it.contentDescription == "Tiện ích nổi" } as LinearLayout
                val rootLabel = root.getChildAt(0) as TextView
                val units = rootLabel.textSize / 28f * context.resources.displayMetrics.density /
                    context.resources.displayMetrics.scaledDensity
                assertEquals("Source KikiItemSettings uses 26 units vertically", (26 * units).roundToInt(), root.paddingTop)
                assertEquals(root.paddingTop, root.paddingBottom)
                val rootValue = root.getChildAt(1)
                assertEquals("Source switch navigation value has 40 units before the arrow", (40 * units).roundToInt(),
                    (rootValue.layoutParams as LinearLayout.LayoutParams).marginEnd)
                assertFalse("Source switch label excludes font padding", rootLabel.includeFontPadding)
                assertEquals(1, views(surface).count { it.tag == "tnSettingsBetaBadge" })
                assertEquals(1, views(surface).count { it.tag == "tnSettingsFeaturePremiumBadge" })
                surface.traffic()
                measure(surface)
                val detail = views(surface).single { it.contentDescription == "Ngưỡng báo vượt tốc độ" } as LinearLayout
                assertEquals("Already measured detail navigation remains at 28 units", (28 * units).roundToInt(), detail.paddingTop)
                assertEquals((16 * units).roundToInt(), (detail.getChildAt(1).layoutParams as LinearLayout.LayoutParams).marginEnd)
            } finally { prefs.edit().clear().commit() }
        }
    }

    private fun measure(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1600, 900)
    }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
