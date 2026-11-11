package vn.tn.assistant.ui

import android.content.Context
import android.graphics.drawable.RippleDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.R

/** Public labels/properties measured from the unmodified, signed-in original drawer. */
class TnSettingsDrawerStyleRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reference get() = JSONObject(instrumentation.context.assets.open("reference/original-size-drawer-styles.json")
        .bufferedReader().use { it.readText() })

    @Test fun sourceOptionsKeepTheirMeasuredTypographyWithoutAnInventedRipple() = withDrawer { drawer ->
        val source = reference
        val title = views(drawer).single { it.tag == "tnSettingsDrawerTitle" } as TextView
        checkText(source.getJSONObject("title"), title)
        for ((index, value) in listOf("large", "standard", "small").withIndex()) {
            val row = views(drawer).single { it.tag == "tnSettingsDrawerOption:$value" } as ViewGroup
            val text = row.getChildAt(0) as TextView
            val original = source.getJSONArray("options").getJSONObject(index)
            checkText(original, text)
            assertNull("The original option row $value has no ripple/background", row.background)
            assertNull("The original option row $value has no foreground", row.foreground)
            assertTrue("The source row is still an active selection target", row.isClickable)
        }
    }

    @Test fun sourceCloseKeepsItsBoundedWhiteRippleAndOriginalLineSpacing() = withDrawer { drawer ->
        val close = views(drawer).single { it.tag == "tnSettingsDrawerClose" } as TextView
        val source = reference.getJSONObject("close")
        assertTrue("The source close TextView has a real ripple background", close.background is RippleDrawable)
        val ripple = close.background as RippleDrawable
        // getColor is not public on API30. Read the immutable drawable state,
        // as used by the read-only source observation on the same API level.
        val state = requireNotNull(ripple.constantState)
        val colors = state.javaClass.declaredFields.filter { it.type == android.content.res.ColorStateList::class.java }
            .mapNotNull { it.isAccessible = true; it.get(state) as? android.content.res.ColorStateList }
        assertTrue("The source white 20% ripple is preserved", colors.any { it.defaultColor == source.getInt("ripple_color") })
        checkText(source, close)
        assertTrue(close.isClickable)
    }

    private fun checkText(source: JSONObject, text: TextView) {
        assertEquals("Text size", source.getDouble("text_px").toFloat(), text.textSize, .01f)
        assertEquals("Font weight", source.getInt("font_weight"), text.typeface.weight)
        assertEquals("Font style", source.getInt("font_style"), text.typeface.style)
        assertEquals("Font padding", source.getBoolean("include_font_padding"), text.includeFontPadding)
        assertEquals("Text color", source.getInt("text_color"), text.currentTextColor)
        assertEquals("Line spacing", source.getDouble("line_spacing_multiplier").toFloat(), text.lineSpacingMultiplier, .001f)
    }

    private fun withDrawer(check: (TnSettingsDrawerSurface) -> Unit) {
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
        assertEquals("Compare the original at its observed density", reference.getDouble("density").toFloat(), context.resources.displayMetrics.density, .001f)
        assertEquals("Compare the source font scale", 1f, context.resources.configuration.fontScale, .001f)
        val scope = TnOverlayAppOpTestScope(context, "allow")
        try {
            instrumentation.runOnMainSync {
                val prefs = context.getSharedPreferences("tn-drawer-style-oracle", Context.MODE_PRIVATE)
                prefs.edit().clear().putBoolean("floating_enabled", true).commit()
                try {
                    val surface = TnUserSettingsSurface(context, prefs, "style-oracle", {}, {}, { _, _ -> })
                    fun measure() {
                        surface.measure(exact(1080), exact(1731))
                        surface.layout(0, 0, 1080, 1731)
                    }
                    surface.floating(); measure()
                    views(surface).single { it.contentDescription == "Kích cỡ" }.performClick(); measure()
                    val drawer = views(surface).filterIsInstance<TnSettingsDrawerSurface>().single()
                    drawer.showImmediatelyForMeasurement(); measure()
                    check(drawer)
                } finally { prefs.edit().clear().commit() }
            }
        } finally { scope.close() }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
