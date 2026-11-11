package vn.tn.assistant.ui

import android.content.Context
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.R

/** Geometry oracle is the owner's original, signed-in, open size drawer. No fixture pixels. */
class TnSettingsDrawerReferenceRuntimeTest {
    @Test fun drawerMatchesOriginalPanelTitleOptionsRadioAndCloseBounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnOriginalDrawer") == "true")
        val opacity = InstrumentationRegistry.getArguments().getString("tnOriginalDrawerKind") == "opacity"
        fun nodes(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) +
            (0 until n.childCount).flatMap { n.getChild(it)?.let(::nodes) ?: emptyList() }
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var availableRoot = instrumentation.uiAutomation.rootInActiveWindow
        while (availableRoot == null && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            availableRoot = instrumentation.uiAutomation.rootInActiveWindow
        }
        val root = requireNotNull(availableRoot) { "Original drawer accessibility window is unavailable" }
        assertEquals("ai.zalo.kiki.car", root.packageName.toString())
        val source = nodes(root)
        fun node(id: String) = source.first { it.viewIdResourceName == "ai.zalo.kiki.car:id/$id" }
        fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)
        val panel = bounds(node("nav_view"))
        fun relative(n: AccessibilityNodeInfo) = bounds(n).apply { offset(0, -panel.top) }
        val appOp = TnOverlayAppOpTestScope(instrumentation.targetContext, "allow")
        try { instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-live-drawer-oracle-only", Context.MODE_PRIVATE)
            prefs.edit().clear().putBoolean("floating_enabled", true).commit()
            val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, {}, { _, _ -> })
            fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
                (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
            fun measure() {
                surface.measure(View.MeasureSpec.makeMeasureSpec(panel.right, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(panel.height(), View.MeasureSpec.EXACTLY))
                surface.layout(0, 0, panel.right, panel.height())
            }
            surface.floating(); measure()
            descendants(surface).first { it.contentDescription == (if (opacity) "Độ đậm hiển thị" else "Kích cỡ") }.performClick(); measure()
            val drawer = descendants(surface).filterIsInstance<TnSettingsDrawerSurface>().single()
            drawer.showImmediatelyForMeasurement(); measure()
            fun rect(v: View): Rect {
                var x = v.left; var y = v.top; var parent = v.parent
                while (parent is View && parent !== surface) { x += parent.left; y += parent.top; parent = parent.parent }
                return Rect(x, y, x + v.width, y + v.height)
            }
            fun check(id: String, tag: String) = assertEquals(id, relative(node(id)), rect(descendants(surface).single { it.tag == tag }))
            check("nav_view", "tnSettingsDrawerPanel")
            check("nav_drawer_title", "tnSettingsDrawerTitle")
            check("nav_recycler_view", "tnSettingsDrawerList")
            check("btn_close_nav_drawer", "tnSettingsDrawerClose")
            val options = nodes(node("nav_recycler_view"))
            val expected = if (opacity) listOf("Tự động" to "auto", "Bình thường" to "normal", "Nhạt" to "faint", "Rất nhạt" to "very_faint")
                else listOf("To" to "large", "Tiêu chuẩn" to "standard", "Nhỏ" to "small")
            for ((label, value) in expected) {
                val original = options.first { it.text?.toString() == label }
                var originalRow = requireNotNull(original.parent)
                while (!originalRow.isClickable) originalRow = requireNotNull(originalRow.parent)
                assertEquals("Option $label", relative(originalRow), rect(descendants(surface).single { it.tag == "tnSettingsDrawerOption:$value" }))
                val originalRadio = nodes(originalRow).single { it.className.toString().endsWith("RadioButton") }
                assertEquals("Radio $label", relative(originalRadio), rect(descendants(surface).single { it.tag == "tnSettingsDrawerRadio:$value" }))
            }
            prefs.edit().clear().commit()
        } } finally { appOp.close() }
    }
}
