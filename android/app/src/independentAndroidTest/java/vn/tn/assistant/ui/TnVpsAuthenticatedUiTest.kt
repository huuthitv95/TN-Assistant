package vn.tn.assistant.ui

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.R

/** Opt-in real VPS journey. Credentials are seeded privately on the AVD, never packaged. */
class TnVpsAuthenticatedUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun realLoginSettingsAndFloatingControl() {
        val context = instrumentation.targetContext
        val credentialFile = File(context.filesDir, "tn-vps-login.private.json")
        assumeTrue("Live journey was not requested", credentialFile.isFile || InstrumentationRegistry.getArguments().getString("tnLiveUi") == "true")
        val credential = if (credentialFile.isFile) JSONObject(credentialFile.readText()).also {
            assertTrue("Remove the one-use credential file before accessing the network", credentialFile.delete())
        } else null
        val initiallyEnabled = TnFloatingControlService.isEnabled(context)
        val notificationAccess = TnNotificationAccessTestScope(context, true)
        val overlayAppOp = TnOverlayAppOpTestScope(context, "allow")
        try {
        assertEquals("https://assistant.tnfastvn.com", BuildConfig.API_BASE_URL)
        if (credential != null) {
            // Explicitly seeded credentials request a fresh login, independent of a cached service session.
            vn.tn.assistant.auth.TnSessionStore.get(context).clear()
            context.getSharedPreferences("tn-session-v1", 0).edit()
                .putInt("tn_welcome_step_v2", 4).putBoolean("tn_welcome_completed_v2", false).commit()
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        if (credential != null) {
        await("Account entry must be shown", 15_000) { find(activity) { it is Button && it.text.toString() == "TIẾP TỤC" } != null }
        onMain { find(activity) { it is Button && it.text.toString() == "TIẾP TỤC" }?.performClick() }
        await("Zalo login must open directly from the welcome card", 10_000) { find(activity) { it.tag == "tnZaloAppToolbar" } != null }
        onMain { activity.onBackPressed() }
        await("TN account choice must open", 5_000) { find(activity) { it is Button && it.text.toString() == "TÀI KHOẢN TN" } != null }
        click(activity) { it is Button && it.text.toString() == "TÀI KHOẢN TN" }
        onMain {
            val username = find(activity) { it is EditText && it.hint?.toString() == "Tên đăng nhập" } as EditText
            val password = find(activity) { it is EditText && it.hint?.toString()?.startsWith("Mật khẩu") == true } as EditText
            assertTrue("Password must remain masked after single-line styling", password.transformationMethod is PasswordTransformationMethod)
            assertEquals(ResourcesCompat.getFont(context, R.font.tn_barlow_regular), password.typeface)
            username.setText(credential.getString("username"))
            password.setText(credential.getString("password"))
            assertTrue("No cleartext password may be drawn", password.transformationMethod.getTransformation(password.text, password).toString() != password.text.toString())
            requireNotNull(find(activity) { it is Button && it.text.toString() == "ĐĂNG NHẬP" }).performClick()
        }
        }
        await("VPS login must reach authenticated UI", 55_000) {
            find(activity) { (it is Button && it.text.toString() == "BẮT ĐẦU SỬ DỤNG") || it.contentDescription == "CÀI ĐẶT" } != null
        }
        onMain { find(activity) { it is Button && it.text.toString() == "BẮT ĐẦU SỬ DỤNG" }?.performClick() }
        await("Authenticated home must open", 5_000) { find(activity) { it.contentDescription == "CÀI ĐẶT" } != null }
        Thread.sleep(500)
        capture("tn130-home.private.png")
        click(activity) { it.contentDescription == "CÀI ĐẶT" }
        await("Real account settings must load from VPS", 20_000) {
            find(activity) { it.tag == "tnSettingsAccountCard" } != null &&
                find(activity) { it is TextView && it.text.toString() == "Đang tải tài khoản…" } == null &&
                find(activity) { it.contentDescription in setOf("Basic", "Premium") } != null
        }
        Thread.sleep(500)
        val expectAdminPremium = InstrumentationRegistry.getArguments().getString("tnExpectAdminPremium") == "true"
        if (expectAdminPremium) onMain {
            assertNotNull("The real VPS Admin must have Premium without purchasing a product", find(activity) { it.contentDescription == "Premium" })
            assertNull("The real VPS Admin must not be labelled Basic", find(activity) { it.contentDescription == "Basic" })
        }
        onMain {
            val decor = activity.window.decorView
            val insets = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(requireNotNull(decor.rootWindowInsets), decor)
            assertTrue("Original signed-in Settings keeps status bars visible", insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()))
            assertTrue("Original signed-in Settings keeps navigation controls visible", insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars()))
        }
        capture("tn130-settings.private.png")
        captureSettingsLayout(activity)
        click(activity) { it.tag == "tnSettingsAccountCard" }
        if (expectAdminPremium) onMain {
            assertNull("The authenticated Admin account has no upgrade prompt", find(activity) { it is TextView && it.text.toString().contains("Nâng cấp") })
        }
        capture("tn130-account-details.private.png")
        click(activity) { it.contentDescription == "Quay lại cài đặt" }
        for ((index, group) in listOf("Tiện ích", "Dẫn đường", "Giải trí", "Điều khoản", "Cập nhật", "Cài đặt khác").withIndex()) {
            click(activity) { it.contentDescription == "Nhóm $group" }
            Thread.sleep(600)
            capture("tn130-settings-group-${index + 1}.private.png")
        }
        click(activity) { it.contentDescription == "Nhóm Tài khoản" }
        Thread.sleep(600)
        click(activity) { it.contentDescription == "Tiện ích nổi" }
        await("Floating settings must open", 5_000) { find(activity) { it.contentDescription == "Tiện ích nổi: Bật" } != null }
        capture("tn130-floating-settings.private.png")
        click(activity) { it.contentDescription == "Tiện ích nổi: Bật" }
        await("Floating setting must be enabled", 5_000) { TnFloatingControlService.isEnabled(context) }
        capture("tn130-floating-enabled.private.png")
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        // Target-36 package visibility can hide the home app from the app's
        // PackageManager query. Resolve it with the same shell identity used
        // for the resumed-Activity observation instead of accepting a resolver.
        val launcher = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        ).bufferedReader().use { it.readText() }.lineSequence().map { it.trim() }
            .firstOrNull { it.matches(Regex("[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+")) }
            ?.substringBefore('/') ?: error("No launcher resolves on the test AVD")
        val launcherDeadline = android.os.SystemClock.uptimeMillis() + 5_000
        var launcherResumed = false
        var resumed = ""
        while (!launcherResumed && android.os.SystemClock.uptimeMillis() < launcherDeadline) {
            val state = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities")
            ).bufferedReader().use { it.readText() }
            resumed = state.lineSequence().firstOrNull { "mResumedActivity" in it }.orEmpty()
            launcherResumed = "$launcher/" in resumed
            if (!launcherResumed) Thread.sleep(100)
        }
        assertTrue("Capture the floating control only after the launcher is actually resumed; expected=$launcher; observed=$resumed", launcherResumed)
        // The foreground TN screen suppresses its own overlay; attachment is expected on the launcher.
        await("Enabled floating control must attach on the launcher", 5_000) { TnFloatingControlService.isVisible() }
        capture("tn130-floating-launcher.private.png")
        File(context.cacheDir, "tn130-auth-check.json").writeText(JSONObject()
            .put("version", BuildConfig.VERSION_NAME).put("api_base", BuildConfig.API_BASE_URL)
            .put("authenticated", true).put("authentication_path", if (credential == null) "saved_session_refresh" else "login")
            .put("password_masked_checked", credential != null).put("settings_loaded", true)
            .put("floating_visible", TnFloatingControlService.isVisible())
            .put("floating_initially_enabled", initiallyEnabled).toString(2))
        if (expectAdminPremium && android.os.Build.VERSION.SDK_INT >= 29) {
            await("Actual floating badge must receive Admin plan from VPS", 15_000) {
                android.view.inspector.WindowInspector.getGlobalWindowViews()
                        .filterIsInstance<TnFloatingClusterView>().any {
                            it.isAttachedToWindow && it.accountBadge.visibility == View.VISIBLE &&
                                it.accountBadge.contentDescription == "Tài khoản Admin"
                        }
            }
            onMain {
                val overlay = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .filterIsInstance<TnFloatingClusterView>().single { it.isAttachedToWindow }
                assertTrue("Click the actual attached Admin badge", overlay.accountBadge.performClick())
            }
            await("Admin badge must open account information without an upgrade prompt", 15_000) {
                find(activity) { it is TextView && it.text.toString().contains("Gói hiện tại: Admin") } != null
            }
            onMain {
                assertNull("Admin badge must not open a purchase CTA", find(activity) {
                    it is TextView && (it.text.toString().contains("Nâng cấp") || it.text.toString().contains("MUA NGAY"))
                })
            }
            File(context.cacheDir, "tn176-live-floating-admin.json").writeText(JSONObject()
                .put("actual_vps_admin_badge", true).put("actual_attached_badge_clicked", true)
                .put("admin_account_details_opened", true).put("upgrade_prompt_absent", true).toString(2))
        }
        } finally {
            try {
                if (!initiallyEnabled) TnFloatingControlService.disable(context)
            } finally {
                overlayAppOp.close()
                notificationAccess.close()
            }
        }
    }

    private fun capture(name: String) {
        // Runtime assertions and the redacted layout report do not require screenshots.
        // Account screens are captured only when explicitly requested for a separate visual review.
        if (InstrumentationRegistry.getArguments().getString("tnCaptureScreenshots") != "true") return
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        FileOutputStream(File(instrumentation.targetContext.cacheDir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun captureSettingsLayout(activity: Activity) = onMain {
        if (InstrumentationRegistry.getArguments().getString("tnCaptureLayouts") != "true") return@onMain
        val labels = setOf("Cài đặt", "Tài khoản", "Tiện ích", "Dẫn đường", "Giải trí", "Điều khoản", "Thông tin", "Khác", "Tiện ích nổi")
        val entries = org.json.JSONArray()
        for (view in descendants(activity.findViewById(android.R.id.content))) {
            val text = (view as? TextView)?.text?.toString().orEmpty()
            val tag = view.tag?.toString().orEmpty()
            if (text !in labels && tag !in setOf("tnSettingsAccountCard", "tnSettingsBanner", "tnSettingsNavigation")) continue
            val xy = IntArray(2); view.getLocationOnScreen(xy)
            entries.put(JSONObject().put("label", if (text in labels) text else tag)
                .put("bounds", org.json.JSONArray(listOf(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)))
                .put("text_px", (view as? TextView)?.textSize ?: 0f))
        }
        File(instrumentation.targetContext.cacheDir, "tn130-settings-layout.json").writeText(entries.toString(2))
    }
    private fun click(activity: Activity, predicate: (View) -> Boolean) = onMain {
        val target = requireNotNull(find(activity, predicate)) { "Expected UI action is missing" }
        val state = buildList {
            var cursor: View? = target
            repeat(5) {
                cursor?.let { view ->
                    add("${view.javaClass.simpleName}(tag=${view.tag},description=${view.contentDescription},enabled=${view.isEnabled},clickable=${view.isClickable})")
                    cursor = view.parent as? View
                }
            }
        }.joinToString(" <- ")
        assertTrue("Expected action must be enabled: $state", target.isEnabled)
        // CompoundButton toggles via performClick even when no OnClickListener returns true.
        target.performClick()
    }
    private fun find(activity: Activity, predicate: (View) -> Boolean): View? =
        descendants(activity.findViewById(android.R.id.content)).firstOrNull(predicate)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(message: String, timeout: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout * 1_000_000
        while (System.nanoTime() < deadline) {
            var success = false
            onMain { success = condition() }
            if (success) return
            Thread.sleep(100)
        }
        fail(message)
    }
}
