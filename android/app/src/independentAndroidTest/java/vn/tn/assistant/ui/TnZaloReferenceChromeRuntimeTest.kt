package vn.tn.assistant.ui

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ViewGroup
import android.widget.Button
import android.webkit.WebView
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity

/** Measures Zalo host/SDK chrome against the unmodified original open on the same AVD. */
class TnZaloReferenceChromeRuntimeTest {
    @Test fun zaloLoginChromeMatchesOriginalLiveWindowBounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnOriginalZaloChrome") == "true")
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        val rootDeadline = android.os.SystemClock.uptimeMillis() + 5_000
        var root = instrumentation.uiAutomation.rootInActiveWindow
        while (root == null && android.os.SystemClock.uptimeMillis() < rootDeadline) {
            Thread.sleep(100)
            root = instrumentation.uiAutomation.rootInActiveWindow
        }
        val originalRoot = requireNotNull(root) {
            "Open the unmodified original app's Zalo login Activity before running this comparison"
        }
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::nodes) ?: emptyList() }
        val original = nodes(originalRoot)
        fun sourceBounds(id: String) = Rect().also { rect ->
            requireNotNull(original.firstOrNull { it.viewIdResourceName == id }) {
                "The original Zalo login tree must contain $id"
            }.getBoundsInScreen(rect)
        }
        val sourceAppBar = sourceBounds("android:id/action_bar")
        val sourceAppTitle = sourceBounds("android:id/action_bar_title")
        val sourceSdkBar = sourceBounds("ai.zalo.kiki.car:id/zalosdk_toolbar")
        val sourceSdkTitle = sourceBounds("ai.zalo.kiki.car:id/zalosdk_txt_title")
        val sourceBack = sourceBounds("ai.zalo.kiki.car:id/zalosdk_back_control")
        val sourceWeb = sourceBounds("ai.zalo.kiki.car:id/zalosdk_weblogin_container")
        val sourceSpinner = original.firstOrNull {
            it.viewIdResourceName == "ai.zalo.kiki.car:id/zalosdk_progress"
        }?.let { node -> Rect().also(node::getBoundsInScreen) }

        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("tn-session-v1", 0)
        val oldStep = prefs.getInt("tn_welcome_step_v2", 0)
        val oldComplete = prefs.getBoolean("tn_welcome_completed_v2", false)
        val oldMuted = prefs.getBoolean("tn_setup_voice_muted", false)
        val savedRefresh = prefs.getString("refresh_ciphertext", null)
        var activity: MainActivity? = null
        try {
            assertTrue(prefs.edit().putInt("tn_welcome_step_v2", 4)
                .putBoolean("tn_welcome_completed_v2", false).putBoolean("tn_setup_voice_muted", true)
                .remove("refresh_ciphertext").commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity

            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            fun allViews() = descendants(requireNotNull(activity).findViewById(android.R.id.content))
            fun await(message: String, predicate: () -> Boolean) {
                val deadline = android.os.SystemClock.uptimeMillis() + 15_000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    var ready = false
                    instrumentation.runOnMainSync { ready = predicate() }
                    if (ready) return
                    Thread.sleep(100)
                }
                val current = allViews().mapNotNull { view ->
                    val text = (view as? android.widget.TextView)?.text?.toString()?.takeIf(String::isNotBlank)
                    val tag = view.tag?.toString()?.takeIf(String::isNotBlank)
                    val description = view.contentDescription?.toString()?.takeIf(String::isNotBlank)
                    listOfNotNull(text, tag, description).takeIf { it.isNotEmpty() }?.joinToString("|")
                }.distinct()
                fail("$message; visible=$current")
            }
            await("The last welcome card must be visible") {
                allViews().any { it is Button && it.text.toString() == "TIẾP TỤC" }
            }
            instrumentation.runOnMainSync {
                allViews().filterIsInstance<Button>().single { it.text.toString() == "TIẾP TỤC" }.performClick()
            }
            await("The direct Zalo surface must open") {
                allViews().any { it.tag == "tnZaloAppToolbar" } && allViews().any { it.tag == "tnZaloSdkToolbar" }
            }
            instrumentation.runOnMainSync {
                val decor = requireNotNull(activity).window.decorView
                val insets = WindowInsetsCompat.toWindowInsetsCompat(requireNotNull(decor.rootWindowInsets), decor)
                assertTrue("The source Zalo Activity shows the status bar", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
                assertTrue("The source Zalo Activity shows the navigation bar", insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
                val views = allViews()
                val sourceSized = mutableMapOf(
                    "host ActionBar" to (views.first { it.tag == "tnZaloAppToolbar" } to sourceAppBar),
                    "Zalo SDK toolbar" to (views.first { it.tag == "tnZaloSdkToolbar" } to sourceSdkBar),
                    "Zalo SDK back target" to (views.first { it.tag == "tnZaloSdkBack" } to sourceBack),
                    "Zalo web content frame" to (views.first { it.tag == "tnZaloWebContent" } to sourceWeb),
                )
                if (sourceSpinner != null) sourceSized["Zalo loading ring"] =
                    views.first { it.tag == "tnZaloLoadingRing" } to sourceSpinner
                for ((name, pair) in sourceSized) {
                    val (view, expected) = pair
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    val actual = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                    assertEquals("$name must match the original live bounds", expected, actual)
                }
                fun assertTextBounds(tag: String, expected: Rect, label: String) {
                    val view = views.first { it.tag == tag }
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    val actual = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                    assertEquals("$label top must match", expected.top, actual.top)
                    assertEquals("$label bottom must match", expected.bottom, actual.bottom)
                    assertEquals("$label starts at the same x-position", expected.left, actual.left)
                }
                assertTextBounds("tnZaloAppTitle", sourceAppTitle, "Host ActionBar title")
                val sdkTitle = views.first { it.tag == "tnZaloSdkTitle" }
                val location = IntArray(2); sdkTitle.getLocationOnScreen(location)
                assertEquals("Zalo SDK title must match the original text", "Login with Zalo", (sdkTitle as android.widget.TextView).text.toString())
                assertEquals("Zalo SDK title top must match", sourceSdkTitle.top, location[1])
                assertEquals("Zalo SDK title bottom must match", sourceSdkTitle.bottom, location[1] + sdkTitle.height)
                assertEquals("Zalo SDK title horizontal center must match", sourceSdkTitle.centerX(), location[0] + sdkTitle.width / 2)
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            prefs.edit().putInt("tn_welcome_step_v2", oldStep).putBoolean("tn_welcome_completed_v2", oldComplete)
                .putBoolean("tn_setup_voice_muted", oldMuted).apply {
                    if (savedRefresh == null) remove("refresh_ciphertext") else putString("refresh_ciphertext", savedRefresh)
                }.commit()
        }
    }
}
