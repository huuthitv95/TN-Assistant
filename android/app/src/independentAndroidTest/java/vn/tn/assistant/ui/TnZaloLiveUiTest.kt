package vn.tn.assistant.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity

/** Opens the actual VPS-backed Zalo flow; never enters credentials or approves consent. */
class TnZaloLiveUiTest {
    @Test fun opensRealZaloAuthorizationPage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnLiveZalo") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("https://assistant.tnfastvn.com", BuildConfig.API_BASE_URL)
        val prefs = context.getSharedPreferences("tn-session-v1", 0)
        val oldStep = prefs.getInt("tn_welcome_step_v2", 0)
        val oldComplete = prefs.getBoolean("tn_welcome_completed_v2", false)
        val oldMuted = prefs.getBoolean("tn_setup_voice_muted", false)
        val oldDeviceId = prefs.getString("device_id", null)
        val savedRefresh = prefs.getString("refresh_ciphertext", null)
        assertTrue(prefs.edit().putInt("tn_welcome_step_v2", 4).putBoolean("tn_welcome_completed_v2", false)
            .putBoolean("tn_setup_voice_muted", true).putString("device_id", UUID.randomUUID().toString())
            .remove("refresh_ciphertext").commit())
        var activity: MainActivity? = null
        try {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        var compatibilityWarningDismissed = false
        fun dismissCompatibilityWarning() {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
            fun nodes(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
                listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let { nodes(it) } ?: emptyList() }
            val all = nodes(root)
            if (all.any { it.text?.toString() == "Android App Compatibility" }) {
                compatibilityWarningDismissed = all.firstOrNull { it.text?.toString() == "OK" }
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true || compatibilityWarningDismissed
            }
        }
        fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        fun views() = all(activity.findViewById(android.R.id.content))
        fun await(message: String, timeout: Long, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + timeout * 1_000_000
            while (System.nanoTime() < deadline) {
                dismissCompatibilityWarning()
                var ready = false
                instrumentation.runOnMainSync { ready = predicate() }
                if (ready) return
                Thread.sleep(100)
            }
            val nativeState = mutableListOf<String>()
            var webViewCount = 0
            var browserSnapshot = ""
            instrumentation.runOnMainSync {
                val currentViews = views()
                val webViews = currentViews.filterIsInstance<WebView>()
                webViewCount = webViews.size
                browserSnapshot = webViews.firstOrNull()?.let { web ->
                    val uri = web.url?.let(Uri::parse)
                    "host=${uri?.host.orEmpty()} path=${uri?.path.orEmpty()} progress=${web.progress} title=${web.title.orEmpty().take(100)}"
                }.orEmpty()
                currentViews.mapNotNull { (it as? android.widget.TextView)?.text?.toString()?.trim() }
                    .distinct()
                    .forEach { nativeState += it.take(100) }
            }
            fail("$message; webviews=$webViewCount $browserSnapshot; visible_text=${nativeState.joinToString(" | ")}")
        }
        await("Welcome account step must appear", 10_000) { views().any { it is Button && it.text == "TIẾP TỤC" } }
        instrumentation.runOnMainSync { views().filterIsInstance<Button>().first { it.text == "TIẾP TỤC" }.performClick() }
        await("The original Zalo sign-in surface must open directly", 5_000) {
            views().any { it.tag == "tnZaloAppToolbar" } && views().any { it.tag == "tnZaloSdkToolbar" }
        }
        assertFalse("The initial Zalo flow must not add the TN account chooser", views().any { it is Button && it.text == "ĐĂNG NHẬP BẰNG ZALO" })
        var authority = ""
        var path = ""
        var progress = 0
        await("The genuine Zalo HTTPS page must load (host=$authority path=$path progress=$progress)", 50_000) {
            val web = views().filterIsInstance<WebView>().firstOrNull()
            val uri = web?.url?.let(Uri::parse)
            authority = uri?.host.orEmpty(); path = uri?.path.orEmpty(); progress = web?.progress ?: 0
            val zaloHost = authority == "oauth.zaloapp.com" || authority == "zalo.me" ||
                authority.endsWith(".zalo.me") || authority.endsWith(".zaloapp.com")
            uri?.scheme == "https" && zaloHost && progress == 100
        }
        dismissCompatibilityWarning()
        File(context.cacheDir, "tn132-zalo-live-check.json").writeText(JSONObject()
            .put("version", BuildConfig.VERSION_NAME).put("api_base", BuildConfig.API_BASE_URL)
            .put("authorization_host", authority).put("authorization_path", path)
            .put("genuine_https_page_loaded", true).put("consent_approved", false)
            .put("android_compatibility_warning_dismissed", compatibilityWarningDismissed).toString(2))
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            prefs.edit().putInt("tn_welcome_step_v2", oldStep).putBoolean("tn_welcome_completed_v2", oldComplete)
                .putBoolean("tn_setup_voice_muted", oldMuted).apply {
                    if (oldDeviceId == null) remove("device_id") else putString("device_id", oldDeviceId)
                    if (savedRefresh == null) remove("refresh_ciphertext") else putString("refresh_ciphertext", savedRefresh)
                }.commit()
        }
    }
}
