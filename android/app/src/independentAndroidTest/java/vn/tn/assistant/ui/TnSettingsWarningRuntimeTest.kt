package vn.tn.assistant.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.R
import kotlin.math.roundToInt

/** Layout oracle is inflated from the source ConstraintLayout, independent of the TN warning implementation. */
class TnSettingsWarningRuntimeTest {
    @Test fun warningGeometryMatchesInflatedSourceWithAndWithoutAnAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            for ((width, message) in listOf(
                1014 to "Tính năng cần có quyền vị trí để sử dụng",
                922 to "Tính năng cần cấp quyền truy cập thông báo để sử dụng",
                480 to "Tính năng chỉ hỗ trợ khi Tiện ích nổi được bật",
            )) for (label in listOf("Cấp quyền", "")) {
                val actual = TnSettingsWarningView(context, message, label, if (label.isBlank()) null else ({}))
                val oracle = sourceOracle(context, message, label)
                measure(actual, width)
                measure(oracle, width)
                assertEquals("Source warning total height at width=$width action=$label actual=${diagnostic(actual)} oracle=${diagnostic(oracle)}", oracle.height, actual.height)
                val actualTitle = views(actual).filterIsInstance<TextView>().single { it.text == message }
                val oracleTitle = oracle.getChildAt(0) as TextView
                assertEquals("Title, including its compound warning icon", bounds(oracleTitle, oracle), bounds(actualTitle, actual))
                assertEquals(oracleTitle.currentTextColor, actualTitle.currentTextColor)
                assertFalse(actualTitle.includeFontPadding)
                assertEquals("The original warning mark remains 21x22 native dp", listOf(
                    (21 * context.resources.displayMetrics.density).roundToInt(),
                    (22 * context.resources.displayMetrics.density).roundToInt()),
                    actualTitle.compoundDrawablesRelative[0].let { listOf(it.intrinsicWidth, it.intrinsicHeight) })
                if (label.isNotBlank()) {
                    val actualAction = views(actual).filterIsInstance<TextView>().single { it.text == label }
                    val oracleAction = oracle.getChildAt(1) as TextView
                    assertEquals("CTA bounds include source 16/8 padding", bounds(oracleAction, oracle), bounds(actualAction, actual))
                    assertEquals(oracleAction.currentTextColor, actualAction.currentTextColor)
                    assertFalse(actualAction.includeFontPadding)
                    assertEquals("CTA uses Barlow Medium", Typeface.createFromAsset(context.assets, "fonts/barlowmedium.ttf"), actualAction.typeface)
                }
            }
        }
    }

    @Test fun bodyAndCtaDispatchOnlyRealActionsAndCtaRetainsTheSource400msDebounce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var warning: TnSettingsWarningView
        lateinit var action: TextView
        var calls = 0
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            warning = TnSettingsWarningView(context, "Cần cấp quyền", "Cấp quyền") { calls++ }
            measure(warning, 922)
            action = views(warning).filterIsInstance<TextView>().single { it.text == "Cấp quyền" }
            action.performClick()
            action.performClick()
            assertEquals("An immediate repeated CTA click is ignored", 1, calls)
        }
        Thread.sleep(450)
        instrumentation.runOnMainSync {
            action.performClick()
            assertEquals("CTA becomes available after the source 400ms interval", 2, calls)
        }
        Thread.sleep(450)
        instrumentation.runOnMainSync {
            warning.performClick()
            assertEquals("The card body also opens the real permission action", 3, calls)
            val passive = TnSettingsWarningView(warning.context, "Tính năng chưa sẵn sàng")
            assertFalse("A warning without a configured action cannot report a fake click", passive.performClick())
            assertFalse(passive.isClickable)
        }
    }

    private fun sourceOracle(context: Context, message: String, action: String): ConstraintLayout {
        val root = TnSourceConstraintOracle.inflate(context, "tn_warning_source_oracle")
        val config = context.resources.configuration
        val factor = when {
            config.smallestScreenWidthDp >= 600 -> .85f
            config.smallestScreenWidthDp >= 400 && config.screenWidthDp >= 1200 -> 1f
            config.smallestScreenWidthDp >= 400 && config.screenWidthDp >= 682 -> .55f
            config.smallestScreenWidthDp >= 400 -> .6f
            config.smallestScreenWidthDp >= 300 && config.screenWidthDp >= 960 -> .6f
            config.smallestScreenWidthDp >= 300 -> .45f
            config.smallestScreenWidthDp >= 200 -> .3f
            else -> 1f
        }
        fun sd(value: Int) = (value * factor * context.resources.displayMetrics.density).roundToInt()
        root.setPadding(sd(24), sd(26), sd(24), sd(26))
        val title = root.getChildAt(0) as TextView
        assertEquals("Oracle must read the original constraint graph using the tested APK's attribute IDs",
            root.getChildAt(1).id, (title.layoutParams as ConstraintLayout.LayoutParams).endToStart)
        title.text = message
        title.typeface = Typeface.createFromAsset(context.assets, "fonts/barlowregular.ttf")
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, (28 * factor * context.resources.displayMetrics.scaledDensity).roundToInt().toFloat())
        title.setTextColor(0xffe9f3ff.toInt())
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(context.getDrawable(R.drawable.tn_settings_ic_warning_circle), null, null, null)
        title.compoundDrawablePadding = sd(16)
        (title.layoutParams as ConstraintLayout.LayoutParams).marginEnd = sd(16)
        val cta = root.getChildAt(1) as TextView
        cta.text = action
        cta.visibility = if (action.isBlank()) View.GONE else View.VISIBLE
        cta.typeface = Typeface.createFromAsset(context.assets, "fonts/barlowmedium.ttf")
        cta.setTextSize(TypedValue.COMPLEX_UNIT_PX, (28 * factor * context.resources.displayMetrics.scaledDensity).roundToInt().toFloat())
        cta.setTextColor(0xff37dee7.toInt())
        cta.setPadding(sd(16), sd(8), sd(16), sd(8))
        return root
    }

    private fun measure(view: View, width: Int) {
        // Match the real Vietnamese window before measuring detached views.
        // The helper APK has no supportsRtl flag, unlike the tested app; an
        // unresolved inherited direction otherwise omits its relative icon.
        views(view).forEach { it.layoutDirection = View.LAYOUT_DIRECTION_LTR }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, width, view.measuredHeight)
    }
    private fun bounds(view: View, root: View): Rect {
        var x = view.left; var y = view.top; var parent = view.parent
        while (parent is View && parent !== root) { x += parent.left; y += parent.top; parent = parent.parent }
        return Rect(x, y, x + view.width, y + view.height)
    }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun diagnostic(root: ViewGroup) = "padding=${root.paddingTop},${root.paddingBottom} " +
        (0 until root.childCount).joinToString(";") { index ->
            val v = root.getChildAt(index) as TextView
            "${bounds(v, root)} size=${v.textSize} visibility=${v.visibility} icon=${v.compoundDrawablesRelative[0]?.bounds} " +
                "lp=${(v.layoutParams as ConstraintLayout.LayoutParams).goneEndMargin}"
        }
}
