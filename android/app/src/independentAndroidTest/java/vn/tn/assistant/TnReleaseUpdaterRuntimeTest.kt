package vn.tn.assistant

import android.net.Uri
import android.accessibilityservice.AccessibilityService
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import vn.tn.assistant.ui.TnIsolatedActivityPreferences

class TnReleaseUpdaterRuntimeTest {
    @Test
    fun cancellingUnknownSourcePromptClearsPendingInstall() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        assertFalse("Start with unknown-source access denied", context.packageManager.canRequestPackageInstalls())
        val pending = MainActivity::class.java.getDeclaredField("pendingReleaseInstall").apply { isAccessible = true }
        val progress = MainActivity::class.java.getDeclaredField("updateInProgress").apply { isAccessible = true }
        val request = MainActivity::class.java.getDeclaredMethod("requestReleaseInstall", TnAppRelease::class.java).apply { isAccessible = true }
        val release = TnAppRelease(context.packageName, 99999999L, "permission-cancel-fixture", 21,
            "/downloads/tn-permission-test-must-not-download.apk", "0".repeat(64), "")
        TnIsolatedActivityPreferences(context).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    request.invoke(activity, release)
                    assertEquals(release, pending.get(activity))
                    assertFalse("Permission prompt must precede download", (progress.get(activity) as AtomicBoolean).get())
                }
                instrumentation.waitForIdleSync()
                val automation = instrumentation.uiAutomation
                val deadline = System.currentTimeMillis() + 5000
                while (automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Cho phép cài bản cập nhật").isNullOrEmpty() &&
                    System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertFalse("The update permission prompt is actually visible",
                    automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Cho phép cài bản cập nhật").isNullOrEmpty())
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                scenario.onActivity { activity ->
                    assertNull("Back cancels the pending update just like DE SAU", pending.get(activity))
                    assertFalse("Cancelled prompt must not start a download", (progress.get(activity) as AtomicBoolean).get())
                }
                assertFalse("Cancelling must not grant unknown-source access", context.packageManager.canRequestPackageInstalls())
            }
        }
    }

    @Test
    fun publishedApkDownloadsWithMatchingInstalledSigningCertificate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS).build()
        val catalog = Request.Builder()
            .url(BuildConfig.API_BASE_URL + "/v1/releases/latest?application_id=" + Uri.encode(context.packageName))
            .get().build()
        val metadata = client.newCall(catalog).execute().use { response ->
            assertEquals(200, response.code)
            JSONObject(requireNotNull(response.body).string())
        }
        val release = requireNotNull(TnAppRelease.parse(metadata, context.packageName, 0))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var activity: MainActivity? = null
            scenario.onActivity { activity = it }
            var apk: File? = null
            try {
                apk = TnReleaseUpdater.download(requireNotNull(activity), release, client)
                assertTrue(requireNotNull(apk).isFile)
                assertTrue(requireNotNull(apk).length() > 1024 * 1024)
            } finally {
                apk?.delete()
            }
        }
    }
}
