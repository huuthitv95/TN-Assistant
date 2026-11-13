package vn.tn.assistant.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.*
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayInputStream

/** Settings-owned document/QR dialog. Closing destroys the browser and invalidates all callbacks. */
internal class TnSupportDialog(
    context: Context,
    private val page: TnSupportPolicy.Page,
    private val address: String,
) : Dialog(context) {
    private val chrome = TnSettingsChrome(context)
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var browser: WebView? = null
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundColor(0xff00225b.toInt())
    }
    private val state = chrome.text("", 24f).apply { tag = "tnSupportState"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val body = FrameLayout(context)
    private val retry = button("THỬ LẠI") { loadDocument() }.apply { tag = "tnSupportRetry"; visibility = View.GONE }

    init {
        require(TnSupportPolicy.url(address) != null)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(content)
        content.addView(chrome.text(page.title, 32f, true))
        content.addView(state)
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context).apply { gravity = Gravity.END }
        actions.addView(retry)
        actions.addView(button("ĐÓNG") { dismiss() }.apply { contentDescription = "Đóng ${page.title}" })
        content.addView(actions)
    }

    override fun show() {
        if (isShowing) return
        super.show()
        window?.setLayout((context.resources.displayMetrics.widthPixels * .9).toInt(),
            (context.resources.displayMetrics.heightPixels * .85).toInt())
        if (page.qr) showQr(page.title) else loadDocument()
    }

    override fun dismiss() {
        destroyBrowser()
        super.dismiss()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun button(title: String, action: () -> Unit) = chrome.text(title, 24f, true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(48); minimumWidth = dp(96)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = chrome.ripple(); isFocusable = true
        setOnClickListener { action() }
    }

    private fun showQr(title: String) {
        state.text = "Quét mã QR bằng điện thoại để mở nội dung hỗ trợ TN."
        val matrix = QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, 480, 480)
        val pixels = IntArray(480 * 480) { index -> if (matrix[index % 480, index / 480]) Color.BLACK else Color.WHITE }
        val bitmap = Bitmap.createBitmap(pixels, 480, 480, Bitmap.Config.ARGB_8888)
        body.addView(ImageView(context).apply {
            tag = "tnSupportQr"; contentDescription = "Mã QR $title"
            setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }, FrameLayout.LayoutParams(-1, -1))
    }

    @Suppress("DEPRECATION")
    private fun loadDocument() {
        destroyBrowser()
        val attempt = generation
        val view = WebView(context)
        browser = view
        var failed = false
        fun current() = isShowing && generation == attempt && browser === view
        fun failure(message: String) {
            if (!current() || failed) return
            failed = true
            handler.removeCallbacksAndMessages(null)
            view.stopLoading(); view.visibility = View.INVISIBLE
            state.text = message; retry.visibility = View.VISIBLE
        }
        state.text = "Đang tải tài liệu…"; retry.visibility = View.GONE
        view.tag = "tnSupportWeb"
        view.settings.apply {
            javaScriptEnabled = false; domStorageEnabled = false
            allowFileAccess = false; allowContentAccess = false
            allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            saveFormData = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.setDownloadListener { _, _, _, _, _ -> failure("Tài liệu này yêu cầu tải tệp và chưa thể hiển thị trong TN.") }
        view.webViewClient = object : WebViewClient() {
            private fun reject(url: String): Boolean {
                if (TnSupportPolicy.allows(address, url)) return false
                failure("Liên kết chuyển hướng không thuộc trang hỗ trợ đã cấu hình.")
                return true
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = reject(request.url.toString())
            override fun shouldOverrideUrlLoading(v: WebView, url: String) = reject(url)
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (TnSupportPolicy.allows(address, request.url.toString())) null
                else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                if (url == null || !TnSupportPolicy.allows(address, url)) failure("Địa chỉ tài liệu không hợp lệ.")
            }
            override fun onPageFinished(v: WebView, url: String?) {
                if (current() && !failed) {
                    handler.removeCallbacksAndMessages(null)
                    state.text = ""; view.visibility = View.VISIBLE
                }
            }
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
                h.cancel(); failure("Không xác minh được kết nối bảo mật của tài liệu.")
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) failure("Chưa tải được tài liệu. Kiểm tra mạng rồi thử lại.")
            }
            override fun onReceivedError(v: WebView, code: Int, description: String?, failingUrl: String?) {
                failure("Chưa tải được tài liệu. Kiểm tra mạng rồi thử lại.")
            }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) failure("Máy chủ tài liệu trả lỗi ${response.statusCode}. Vui lòng thử lại.")
            }
        }
        body.addView(view, FrameLayout.LayoutParams(-1, -1))
        handler.postDelayed({ failure("Tải tài liệu quá lâu. Kiểm tra mạng rồi thử lại.") }, 20000)
        // No API Authorization header, session token, JavaScript bridge or client certificate.
        view.loadUrl(address)
    }

    private fun destroyBrowser() {
        generation++
        handler.removeCallbacksAndMessages(null)
        browser?.let {
            it.stopLoading(); it.webViewClient = WebViewClient(); it.webChromeClient = null
            (it.parent as? ViewGroup)?.removeView(it); it.removeAllViews(); it.destroy()
        }
        browser = null
        body.removeAllViews()
    }
}
