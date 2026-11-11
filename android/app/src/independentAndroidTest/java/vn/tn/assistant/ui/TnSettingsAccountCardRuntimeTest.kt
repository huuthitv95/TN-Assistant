package vn.tn.assistant.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.R
import kotlin.math.roundToInt

/** Uses the original XML constraint graph, not the TN View's layout algorithm, as the geometry oracle. */
class TnSettingsAccountCardRuntimeTest {
    private data class Device(val width: Int, val screenDp: Int, val smallestDp: Int, val factor: Float)

    @Test fun originalConstraintGraphMatchesLongNamesBothBadgesAndFontScaling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val devices = listOf(Device(480, 360, 360, .45f), Device(1014, 411, 411, .6f),
                Device(922, 731, 411, .55f), Device(1700, 1280, 800, .85f))
            for (device in devices) for (font in listOf(1f, 1.35f, 2f)) {
                val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    screenWidthDp = device.screenDp; smallestScreenWidthDp = device.smallestDp
                    fontScale = font
                }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(configuration), R.style.TnAssistantOverlayTheme)
                for (premium in listOf(false, true, null)) for (name in listOf("Admin", "Tài khoản có tên rất dài để kiểm tra phần chữ bị cắt ở mép phải")) {
                    val subtitle = if (premium == true) "Tài khoản TN Premium" else "Tính năng giới hạn"
                    val actual = TnSettingsAccountCard(context, name, premium, subtitle) {}
                    val oracle = sourceOracle(context, device.factor, name, premium, subtitle)
                    measure(actual, device.width); measure(oracle, device.width)
                    val scope = "width=${device.width} font=$font premium=$premium long=${name.length > 10}"
                    assertEquals("Account item height: $scope actual=${diagnostic(actual)} oracle=${diagnostic(oracle)}", oracle.height, actual.height)
                    for ((tag, childIndex) in listOf("tnSettingsProfileAvatar" to 0, "tnSettingsAccountName" to 2,
                        "tnSettingsAccountSubtitle" to 4, "tnSettingsAccountArrow" to 6)) {
                        val actualView = actual.findViewWithTag<View>(tag)
                        assertEquals("Original $tag bounds: $scope", bounds(oracle.getChildAt(childIndex)), bounds(actualView))
                    }
                    if (premium != null) assertEquals("Original intrinsic, SP-sized badge: $scope",
                        bounds(oracle.getChildAt(3)), bounds(actual.findViewWithTag<View>("tnSettingsAccountBadge")))
                    val title = actual.findViewWithTag<TextView>("tnSettingsAccountName")
                    val description = actual.findViewWithTag<TextView>("tnSettingsAccountSubtitle")
                    assertEquals((oracle.getChildAt(2) as TextView).baseline, title.baseline)
                    assertEquals((oracle.getChildAt(4) as TextView).baseline, description.baseline)
                    assertEquals(1, title.maxLines); assertEquals(1, description.maxLines)
                    assertTrue(title.includeFontPadding); assertTrue(description.includeFontPadding)
                    assertEquals(if (premium == false) 0xffffaea6.toInt() else 0xc0e9f3ff.toInt(), description.currentTextColor)
                }
            }
        }
    }

    @Test fun signedInSurfaceActuallyUsesTheClippedAnimatedAccountComponent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-account-card-integration-test-only", Context.MODE_PRIVATE)
            val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, {}, { _, _ -> })
            surface.account(JSONObject().put("user", JSONObject().put("username", "Admin").put("role", "admin")))
            measure(surface, 1080)
            val card = surface.findViewWithTag<View>("tnSettingsAccountCard")
            assertTrue("The real settings tree must use the new component, not an unused prototype", card is TnSettingsAccountCard)
            val badge = card.findViewWithTag<View>("tnSettingsAccountBadge")
            assertTrue(badge is TnSettingsAccountBadgeView)
            assertTrue((badge as TnSettingsAccountBadgeView).premium)
            assertTrue(badge.clipToOutline)
        }
    }

    private fun sourceOracle(context: Context, factor: Float, name: String, premium: Boolean?, subtitle: String): ConstraintLayout {
        val root = TnSourceConstraintOracle.inflate(context, "tn_account_source_oracle")
        assertEquals("Oracle must retain the source name-to-avatar constraint across APK resource namespaces",
            root.getChildAt(0).id, (root.getChildAt(2).layoutParams as ConstraintLayout.LayoutParams).startToEnd)
        fun sd(value: Int) = (value * factor * context.resources.displayMetrics.density).roundToInt()
        fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value * factor, context.resources.displayMetrics).roundToInt()
        fun layout(index: Int, width: Int, height: Int, update: ConstraintLayout.LayoutParams.() -> Unit) {
            root.getChildAt(index).layoutParams = (root.getChildAt(index).layoutParams as ConstraintLayout.LayoutParams).apply {
                this.width = width; this.height = height; update()
            }
        }
        layout(0, sd(80), sd(80)) { marginStart = sd(32); topMargin = sd(28); bottomMargin = sd(28) }
        layout(1, sd(22), sd(22)) { topMargin = (4 * context.resources.displayMetrics.density).roundToInt() }
        layout(2, 0, -2) { marginStart = sd(24); topMargin = sd(28); bottomMargin = sd(28) }
        layout(3, -2, sp(28f)) { marginStart = sd(12); marginEnd = sd(32); goneEndMargin = sd(72) }
        layout(4, 0, -2) { topMargin = sd(2); bottomMargin = sd(28); goneEndMargin = sd(72) }
        layout(5, -2, -2) { marginEnd = sd(32) }
        layout(6, sd(24), sd(24)) { marginEnd = sd(32) }
        (root.getChildAt(2) as TextView).apply {
            text = name; typeface = Typeface.createFromAsset(context.assets, "fonts/barlowregular.ttf")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(28f).toFloat())
        }
        (root.getChildAt(4) as TextView).apply {
            text = subtitle; typeface = Typeface.createFromAsset(context.assets, "fonts/barlowregular.ttf")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(24f).toFloat())
        }
        (root.getChildAt(3) as ImageView).apply {
            visibility = if (premium == null) View.GONE else View.VISIBLE
            if (premium != null) setImageDrawable(context.getDrawable(if (premium) R.drawable.tn_settings_badge_premium else R.drawable.tn_settings_badge_basic))
        }
        return root
    }

    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, width, view.measuredHeight)
    }
    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)
    private fun diagnostic(root: ConstraintLayout) = (0 until root.childCount).joinToString(";") { index ->
        val v = root.getChildAt(index)
        "$index:${bounds(v)} lp=${v.layoutParams.width}x${v.layoutParams.height}" +
            if (v is TextView) " textSize=${v.textSize} baseline=${v.baseline}" else ""
    }
}
