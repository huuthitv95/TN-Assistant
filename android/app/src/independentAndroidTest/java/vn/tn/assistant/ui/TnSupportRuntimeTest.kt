package vn.tn.assistant.ui

import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity

/** Fixture URLs exercise UI, QR and lifecycle only; they do not certify a live support service. */
class TnSupportRuntimeTest {
    @Test fun configuredProductionDocumentLoadsWithoutJavascript() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("support_live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val address = "https://assistant.tnfastvn.com/support/complaints.html"
        TnIsolatedActivityPreferences(instrumentation.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var dialog: TnSupportDialog
                scenario.onActivity { activity ->
                    dialog = TnSupportDialog(activity, TnSupportPolicy.pages[2], address)
                    dialog.show()
                }
                try {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 25000
                    var loaded = false
                    while (!loaded && android.os.SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity {
                            val views = descendants(dialog.window!!.decorView)
                            val web = views.filterIsInstance<WebView>().single()
                            val state = views.single { it.tag == "tnSupportState" } as TextView
                            loaded = web.url == address && web.progress == 100 &&
                                web.title?.contains("TN Assistant") == true && state.text.isEmpty()
                        }
                        if (!loaded) android.os.SystemClock.sleep(200)
                    }
                    assertTrue("Production support document did not finish loading", loaded)
                    val systemWindow = instrumentation.uiAutomation.rootInActiveWindow
                    assertFalse("Native compatibility warning obscures support UI",
                        systemWindow?.findAccessibilityNodeInfosByText("Android App Compatibility")?.isNotEmpty() == true)
                    scenario.onActivity { assertTrue("Support dialog is obscured", dialog.window!!.decorView.hasWindowFocus()) }
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    val output = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "tn-support-live.png")
                    output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                } finally { scenario.onActivity { dialog.dismiss() } }
            }
        }
    }

    @Test fun supportQrEncodesConfiguredUrlAndCloseReleasesItsContent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        TnIsolatedActivityPreferences(instrumentation.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val address = "https://support.example.com/faq?language=vi"
                    val dialog = TnSupportDialog(activity, TnSupportPolicy.pages.first(), address)
                    dialog.show()
                    try {
                        val image = descendants(dialog.window!!.decorView).single { it.tag == "tnSupportQr" } as ImageView
                        val bitmap = (image.drawable as BitmapDrawable).bitmap
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        assertEquals(address, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                            RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text)
                        descendants(dialog.window!!.decorView).filterIsInstance<TextView>().single { it.text == "ĐÓNG" }.performClick()
                        assertFalse(dialog.isShowing)
                        assertFalse(descendants(dialog.window!!.decorView).any { it.tag == "tnSupportQr" })
                    } finally { dialog.dismiss() }
                }
            }
        }
    }

    @Test fun missingUrlsStayExplicitAndRefreshDispatchesSupportAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val preferences = context.getSharedPreferences("tn-support-fixture", 0)
            val actions = mutableListOf<String>()
            try {
                val surface = TnUserSettingsSurface(context, preferences, "fixture-device", {}, { actions += it }, { _, _ -> })
                surface.dataPage("support", JSONObject().put("support", JSONObject().put("faq_url", "file:///private")))
                assertEquals(6, descendants(surface).filterIsInstance<TextView>().count { it.text == "Chưa cấu hình" })
                descendants(surface).single { it.contentDescription == "Vấn đề thường gặp" }.performClick()
                descendants(surface).filterIsInstance<TextView>().single { it.text == "TẢI LẠI" }.performClick()
                assertEquals(listOf("support"), actions)
            } finally { preferences.edit().clear().commit() }
        }
    }

    @Test fun documentFailureCanRetryAndDismissalDiscardsLateCallbacks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        TnIsolatedActivityPreferences(instrumentation.targetContext).use {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val dialog = TnSupportDialog(activity, TnSupportPolicy.pages[2], "https://support.invalid/policy")
                    dialog.show()
                    try {
                        val root = dialog.window!!.decorView
                        val web = descendants(root).filterIsInstance<WebView>().single()
                        assertFalse(web.settings.javaScriptEnabled)
                        assertFalse(web.settings.allowFileAccess)
                        assertFalse(web.settings.allowContentAccess)
                        val client = web.webViewClient
                        assertTrue(client.shouldOverrideUrlLoading(web, "https://external.example.com/policy"))
                        val state = descendants(root).single { it.tag == "tnSupportState" } as TextView
                        val error = state.text.toString()
                        client.onPageFinished(web, "https://support.invalid/policy")
                        assertEquals(error, state.text.toString())
                        val retry = descendants(root).single { it.tag == "tnSupportRetry" }
                        assertEquals(View.VISIBLE, retry.visibility)
                        retry.performClick()
                        assertNotSame(web, descendants(root).filterIsInstance<WebView>().single())
                        dialog.dismiss()
                        client.onPageFinished(web, "https://support.invalid/policy")
                        assertFalse(dialog.isShowing)
                        assertFalse(descendants(root).any { it is WebView })
                    } finally { dialog.dismiss() }
                }
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
