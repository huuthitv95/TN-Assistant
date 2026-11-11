package vn.tn.assistant.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.MainActivity

/** Runtime checks for the Zalo recovery popover added to the matching onboarding step. */
class TnZaloRecoveryMenuRuntimeTest {
    @Test
    fun overflowControlUsesTheOriginalDarkRoundedButtonSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val surface = surface(context) {}
            val overflow = descendants(surface).filterIsInstance<ImageButton>().single()
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            overflow.background.apply {
                setBounds(0, 0, bitmap.width, bitmap.height)
                draw(Canvas(bitmap))
            }
            assertEquals("Overflow button keeps the source dark surface", Color.rgb(10, 18, 33), bitmap.getPixel(14, 48))
            bitmap.recycle()
        }
    }

    @Test
    fun overflowOpensOriginalCopyAndRunsTheConfiguredRecoveryAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var recoveryCalls = 0
        var surface: TnOnboardingSurface? = null
        var menuBounds: Rect? = null
        var titleBounds: Rect? = null
        var actionBounds: Rect? = null
        var measuredSurfaceWidth = 0
        var panelTopMargin = 0

        instrumentation.runOnMainSync {
            surface = surface(context) { recoveryCalls++ }
            val overflow = descendants(surface!!).filterIsInstance<ImageButton>().single()
            overflow.performClick()

            surface!!.measure(exact(1600), exact(828))
            surface!!.layout(0, 0, 1600, 828)
            val menu = descendants(surface!!).firstOrNull { it.tag == "tnZaloRecoveryMenu" }
            val menuTitle = descendants(surface!!).filterIsInstance<TextView>()
                .firstOrNull { it.tag == "tnZaloRecoveryTitle" }
            val menuAction = descendants(surface!!).filterIsInstance<TextView>()
                .firstOrNull { it.tag == "tnZaloRecoveryAction" }

            assertNotNull("Recovery card must be visible", menu)
            assertNotNull("Recovery heading must be visible", menuTitle)
            assertNotNull("Recovery action must be visible", menuAction)
            measuredSurfaceWidth = surface!!.width
            panelTopMargin = (menu!!.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin
            menuBounds = menu!!.let { Rect(it.left, it.top, it.right, it.bottom) }
            titleBounds = menuTitle!!.let { Rect(it.left, it.top, it.right, it.bottom) }
            actionBounds = menuAction!!.let { Rect(it.left, it.top, it.right, it.bottom) }
            assertEquals("Bạn gặp lỗi đăng nhập?", menuTitle?.text)
            assertEquals("THỬ CÁCH KHÁC", menuAction?.text)
            menuAction?.performClick()
        }

        val density = context.resources.displayMetrics.density
        fun px(value: Float) = (value * density + .5f).toInt()
        val panel = requireNotNull(menuBounds)
        assertEquals("Card width matches the original 288dp surface", px(288f), panel.width())
        assertEquals("Card height matches the original screenshot", px(92.6f), panel.height())
        assertEquals("Card is anchored to the original right inset", 1600 - px(27.2f) - px(288f), panel.left)
        assertEquals("Card is anchored to the original top inset (density=$density, width=$measuredSurfaceWidth, margin=$panelTopMargin)", px(27.2f), panel.top)
        assertEquals("Title uses the original left inset", px(27f), requireNotNull(titleBounds).left)
        assertEquals("Title uses the original top offset", px(13.3f), requireNotNull(titleBounds).top)
        assertEquals("Title width matches the original text column", panel.width() - 2 * px(27f), requireNotNull(titleBounds).width())
        assertEquals("CTA right edge matches the original", panel.width() - px(27.2f), requireNotNull(actionBounds).right)
        assertEquals("CTA top matches the original screenshot", px(52f), requireNotNull(actionBounds).top)
        assertEquals("The recovery CTA runs exactly once", 1, recoveryCalls)
        assertTrue("The card closes after selecting recovery", descendants(requireNotNull(surface))
            .none { it.tag == "tnZaloRecoveryMenu" })
    }

    @Test
    fun overflowCanBeDismissedByBackOrOutsideTap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var backDismissed = false
        var outsideDismissed = false

        instrumentation.runOnMainSync {
            val backSurface = surface(context) {}
            descendants(backSurface).filterIsInstance<ImageButton>().single().performClick()
            backDismissed = backSurface.dismissOverflowMenu()
            assertFalse("Repeated back handling has no effect", backSurface.dismissOverflowMenu())

            val outsideSurface = surface(context) {}
            descendants(outsideSurface).filterIsInstance<ImageButton>().single().performClick()
            val layer = descendants(outsideSurface).firstOrNull { it.tag == "tnZaloRecoveryMenu" }
                ?.parent as? View
            layer?.performClick()
            outsideDismissed = descendants(outsideSurface).none { it.tag == "tnZaloRecoveryMenu" }
        }

        assertTrue("Back dismisses an open card", backDismissed)
        assertTrue("An outside tap dismisses an open card", outsideDismissed)
    }

    @Test
    fun zaloOnboardingStepIntegratesTheOverflowWithoutStartingLoginUntilSelected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("tn-session-v1", 0)
        val saved = preferences.all.toMap()
        var activity: MainActivity? = null
        var titleFound = false
        var menuFound = false
        var recoveryActionVisible = false

        try {
            assertTrue(preferences.edit().clear().commit())
            assertTrue(preferences.edit()
                .putInt("tn_welcome_step_v2", 4)
                .putBoolean("tn_welcome_completed_v2", false)
                .putBoolean("tn_setup_voice_muted", true)
                .commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity

            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline && !recoveryActionVisible) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val root = activity?.findViewById<View>(android.R.id.content) ?: return@runOnMainSync
                    val all = descendants(root)
                    titleFound = all.filterIsInstance<TextView>().any { it.text == "Đăng ký/Đăng nhập tài khoản" }
                    val overflow = all.filterIsInstance<ImageButton>().singleOrNull()
                    if (overflow != null && !menuFound) {
                        overflow.performClick()
                    }
                    val opened = descendants(root)
                    val panel = opened.firstOrNull { it.tag == "tnZaloRecoveryMenu" }
                    menuFound = panel != null
                    recoveryActionVisible = panel != null && panel.isLaidOut && kotlin.math.abs(panel.translationX) < .5f &&
                        opened.any { it.tag == "tnZaloRecoveryAction" && it.isShown }
                }
                if (!recoveryActionVisible) Thread.sleep(16)
            }
            assertTrue("Zalo account step is visible", titleFound)
            assertTrue("The overflow opens the recovery card", menuFound)
            assertTrue("The recovery CTA is visible without launching OAuth", recoveryActionVisible)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            val editor = preferences.edit().clear()
            saved.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            assertTrue(editor.commit())
        }
    }

    private fun surface(context: android.content.Context, recovery: () -> Unit) =
        TnOnboardingSurface(
            context = context,
            step = 4,
            title = "Đăng ký/Đăng nhập tài khoản",
            description = "Đăng nhập TN với Zalo để có trải nghiệm cá nhân hoá tốt nhất",
            primaryText = "TIẾP TỤC",
            onPrimary = {},
        ).apply {
            setOverflowAction(recovery)
            measure(exact(1600), exact(828))
            layout(0, 0, 1600, 828)
        }

    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()
}
