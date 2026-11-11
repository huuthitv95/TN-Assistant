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

/** Opt-in oracle: the owner's signed-in, unmodified original Settings window. */
class TnSettingsReferenceBoundsRuntimeTest {
    @Test fun settingsChromeMatchesSignedInOriginalWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnOriginalSettings") == "true")
        val floating = InstrumentationRegistry.getArguments().getString("tnOriginalSettingsDetail") == "floating"
        val account = InstrumentationRegistry.getArguments().getString("tnOriginalSettingsDetail") == "account"
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes) ?: emptyList() }
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var availableRoot = instrumentation.uiAutomation.rootInActiveWindow
        while (availableRoot == null && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            availableRoot = instrumentation.uiAutomation.rootInActiveWindow
        }
        val root = requireNotNull(availableRoot) { "The original Settings accessibility window is not available" }
        assertEquals("Keep the original signed-in Settings window in front", "ai.zalo.kiki.car", root.packageName.toString())
        val source = nodes(root)
        fun bounds(id: String) = Rect().also { rect ->
            requireNotNull(source.firstOrNull { it.viewIdResourceName == "ai.zalo.kiki.car:id/$id" })
                .getBoundsInScreen(rect)
        }
        val sourceHeader = bounds("headerView")
        val sourceClose = source.firstOrNull { it.viewIdResourceName == "ai.zalo.kiki.car:id/btn_close" }?.let {
            Rect().also(it::getBoundsInScreen)
        }
        val sourceContent = bounds("recycler_view")
        val sourceTitle = Rect().also { rect -> requireNotNull(source.firstOrNull {
            it.text?.toString() == (if (floating) "Tiện ích nổi" else if (account) "Tài khoản" else "Cài đặt") && it.className?.toString()?.endsWith("TextView") == true
        }).getBoundsInScreen(rect) }
        val statusInset = instrumentation.targetContext.resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { instrumentation.targetContext.resources.getDimensionPixelSize(it) }
        val originY = bounds("container_view").top
        // The root window can already fit status bars. Derive its content origin
        // from the actual system inset rather than a screenshot's pixel guess.
        assertEquals("Original Settings must fit the status bar", statusInset, originY)
        fun relative(rect: Rect) = Rect(rect).apply { offset(0, -originY) }
        val appOp = if (floating) TnOverlayAppOpTestScope(instrumentation.targetContext, "allow") else null
        try { instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-reference-settings-only", Context.MODE_PRIVATE)
            prefs.edit().clear().putBoolean("floating_enabled", true).commit()
            val surface = TnUserSettingsSurface(context, prefs, "private-test-device", {}, {}, { _, _ -> })
            if (floating) surface.floating()
            if (account) surface.accountDetails()
            val width = sourceContent.right
            val height = sourceContent.bottom - originY
            surface.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            surface.layout(0, 0, width, height)
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val views = descendants(surface)
            fun rect(view: View): Rect {
                var x = view.left; var y = view.top; var parent = view.parent
                while (parent is View && parent !== surface) { x += parent.left; y += parent.top; parent = parent.parent }
                return Rect(x, y, x + view.width, y + view.height)
            }
            assertEquals("Settings content bounds", relative(sourceContent), rect(views.single { it.tag == "tnSettingsContent" }))
            val close = views.single { it.contentDescription == "Đóng cài đặt" }
            if (sourceClose == null) assertEquals("Narrow detail uses Back without a redundant close button", View.GONE, close.visibility)
            else assertEquals("Close control bounds", relative(sourceClose), rect(close))
            assertEquals("Root title bounds", relative(sourceTitle), rect(views.single { it.tag == "tnSettingsHeading" }))
            if (!floating && !account) {
                val profile = Rect().also { bounds -> requireNotNull(source.firstOrNull {
                    it.viewIdResourceName == "ai.zalo.kiki.car:id/avatar"
                }?.parent).getBoundsInScreen(bounds) }
                assertEquals("Signed-in profile card bounds", relative(profile), rect(views.single { it.tag == "tnSettingsAccountCard" }))
            }
            val labels = if (floating) listOf("Kích cỡ", "Độ đậm hiển thị") else if (account) listOf("Nâng cấp gói dịch vụ", "Đăng xuất tài khoản") else emptyList()
            for (label in labels) {
                val sourceRow = Rect().also { bounds -> requireNotNull(source.firstOrNull {
                    it.text?.toString() == label
                }?.parent).getBoundsInScreen(bounds) }
                assertEquals("Floating option row: $label", relative(sourceRow), rect(views.single { it.contentDescription == label }))
            }
            prefs.edit().clear().commit()
        } } finally { appOp?.close() }
    }
}
