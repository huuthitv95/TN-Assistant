package vn.tn.assistant.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.auth.TnSessionStore
import java.util.UUID
import java.util.concurrent.TimeUnit

@SdkSuppress(minSdkVersion = 29)
class TnSupportJourneyRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val origin = "https://assistant.tnfastvn.com"
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    @Test fun guestOpensConfiguredPagesFromSettings() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("support_journey_live") == "true")
        val context = instrumentation.targetContext
        assertEquals("vn.tn.assistant.preview", context.packageName)
        assertEquals(origin, BuildConfig.API_BASE_URL)
        val session = TnSessionStore.get(context)
        assertFalse("Use an empty Preview profile", session.hasSession())

        TnIsolatedActivityPreferences(context).use {
            val deviceId = "tn-support-" + UUID.randomUUID()
            val guest = request("/v1/auth/guest", "POST", payload = JSONObject()
                .put("device_id", deviceId).put("device_name", "TN support acceptance"))
            val access = guest.getString("access_token")
            try {
                val settings = request("/v1/settings", "PUT", access, JSONObject().put("welcome_enabled", false))
                assertFalse(settings.getBoolean("welcome_enabled"))
                session.accept(guest)
                assertTrue(context.getSharedPreferences("tn-session-v1", 0).edit()
                    .putString("device_id", deviceId).putBoolean("tn_welcome_completed_v2", true)
                    .putBoolean("tn_setup_voice_muted", true).commit())

                val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                    click("CÀI ĐẶT")
                    click("Hướng dẫn và liên hệ hỗ trợ")
                    checkQr("Vấn đề thường gặp", "faq")
                    scenario.recreate()
                    checkQr("Liên hệ hỗ trợ trực tiếp", "contact")
                    checkQr("Hướng dẫn sửa lỗi micro", "recording")
                    checkDocument("Quy trình tiếp nhận và xử lý khiếu nại", "complaints", "Tiếp nhận và xử lý khiếu nại")
                    checkDocument("Chính sách cho khách hàng dễ tổn thương", "accessibility", "Hỗ trợ khách hàng dễ tổn thương")
                    awaitUi("Facebook shows its unconfigured state") { root ->
                        val row = root.labeled("Facebook TN Assistant") ?: return@awaitUi false
                        row.views().filterIsInstance<TextView>().any { view -> view.text == "Chưa cấu hình" }
                    }
                }
            } finally {
                try {
                    assertTrue("Remove disposable guest", request("/v1/me", "DELETE", access).getBoolean("ok"))
                } finally {
                    session.clear()
                }
            }
        }
    }

    private fun request(path: String, method: String, access: String? = null, payload: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url(origin + path).method(method,
            payload?.toString()?.toRequestBody("application/json".toMediaType()))
        if (access != null) builder.header("Authorization", "Bearer $access")
        return http.newCall(builder.build()).execute().use {
            assertEquals("$method $path", 200, it.code)
            JSONObject(requireNotNull(it.body).string())
        }
    }

    private fun View.views(): Sequence<View> = sequence {
        yield(this@views)
        if (this@views is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).views())
        }
    }

    private fun View.labeled(label: String) = views().firstOrNull { it.contentDescription == label && it.isShown }

    private fun awaitUi(description: String, action: (View) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 35000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            instrumentation.runOnMainSync {
                val window = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }
                if (window != null) done = action(window)
            }
            if (done) return
            SystemClock.sleep(120)
        }
        fail("Timed out waiting for $description")
    }

    private fun click(label: String) = awaitUi(label) { root ->
        val view = root.labeled(label) ?: return@awaitUi false
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        view.getGlobalVisibleRect(Rect()) && view.isEnabled && view.performClick()
    }

    private fun checkDocument(label: String, slug: String, title: String) {
        click(label)
        awaitUi(label) { root ->
            val web = root.findViewWithTag<WebView>("tnSupportWeb") ?: return@awaitUi false
            val state = root.findViewWithTag<TextView>("tnSupportState")
            if (!web.isShown || web.progress != 100 || state.text.isNotEmpty()) return@awaitUi false
            assertEquals("$origin/support/$slug.html", web.url)
            assertEquals("$title · TN Assistant", web.title)
            assertFalse(web.settings.javaScriptEnabled)
            assertFalse(web.settings.allowContentAccess)
            assertFalse(web.settings.allowFileAccess)
            true
        }
        click("Đóng $label")
    }

    private fun checkQr(label: String, slug: String) {
        click(label)
        val area = Rect()
        awaitUi("QR $label") { root ->
            val qr = root.labeled("Mã QR $label") ?: return@awaitUi false
            val position = IntArray(2)
            qr.getLocationOnScreen(position)
            area.set(position[0], position[1], position[0] + qr.width, position[1] + qr.height)
            !area.isEmpty
        }
        // The window can gain focus before its opening animation has reached the display.
        val address = "$origin/support/$slug.html"
        val deadline = SystemClock.uptimeMillis() + 8000
        var decoded: String? = null
        while (decoded != address && SystemClock.uptimeMillis() < deadline) {
            decoded = readDisplayedQr(Rect(area))
            if (decoded != address) SystemClock.sleep(200)
        }
        assertEquals("Displayed QR for $label", address, decoded)
        click("Đóng $label")
    }

    private fun readDisplayedQr(area: Rect): String? {
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val bitmap = screenshot.copy(Bitmap.Config.ARGB_8888, false)
            try {
                assertTrue(area.intersect(0, 0, bitmap.width, bitmap.height))
                val pixels = IntArray(area.width() * area.height())
                bitmap.getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
                val source = RGBLuminanceSource(area.width(), area.height(), pixels)
                return try { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text }
                catch (_: ReaderException) { null }
            } finally { bitmap.recycle() }
        } finally { screenshot.recycle() }
    }
}
