package vn.tn.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity
import vn.tn.assistant.R

/** Construct and navigate real Android Views; pure JVM tests do not exercise constructor/layout order. */
class TnUserSettingsRuntimeTest {
    @Test fun attachedNativeDrawerAnimatesClosesAndDispatchesOneSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val picks = mutableListOf<Pair<String, Any>>()
        lateinit var surface: TnUserSettingsSurface
        lateinit var dialog: android.app.Dialog
        val prefs = instrumentation.targetContext.getSharedPreferences("tn-drawer-attached-only", Context.MODE_PRIVATE)
        TnIsolatedActivityPreferences(instrumentation.targetContext).use { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                surface = TnUserSettingsSurface(activity, prefs, "fixture-device", {}, {}, { k, v -> picks += k to v })
                // A dedicated window survives MainActivity's asynchronous session refresh.
                dialog = android.app.Dialog(activity).apply {
                    setContentView(surface)
                    show()
                    window!!.setLayout(-1, -1)
                }
                surface.traffic()
                descendants(surface).first { it.contentDescription == "Ngưỡng báo vượt tốc độ" }.performClick()
            }
            Thread.sleep(700)
            scenario.onActivity {
                val drawer = descendants(surface).filterIsInstance<TnSettingsDrawerSurface>().single()
                assertTrue("Native drawer completed its opening animation", drawer.isDrawerOpen(android.view.Gravity.END))
                descendants(drawer).first { it.tag == "tnSettingsDrawerOption:5" }.performClick()
                assertTrue("Do not dispatch a selection before the drawer finishes closing", picks.isEmpty())
            }
            Thread.sleep(700)
            scenario.onActivity {
                assertFalse("Closed drawer is removed from the settings hierarchy", descendants(surface).any { it is TnSettingsDrawerSurface })
                assertEquals(listOf("traffic_overspeed_offset" to 5), picks)
                descendants(surface).first { it.contentDescription == "Ngưỡng báo vượt tốc độ" }.performClick()
            }
            Thread.sleep(700)
            scenario.onActivity { assertTrue(surface.back()) }
            Thread.sleep(700)
            scenario.onActivity {
                assertFalse("Back closes the drawer", descendants(surface).any { it is TnSettingsDrawerSurface })
                assertEquals("Back never commits another selection", 1, picks.size)
                dialog.dismiss()
            }
        }
        }
        prefs.edit().clear().commit()
    }
    @Test fun adminAndStructuredLocalPremiumDoNotShowBasicUpgradeGates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-premium-ui-test-only", Context.MODE_PRIVATE)
            for (role in listOf("admin", "user")) {
                val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, {}, { _, _ -> })
                surface.account(JSONObject().put("user", JSONObject().put("name", "TN UI Test").put("role", role))
                    .put("local_premium", JSONObject().put("enabled", role == "user")))
                measure(surface, 1080, 1731)
                assertNotNull("Admin or an active local grant receives the Premium badge", descendants(surface).find { it.contentDescription == "Premium" })
                surface.accountDetails()
                measure(surface, 1080, 1731)
                if (role == "admin") assertFalse("Admin has no purchase prompt", descendants(surface).filterIsInstance<TextView>().any { it.text.toString().contains("Nâng cấp") })
                surface.back()
                descendants(surface).first { it.contentDescription == "TN ơi" }.performClick()
                measure(surface, 1080, 1731)
                assertFalse("Premium wake settings have no upgrade gate", descendants(surface).filterIsInstance<TextView>().any { it.text.toString().contains("Nâng cấp") })
                surface.account(JSONObject("""{"user":{"name":"TN UI Test","role":"user"},"local_premium":{"enabled":false},"premium_features":{"wake_word":false},"entitlements":[]}"""))
                measure(surface, 1080, 1731)
                assertTrue("An expired grant or demoted role loses the upgrade bypass", descendants(surface).filterIsInstance<TextView>().any { it.text.toString() == "Nâng cấp TN Premium" })
            }
            prefs.edit().clear().commit()
        }
    }
    @Test fun sourceNavigationBarVisibilityChangesAtAccountStep() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val isolatedPreferences = TnIsolatedActivityPreferences(appContext)
        val prefs = appContext.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
        val hadStep = prefs.contains("tn_welcome_step_v2")
        val priorStep = prefs.getInt("tn_welcome_step_v2", 0)
        val hadCompleted = prefs.contains("tn_welcome_completed_v2")
        val priorCompleted = prefs.getBoolean("tn_welcome_completed_v2", false)
        try {
            for ((step, expectedVisible) in listOf(0 to false, 4 to true)) {
                prefs.edit().putInt("tn_welcome_step_v2", step)
                    .putBoolean("tn_welcome_completed_v2", false).commit()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val decor = activity.window.decorView
                        val rootInsets = decor.rootWindowInsets
                        assertNotNull("Window insets should be available for onboarding step $step", rootInsets)
                        val insets = WindowInsetsCompat.toWindowInsetsCompat(rootInsets, decor)
                        assertEquals(
                            "Original navigation-bar visibility on onboarding step $step",
                            expectedVisible,
                            insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
                        )
                    }
                }
            }
        } finally {
            prefs.edit().apply {
                if (hadStep) putInt("tn_welcome_step_v2", priorStep) else remove("tn_welcome_step_v2")
                if (hadCompleted) putBoolean("tn_welcome_completed_v2", priorCompleted) else remove("tn_welcome_completed_v2")
            }.commit()
            isolatedPreferences.close()
        }
    }

    @Test fun floatingSettingsUsesLiveOverlayPermissionAndShowsGrantAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        TnNotificationAccessTestScope(instrumentation.targetContext, true).use {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-overlay-permission-test-only", Context.MODE_PRIVATE)
            val granted = TnFloatingControlService.hasPermission(context)
            prefs.edit().clear().putBoolean("tn_overlay_granted", !granted).putBoolean("floating_enabled", false).commit()
            val actions = mutableListOf<String>()
            val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, { actions += it }, { _, _ -> })
            surface.floating()
            measure(surface, 1600, 900)
            assertEquals("Cached permission state is refreshed from Android", granted, prefs.getBoolean("tn_overlay_granted", !granted))
            val texts = descendants(surface).filterIsInstance<TextView>()
            val warning = texts.firstOrNull { it.text.toString() == "Tính năng cần có quyền hiển thị để sử dụng" }
            if (granted) {
                assertNull("Do not show a permission warning when Android already granted overlay access", warning)
            } else {
                assertNotNull("Floating settings mirrors the original missing-permission warning", warning)
                val grant = texts.firstOrNull { it.text.toString() == "Cấp quyền" }
                assertNotNull("The source permission action remains available", grant)
                grant!!.performClick()
                assertEquals(listOf("overlay"), actions)
            }
            prefs.edit().clear().commit()
        }
        }
    }

    /** Oracle: original fragment_onboarding.xml, supplied TN mark and KikiTextView.Description. */
    @Test fun welcomeUsesOriginalImageConstraintAndButtonTextStyle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            for (step in 0..5) {
                val view = TnOnboardingSurface(context, step, "TN Assistant", "Kiểm tra bố cục", "TIẾP TỤC", {})
                measure(view, 1600, 900)
                val views = descendants(view)
                val title = views.filterIsInstance<TextView>().first { it.text == "TN Assistant" }
                val image = views.filterIsInstance<ImageView>().first { it.tag == "tnWelcomeIllustration" }
                assertFalse("Welcome artwork is decorative and cannot take focus", image.isFocusable || image.isFocusableInTouchMode)
                assertFalse("Welcome artwork has no click action", image.isClickable)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, image.importantForAccessibility)
                val artwork = Bitmap.createBitmap(480, 301, Bitmap.Config.ARGB_8888)
                image.drawable.setBounds(0, 0, 480, 301)
                image.drawable.draw(Canvas(artwork))
                val visibleMarkPixel = artwork.getPixel(if (step == 0) 120 else 50, 150)
                assertTrue("Welcome card $step visibly uses the owner-supplied green TN mark",
                    Color.green(visibleMarkPixel) > Color.red(visibleMarkPixel) &&
                        Color.green(visibleMarkPixel) > Color.blue(visibleMarkPixel))
                artwork.recycle()
                val topMargin = context.resources.getDimensionPixelSize(R.dimen.tn_welcome_top_margin)
                val expectedTop = ((topMargin + title.top - image.height) / 2f + 0.5f).toInt()
                val density = context.resources.displayMetrics.density
                val compactTop = (64f * density + 0.5f).toInt()
                if (image.top == compactTop) {
                    assertEquals("Compact viewport keeps the artwork at its safe top and 40dp above the title (step $step)",
                        (40f * density + 0.5f).toInt(), title.top - image.bottom)
                } else {
                    assertEquals("Source illustration is centered above the title (step $step)", expectedTop, image.top)
                }
                assertEquals("No extra image translation in original XML", 0f, image.translationY, 0f)
                assertEquals("Original CTA text shadow radius is 8px", 8f, view.primaryButton.shadowRadius, 0f)
                assertEquals("Original CTA Description line multiplier", 1.25f, view.primaryButton.lineSpacingMultiplier, 0f)
                assertEquals("Original CTA text shadow is #8000", 0x88000000.toInt(), view.primaryButton.shadowColor)
            }
        }
    }
    @Test fun settingsHasNoNonOriginalPromotionOrReservedSpace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-ui-banner-test-only", Context.MODE_PRIVATE)
            for ((w, h) in listOf(1600 to 900, 1280 to 720, 720 to 1280)) {
                prefs.edit().clear().commit()
                val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, {}, { _, _ -> })
                surface.account(JSONObject("""{"user":{"name":"TN UI Test","role":"user"},"local_premium":false,"entitlements":[]}"""))
                measure(surface, w, h)
                val views = descendants(surface)
                val navigation = views.find { it.tag == "tnSettingsNavigation" } as? android.widget.LinearLayout
                assertNotNull(navigation)
                assertTrue("Original settings sidebar starts directly below its header", navigation!!.paddingTop == 0)
                assertNull("No non-original promotional banner is present", views.find { it.tag == "tnSettingsBanner" })
                assertNull("No promotional close control is present", views.find { it.tag == "tnSettingsBannerClose" })
                assertFalse("No promotion-dismissal state is persisted", prefs.contains("tn_settings_banner_dismissed"))
            }
            prefs.edit().clear().commit()
        }
    }
    @Test fun accountSettingsAndDrawersSurviveNativeMeasureAndBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-ui-test-only", Context.MODE_PRIVATE)
            val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, {}, { _, _ -> })
            surface.account(JSONObject("""{"user":{"name":"TN UI Test","role":"user"},"local_premium":false,"entitlements":[]}"""))
            for ((w, h) in listOf(1600 to 900, 1280 to 720, 720 to 1280)) {
                measure(surface, w, h)
                assertTrue(descendants(surface).filterIsInstance<ImageView>().size >= 8)
                val name = descendants(surface).filterIsInstance<TextView>().find { it.text.toString() == "TN UI Test" }
                assertNotNull(name)
                val profile = descendants(surface).firstOrNull { it.tag == "tnSettingsAccountCard" }
                val profileArrow = descendants(surface).filterIsInstance<ImageView>().firstOrNull { it.tag == "tnSettingsProfileArrow" }
                assertNull("The owner's signed-in original card has no trailing arrow", profileArrow)
                val profileCard = checkNotNull(profile) { "The account row must remain present" }
                assertFalse("The source root does not show a second device-ID card", descendants(surface).any { it.contentDescription == "Thiết bị liên kết" })
                val avatar = descendants(surface).filterIsInstance<FrameLayout>()
                    .firstOrNull { it.tag == "tnSettingsProfileAvatar" }
                val avatarImage = descendants(surface).filterIsInstance<ImageView>()
                    .firstOrNull { it.tag == "tnSettingsProfilePhoto" }
                assertNotNull("Account card uses the source profile-avatar frame", avatar)
                assertNotNull("Account card uses the source contact-avatar fallback", avatarImage)
                assertFalse("Profile avatar must not be the application logo", avatarImage!!.drawable is TnWelcomeAppMark)
                assertFalse("Decorative profile avatar cannot take focus", avatar!!.isFocusable || avatarImage.isFocusable)
                assertFalse("Decorative profile avatar cannot be clicked separately", avatar.isClickable || avatarImage.isClickable)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, avatar.importantForAccessibility)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, avatarImage.importantForAccessibility)
                assertEquals(ImageView.ScaleType.CENTER_CROP, avatarImage.scaleType)
                val badge = descendants(surface).find { it.contentDescription == "Basic" }
                assertNotNull(badge)
                assertTrue("Account badge must follow the name instead of sticking to the screen edge", badge!!.left > name!!.right && badge.left - name.right < 48)
                assertTrue("The entire account card is clickable, including the trailing arrow area", profileCard.performClick())
                measure(surface, w, h)
                val detailLabels = descendants(surface).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
                assertTrue("Account page mirrors the source upgrade row", "Nâng cấp gói dịch vụ" in detailLabels)
                assertTrue("Account page mirrors the source logout row", "Đăng xuất tài khoản" in detailLabels)
                assertFalse("The owner's original account detail has only upgrade and logout", "Xoá tài khoản" in detailLabels)
                assertFalse("Account page does not add source-absent profile rows", "Đăng nhập bằng Zalo" in detailLabels || "Thiết bị liên kết" in detailLabels)
                surface.floating(); measure(surface, w, h)
                assertTrue(surface.back())
                surface.traffic(); measure(surface, w, h)
                val row = descendants(surface).find { it.contentDescription == "Ngưỡng báo vượt tốc độ" }
                assertNotNull(row); row!!.performClick(); measure(surface, w, h)
                assertNotNull(descendants(surface).filterIsInstance<TextView>().find { it.text.toString() == "Bằng tốc độ giới hạn" })
                assertTrue(surface.back())
                assertTrue(surface.back())
            }
            prefs.edit().clear().commit()
        }
    }
    @Test fun accountDeleteConfirmationMatchesSourceAndNeverDeletesOnCancelOrBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            val prefs = context.getSharedPreferences("tn-account-delete-ui-test-only", Context.MODE_PRIVATE)
            val actions = mutableListOf<String>()
            val surface = TnUserSettingsSurface(context, prefs, "fixture-device", {}, { actions += it }, { _, _ -> })
            surface.account(JSONObject("""{"user":{"name":"TN UI Test","role":"user"},"local_premium":false,"entitlements":[]}"""))
            surface.privacy()
            measure(surface, 1600, 900)

            val deleteRow = descendants(surface).first { it.contentDescription == "Xoá tài khoản" }
            assertTrue(deleteRow.performClick())
            measure(surface, 1600, 900)
            var texts = descendants(surface).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("Original destructive confirmation title is retained", "Xác nhận xoá tài khoản" in texts)
            assertTrue("Original destructive warning copy is retained", "Xoá tài khoản sẽ:\n\n• Toàn bộ dữ liệu sẽ bị xoá và không thể khôi phục lại\n• Các dịch vụ trả phí sẽ bị dừng và không thể hoàn tác" in texts)
            assertTrue("Source destructive action stays on the left", "XOÁ TÀI KHOẢN" in texts)
            assertTrue("Source close action stays on the right", "ĐÓNG" in texts)
            assertTrue("Android Back only dismisses the confirmation", surface.back())
            assertTrue("Cancel and Back never invoke account deletion", actions.isEmpty())

            measure(surface, 1600, 900)
            descendants(surface).first { it.contentDescription == "Xoá tài khoản" }.performClick()
            measure(surface, 1600, 900)
            descendants(surface).filterIsInstance<TextView>().first { it.text.toString() == "ĐÓNG" }.performClick()
            assertTrue("The source close button never deletes the account", actions.isEmpty())

            descendants(surface).first { it.contentDescription == "Xoá tài khoản" }.performClick()
            measure(surface, 1600, 900)
            descendants(surface).filterIsInstance<TextView>().first { it.text.toString() == "XOÁ TÀI KHOẢN" }.performClick()
            assertEquals("Only the explicit destructive button invokes the fake action", listOf("delete-account"), actions)

            surface.accountDetails()
            descendants(surface).first { it.contentDescription == "Đăng xuất tài khoản" }.performClick()
            measure(surface, 1600, 900)
            texts = descendants(surface).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue("Source logout confirmation keeps its original title", "Xác nhận đăng xuất?" in texts)
            assertTrue("Source logout confirmation keeps its original cancel action", "HỦY" in texts)
            assertTrue("Source logout confirmation keeps its original action label", "ĐĂNG XUẤT" in texts)
            surface.back()
            assertEquals("Canceling logout does not dispatch logout", listOf("delete-account"), actions)
            prefs.edit().clear().commit()
        }
    }
    @Test fun allSixWelcomeIllustrationsAndZaloChromeMeasureNatively() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.TnAssistantOverlayTheme)
            for (step in 0..5) for ((w, h) in listOf(1600 to 900, 720 to 1280)) {
                val view = TnOnboardingSurface(context, step, "TN Assistant", "Kiểm tra bố cục", "TIẾP TỤC", {})
                measure(view, w, h)
                assertTrue(view.primaryButton.measuredWidth > 0)
                assertEquals(w, view.width)
            }
            val web = TnZaloWebSurface(context, View(context), {}, {}, {})
            web.loading(true); measure(web, 1600, 900)
            val chrome = web.getChildAt(0) as LinearLayout
            val appToolbar = chrome.getChildAt(0)
            val hostContext = ContextThemeWrapper(context, android.R.style.Theme_Holo_Light)
            val hostAttributes = hostContext.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
            val hostBarHeight = hostAttributes.getDimensionPixelSize(0, 0)
            hostAttributes.recycle()
            assertEquals("Original host ActionBar follows Holo's phone/tablet height", hostBarHeight, appToolbar.height)
            assertTrue(descendants(appToolbar).any { it is TextView && it.text == "Liên kết TN Assistant với Zalo để tăng cường trải nghiệm cá nhân hóa của bạn" })
            val toolbar = chrome.getChildAt(1)
            assertEquals("Zalo SDK toolbar must retain its source 48dp height", (48 * context.resources.displayMetrics.density).toInt(), toolbar.height)
            assertTrue(descendants(toolbar).any { it is TextView && it.text == "Login with Zalo" })
            val back = descendants(toolbar).first { it.contentDescription == "Quay lại" }
            assertEquals("Zalo SDK back target remains 42dp wide", (42 * context.resources.displayMetrics.density).toInt(), back.width)
            assertEquals("Zalo SDK back target remains 48dp tall", (48 * context.resources.displayMetrics.density).toInt(), back.height)
            assertTrue("Zalo back button uses the owner-authorized reference asset", (back as ImageView).drawable.constantState == context.getDrawable(R.drawable.tn_zalo_back_arrow)?.constantState)
            val content = chrome.getChildAt(2) as ViewGroup
            val spinner = content.getChildAt(1)
            assertEquals("Holo's wrap-content ProgressBar clamps the source spinner to 48dp",
                (48f * context.resources.displayMetrics.density + 0.5f).toInt(), spinner.measuredWidth)
            web.error("Không kết nối được Zalo"); measure(web, 720, 1280)
            web.error(null); web.status("Đang xác minh đăng nhập…"); measure(web, 1600, 900)
        }
    }
    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun rightWithin(view: View, ancestor: View): Int {
        var current = view
        var left = 0
        while (current !== ancestor) {
            left += current.left
            current = current.parent as? View ?: error("View is not a descendant of the expected account row")
        }
        return left + view.width
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
