package vn.tn.assistant.ui

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.MainActivity

/** Reads the unmodified original app's foreground UI before opening TN on the same AVD. */
class TnWelcomeReferenceBoundsRuntimeTest {
    @Test fun welcomeCardMatchesOriginalLiveWindowCoordinates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("tnOriginalBounds") == "true")
        val automation = instrumentation.uiAutomation
        automation.waitForIdle(100, 5_000)
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var availableRoot = automation.rootInActiveWindow
        while (availableRoot == null && android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            availableRoot = automation.rootInActiveWindow
        }
        val root = requireNotNull(availableRoot) { "Original window must be accessible before opening TN" }
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { i -> node.getChild(i)?.let(::nodes) ?: emptyList() }
        val baseline = nodes(root)
        val step = InstrumentationRegistry.getArguments().getString("tnWelcomeStep", "0").toInt()
        val imageId = listOf("onboarding_image_start", "onboarding_image_mic_permission", "onboarding_image_music",
            "onboarding_image_overlay", "onboarding_image_login_zalo")[step]
        val ids = listOf(imageId, "onboarding_title", "onboarding_content", "back_btn", "dots_indicator")
        val expected = ids.associateWith { id ->
            Rect().also { rect ->
                requireNotNull(baseline.firstOrNull { it.viewIdResourceName == "ai.zalo.kiki.car:id/$id" }) {
                    "Open the unmodified original app's corresponding welcome card before running this test"
                }.getBoundsInScreen(rect)
            }
        }
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("tn-session-v1", 0)
        val oldStep = prefs.getInt("tn_welcome_step_v2", 0)
        val oldComplete = prefs.getBoolean("tn_welcome_completed_v2", false)
        val oldMuted = prefs.getBoolean("tn_setup_voice_muted", false)
        val savedRefresh = prefs.getString("refresh_ciphertext", null)
        var activity: MainActivity? = null
        try {
            assertTrue(prefs.edit().putInt("tn_welcome_step_v2", step)
                .putBoolean("tn_welcome_completed_v2", false).putBoolean("tn_setup_voice_muted", true)
                .remove("refresh_ciphertext").commit())
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                    (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
                val all = views(requireNotNull(activity).findViewById(android.R.id.content))
                val title = listOf("Cài đặt TN Assistant", "Cấp quyền truy cập", "Cấp quyền điều khiển",
                    "Cấp quyền hiển thị", "Đăng ký/Đăng nhập tài khoản")[step]
                val primary = all.filterIsInstance<Button>().single()
                assertEquals("Primary label must match the live original", baseline.first {
                    it.viewIdResourceName == "ai.zalo.kiki.car:id/back_btn"
                }.text.toString(), primary.text.toString())
                assertFalse("Plain welcome cards must not add a Back control", all.any { it.contentDescription == "Quay lại" })
                val actual = mapOf(
                    imageId to all.filterIsInstance<ImageView>().first { it.tag == "tnWelcomeIllustration" },
                    "onboarding_title" to all.filterIsInstance<TextView>().first { it.text == title },
                    "onboarding_content" to all.filterIsInstance<TextView>().first { it.text.toString().startsWith(if (step == 0) "Bắt đầu" else if (step == 4) "Đăng nhập" else "Cho phép") },
                    "back_btn" to primary,
                    "dots_indicator" to all.first { it.contentDescription == "Bước ${step + 1} trên 6" },
                )
                if (step == 4) {
                    val sourceCopy = baseline.first { it.viewIdResourceName == "ai.zalo.kiki.car:id/onboarding_content" }.text.toString()
                    val tnCopy = (actual.getValue("onboarding_content") as TextView).text.toString()
                    assertEquals("The login card keeps the source copy with only the product name changed",
                        sourceCopy.replaceFirst("Kiki", "TN"), tnCopy)
                }
                val mismatches = mutableListOf<String>()
                for ((id, view) in actual) {
                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                    val bounds = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
                    val reference = requireNotNull(expected[id])
                    // A shorter TN product name changes a wrap-content description's natural
                    // width; keep its baseline and center exact while checking all other bounds.
                    val matches = if (id == "onboarding_content") {
                        reference.top == bounds.top && reference.bottom == bounds.bottom && reference.centerX() == bounds.centerX()
                    } else reference == bounds
                    if (!matches) {
                        mismatches += "$id bounds expected=$reference actual=$bounds size=${view.width}x${view.height}"
                    }
                }
                val artwork = requireNotNull(actual[imageId])
                val stage = artwork.parent as? View
                val stageLocation = IntArray(2).also { stage?.getLocationOnScreen(it) }
                val stageDescription = "stage=${stage?.javaClass?.simpleName} " +
                    "screenTop=${stageLocation[1]} height=${stage?.height} " +
                    "artLocalTop=${artwork.top} artMeasured=${artwork.measuredWidth}x${artwork.measuredHeight} " +
                    "titleLocalTop=${requireNotNull(actual["onboarding_title"]).top} " +
                    "bodyLocalTop=${requireNotNull(actual["onboarding_content"]).top}"
                assertTrue("Original bounds differ:\n${mismatches.joinToString("\n")}\n$stageDescription", mismatches.isEmpty())
            }
        } finally {
            prefs.edit().putInt("tn_welcome_step_v2", oldStep).putBoolean("tn_welcome_completed_v2", oldComplete)
                .putBoolean("tn_setup_voice_muted", oldMuted).apply {
                    if (savedRefresh == null) remove("refresh_ciphertext") else putString("refresh_ciphertext", savedRefresh)
                }.commit()
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }
}
