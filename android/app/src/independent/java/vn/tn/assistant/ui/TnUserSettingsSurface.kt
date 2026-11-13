package vn.tn.assistant.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.R
import vn.tn.assistant.TnMediaAccessService
import kotlin.math.roundToInt

/** Source-measured settings chrome. Values and permissions are supplied by the TN runtime. */
class TnUserSettingsSurface(
    context: Context,
    private val prefs: SharedPreferences,
    private val deviceId: String,
    private val close: () -> Unit,
    private val action: (String) -> Unit,
    private val save: (String, Any) -> Unit,
) : FrameLayout(context) {
    private val chrome = TnSettingsChrome(context)
    private fun sd(value: Int) = chrome.px(value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val groups = listOf("Tài khoản", "Tiện ích", "Dẫn đường", "Giải trí", "Điều khoản", "Cập nhật", "Cài đặt khác", "Trợ giúp")
    private val icons = listOf(R.drawable.tn_settings_ic_account, R.drawable.tn_home_suggestion_utility,
        R.drawable.tn_home_suggestion_navigate, R.drawable.tn_home_suggestion_music,
        R.drawable.tn_settings_ic_document, R.drawable.tn_settings_ic_update, R.drawable.tn_settings_ic_tab_setting,
        R.drawable.tn_settings_ic_help)
    private val header = object : FrameLayout(context) {
        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h)) }
        override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
    }
    private val heading = chrome.text("Cài đặt", 36f, true).apply { tag = "tnSettingsHeading" }
    private val breadcrumb = chrome.text("Cài đặt", 28f).apply {
        typeface = chrome.medium
        setTextColor(chrome.cyan); visibility = GONE; background = chrome.ripple()
        isFocusable = true; contentDescription = "Quay lại cài đặt"
        setOnClickListener { root(selected, true) }
    }
    private val breadcrumbArrow = chrome.icon(R.drawable.tn_settings_ic_header_arrow).apply {
        visibility = GONE
        setOnClickListener { root(selected, true) }
        contentDescription = "Quay lại cài đặt"
    }
    private val closeButton = ImageButton(context).apply {
        setImageResource(R.drawable.tn_settings_ic_close)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(sd(16), sd(16), sd(16), sd(16))
        setBackgroundResource(R.drawable.tn_settings_background_btn_ripple_rounded_8dp)
        contentDescription = "Đóng cài đặt"; setOnClickListener { closeWithAnimation() }
    }
    private val sidebar = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private val nav = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "tnSettingsNavigation" }
    private val scroll = ScrollView(context).apply {
        tag = "tnSettingsContent"
        isFillViewport = true; isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(sd(50)); setPadding(sd(16), sd(32), sd(16), 0)
        clipToPadding = true; scrollBarStyle = SCROLLBARS_OUTSIDE_INSET
        scrollBarSize = dp(3)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            verticalScrollbarThumbDrawable = resources.getDrawable(R.drawable.tn_settings_scroll_thumb, context.theme)
            verticalScrollbarTrackDrawable = resources.getDrawable(R.drawable.tn_settings_scroll_track, context.theme)
        }
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        isFocusableInTouchMode = true
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val anchors = mutableListOf<View>()
    private val tabs = mutableListOf<TextView>()
    private val indicators = mutableListOf<View>()
    private val tabRows = mutableListOf<View>()
    private var selected = 0
    private var selectedByClick: Int? = null
    private var detail: String? = null
    private var drawer: View? = null
    private var confirmation: View? = null
    private var supportDialog: TnSupportDialog? = null
    private var accountName = "Đang tải tài khoản…"
    private var premium: Boolean? = null
    private var administrator = false
    private var accountError: String? = null
    private var busy = false
    private var statusInset = 0
    private var entered = false
    private var closing = false
    private var contentMotion: android.animation.ValueAnimator? = null
    private var backgroundMotion: android.animation.ValueAnimator? = null
    private val trafficModes = listOf("Giọng nói (Beta)" to "voice", "Âm báo" to "sound", "Chỉ hiện biển báo" to "display", "Tắt" to "none")

    init {
        // Settings owns an opaque source background, rather than tinting the home/footer beneath it.
        background = TnWelcomeBackground(context)
        // The source settings fragment fits system windows. Our custom layout must
        // consume the top inset explicitly instead of drawing its header under it.
        setOnApplyWindowInsetsListener { _, insets ->
            if (statusInset != insets.systemWindowInsetTop) {
                statusInset = insets.systemWindowInsetTop
                requestLayout()
            }
            insets
        }
        addView(header); header.addView(heading); header.addView(closeButton); header.addView(breadcrumb); header.addView(breadcrumbArrow)
        addView(sidebar); sidebar.addView(nav); addView(scroll); scroll.addView(body)
        groups.forEachIndexed { index, title ->
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, sd(16), 0, sd(16))
                isFocusable = true; isClickable = true; contentDescription = "Nhóm $title"
                setOnClickListener {
                    if (detail != null) root(index, true) else {
                        select(index); selectedByClick = index
                        scroll.smoothScrollTo(0, sectionY(index))
                    }
                }
            }
            val indicator = chrome.icon(R.drawable.tn_settings_indicator_tab_layout).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
            row.addView(indicator, LinearLayout.LayoutParams(sd(6), -1).apply { topMargin = sd(10); bottomMargin = sd(10) })
            row.addView(chrome.icon(icons[index]), LinearLayout.LayoutParams(sd(56), sd(56)).apply { marginStart = sd(36) })
            val label = chrome.text(title).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            row.addView(label, LinearLayout.LayoutParams(-2, -2).apply { marginStart = sd(28) })
            nav.addView(row, LinearLayout.LayoutParams(-2, -2)); tabs.add(label); indicators.add(indicator); tabRows.add(row)
        }
        scroll.setOnTouchListener { _, event -> if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) selectedByClick = null; false }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            if (detail == null) {
                val clicked = selectedByClick
                if (clicked != null) { if (kotlin.math.abs(scroll.scrollY - sectionY(clicked)) <= 1) selectedByClick = null }
                else {
                    val bottom = scroll.scrollY > 0 && scroll.scrollY + scroll.height >= body.height + scroll.paddingTop
                    val index = if (bottom) groups.lastIndex else anchors.indices.lastOrNull { anchors[it].top <= scroll.scrollY + sd(1) } ?: 0
                    if (index != selected) select(index)
                }
            }
        }
        root(0, false)
    }

    fun account(response: JSONObject) {
        val user = response.optJSONObject("user") ?: JSONObject()
        accountName = user.optString("name").ifBlank { user.optString("username", "Tài khoản TN") }
        administrator = user.optString("role") == "admin"
        val features = response.optJSONObject("premium_features")
        val hasFeature = features?.keys()?.asSequence()?.any { features.optBoolean(it) } == true
        premium = administrator || hasFeature || response.optJSONObject("local_premium")?.optBoolean("enabled") == true ||
            response.optBoolean("local_premium") || response.optJSONObject("account_plan")?.optString("kind") == "premium"
        accountError = null
        refresh()
    }
    fun loadError(message: String) { accountError = message; refresh() }
    fun wakeStatus() {
        busy = vn.tn.assistant.audio.TnWakeService.consented(context) && !vn.tn.assistant.audio.TnWakeService.isReady()
        if (vn.tn.assistant.audio.TnWakeService.isReady()) accountError = null
        refresh()
    }
    fun saving(value: Boolean) { busy = value }
    /** Only navigation state is saved; permissions and account grants are read afresh. */
    fun saveUiState() = android.os.Bundle().apply {
        putInt("selected_group", selected)
        putString("detail", detail)
        putInt("scroll_y", scroll.scrollY)
    }
    fun restoreUiState(state: android.os.Bundle) {
        selected = state.getInt("selected_group", 0).coerceIn(groups.indices)
        when (state.getString("detail")) {
            "Tiện ích nổi" -> floating()
            "Cảnh báo giao thông" -> traffic()
            "TN ơi" -> wake()
            "Mục đích sử dụng dữ liệu" -> privacy()
            "Tài khoản" -> accountDetails()
            "Thông tin tài khoản" -> planDetails()
            "Cài đặt nâng cao" -> advanced()
            else -> root(selected, false)
        }
        scroll.post { scroll.scrollTo(0, state.getInt("scroll_y", 0).coerceAtLeast(0)) }
    }
    fun refresh() {
        val y = scroll.scrollY
        val page = detail
        when (page) {
            null -> root(selected, false)
            "Tiện ích nổi" -> floating()
            "Cảnh báo giao thông" -> traffic()
            "TN ơi" -> wake()
            "Mục đích sử dụng dữ liệu" -> privacy()
            "Tài khoản" -> accountDetails()
            "Thông tin tài khoản" -> planDetails()
            "Cài đặt nâng cao" -> advanced()
            else -> return
        }
        scroll.post { scroll.scrollTo(0, y) }
    }
    fun back(): Boolean {
        supportDialog?.takeIf { it.isShowing }?.let { it.dismiss(); supportDialog = null; return true }
        confirmation?.let {
            dismissConfirmation()
            if (detail == "Mục đích sử dụng dữ liệu") privacy()
            return true
        }
        drawer?.let { (it as TnSettingsDrawerSurface).dismiss(); return true }
        if (detail != null) { root(selected, true); return true }
        return false
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (entered) return
        entered = true
        // Source fade_in_background_slide_top_content_animator.xml animates these
        // independently: the scene fades in while the content rises one full height.
        if (!animationsEnabled()) return
        background.alpha = 0
        androidx.core.view.OneShotPreDrawListener.add(this) {
            if (!isAttachedToWindow) return@add
            val content = listOf(header, sidebar, scroll)
            content.forEach { it.translationY = height.toFloat() }
            backgroundMotion = android.animation.ValueAnimator.ofInt(0, 255).apply {
                duration = 225L; interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { background.alpha = it.animatedValue as Int; invalidate() }
                start()
            }
            contentMotion = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 400L
                interpolator = android.view.animation.AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in)
                addUpdateListener { animator ->
                    content.forEach { it.translationY = height * (animator.animatedValue as Float) }
                }
                start()
            }
        }
    }
    fun closeWithAnimation() {
        if (closing) return
        closing = true
        if (!isAttachedToWindow || !animationsEnabled()) { close(); return }
        contentMotion?.cancel(); backgroundMotion?.cancel()
        // Source slide_bottom_animator.xml: pTranslateY 0..1, 450ms, the
        // fragment_fast_out_extra_slow_in path, including the background layer.
        val path = android.graphics.Path().apply {
            moveTo(0f, 0f); cubicTo(.05f, 0f, .133333f, .06f, .166666f, .4f)
            cubicTo(.208333f, .82f, .25f, 1f, 1f, 1f)
        }
        animate().translationY(height.toFloat()).setDuration(450L)
            .setInterpolator(android.view.animation.PathInterpolator(path)).withEndAction { close() }.start()
    }
    private fun animationsEnabled(): Boolean =
        android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()
    override fun onDetachedFromWindow() {
        supportDialog?.dismiss(); supportDialog = null
        contentMotion?.cancel(); backgroundMotion?.cancel(); animate().cancel()
        super.onDetachedFromWindow()
    }
    private fun sectionY(index: Int) = (anchors.getOrNull(index)?.top ?: 0).coerceIn(0,
        (body.height + scroll.paddingTop + scroll.paddingBottom - scroll.height).coerceAtLeast(0))
    private fun select(index: Int) {
        selected = index
        tabs.forEachIndexed { i, tab ->
            tab.setTextColor(if (i == index) chrome.cyan else chrome.foreground)
            tab.typeface = if (i == index) chrome.medium else chrome.regular
            tab.isSelected = i == index
            indicators[i].animate().alpha(if (i == index) 1f else 0f).setDuration(200).start()
            tabRows[i].animate().translationX(if (i == index) 0f else -sd(6).toFloat()).setDuration(200).start()
        }
    }
    private fun card(parent: LinearLayout) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = chrome.surface(0x0fffffff, 4)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun root(index: Int, move: Boolean) {
        detail = null; heading.text = "Cài đặt"; heading.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        breadcrumb.visibility = GONE; body.removeAllViews(); anchors.clear()
        groups.indices.forEach { group ->
            val parent = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            body.addView(parent, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) }); anchors.add(parent)
            val section = card(parent)
            when (group) {
                0 -> accountCard(section)
                1 -> {
                    val overlay = TnFloatingControlService.hasPermission(context)
                    val notification = TnMediaAccessService.hasAccess(context)
                    row(section, "Tiện ích nổi", if (prefs.getBoolean("floating_enabled", false)) "Bật" else "Tắt",
                        (overlay && notification) || prefs.getBoolean("floating_enabled", false), rootUtility = true) { floating() }
                    floatingPermissionWarning(section, notification, overlay)
                    row(section, "Cảnh báo giao thông", modeLabel(), prefs.getBoolean("floating_enabled", false), rootUtility = true, badge = R.drawable.tn_settings_feature_premium) { traffic() }
                    if (!prefs.getBoolean("floating_enabled", false)) warning(section, "Tính năng chỉ hỗ trợ khi Tiện ích nổi được bật")
                    row(section, "TN ơi", if (prefs.getBoolean("wake_word_enabled", false)) "Bật" else "Tắt", rootUtility = true, badge = R.drawable.tn_settings_badge_beta) { wake() }
                    toggle(section, "Lời chào khởi động", "welcome_enabled", true)
                }
                2 -> appRow(section, "Ứng dụng bản đồ mặc định", "map_package", maps, "com.google.android.apps.maps")
                3 -> {
                    toggle(section, "Cảnh báo khi mở video", "video_warning", true)
                    appRow(section, "Ứng dụng video mặc định", "video_package", videos, "")
                    appRow(section, "Ứng dụng truyền hình mặc định", "tv_package", television, "")
                    appRow(section, "Ứng dụng radio mặc định", "radio_package", radios, "")
                }
                4 -> {
                    row(section, "Điều khoản sử dụng", "") { action("terms") }
                    row(section, "Mục đích sử dụng dữ liệu", "") { privacy() }
                }
                5 -> {
                    section.addView(chrome.text("Phiên bản ứng dụng").apply { setPadding(sd(32), sd(26), sd(32), 0) })
                    section.addView(chrome.text(BuildConfig.VERSION_NAME, 24f).apply { setPadding(sd(32), 0, sd(32), sd(26)) })
                    divider(section)
                    row(section, "Cập nhật phiên bản mới", "Kiểm tra") { action("updates") }
                }
                6 -> {
                    choice(section, "Giọng đọc TN Assistant", "agent_voice", listOf("Miền Nam" to "south", "Miền Bắc" to "north"), "south")
                    row(section, "Cài đặt nâng cao", "") { advanced() }
                }
                7 -> {
                    row(section, "Kiểm tra hệ thống", "") { action("diagnostics") }
                    row(section, "Hướng dẫn và liên hệ hỗ trợ", "") { action("support") }
                }
            }
            trimDivider(section)
        }
        select(index); requestLayout()
        if (move) scroll.post { scroll.smoothScrollTo(0, sectionY(index)) }
    }
    private fun accountCard(parent: LinearLayout) {
        val profile = TnSettingsAccountCard(context, accountName, premium,
            accountError ?: if (premium == true) "Tài khoản TN Premium" else "Tính năng giới hạn") { accountDetails() }
        parent.addView(profile, LinearLayout.LayoutParams(-1, -2))
        // The source account recycler reserves a final 1dp item decoration.
        parent.setPadding(0, 0, 0, dp(1))
    }
    private fun sub(title: String, build: (LinearLayout) -> Unit) {
        detail = title; heading.text = title; heading.gravity = Gravity.CENTER
        breadcrumb.visibility = VISIBLE; body.removeAllViews()
        val card = card(body); build(card); trimDivider(card); scroll.scrollTo(0, 0); requestLayout()
    }
    fun accountDetails() = sub("Tài khoản") { card ->
        if (!administrator) row(card, "Nâng cấp gói dịch vụ", "") { action("premium") }
        row(card, "Đăng xuất tài khoản", "") {
            confirm("Xác nhận đăng xuất?", "Bạn sẽ cần đăng nhập lại để tiếp tục sử dụng TN.", "HỦY", "ĐĂNG XUẤT",
                onRight = { action("logout") })
        }
    }
    private fun deleteAccountRow(card: LinearLayout) {
        row(card, "Xoá tài khoản", "") {
            confirm("Xác nhận xoá tài khoản",
                "Xoá tài khoản sẽ:\n\n• Toàn bộ dữ liệu sẽ bị xoá và không thể khôi phục lại\n• Các dịch vụ trả phí sẽ bị dừng và không thể hoàn tác",
                "XOÁ TÀI KHOẢN", "ĐÓNG", onLeft = { action("delete-account") })
        }
    }
    fun dataPage(key: String, response: JSONObject) {
        val title = when (key) { "devices" -> "Thiết bị liên kết"; "history" -> "Lịch sử hoạt động"; "premium" -> "Thông tin gói dịch vụ"; "diagnostics" -> "Kiểm tra hệ thống"; else -> "Hướng dẫn và liên hệ hỗ trợ" }
        sub(title) { card ->
            if (key == "diagnostics") {
                val health = response.optJSONObject("health") ?: JSONObject()
                val readiness = response.optJSONObject("readiness") ?: JSONObject()
                row(card, "Kết nối máy chủ TN", if (health.optString("status") == "ok") "Đã kết nối" else "Không kết nối") { action("diagnostics") }
                row(card, "Dịch vụ giọng nói", if (readiness.optBoolean("ready")) "Sẵn sàng" else "Chưa sẵn sàng") { action("diagnostics") }
                val providers = health.optJSONObject("providers") ?: JSONObject()
                val checks = readiness.optJSONObject("checks") ?: JSONObject()
                listOf("asr" to "Nhận dạng giọng nói", "tts" to "Phát giọng nói", "zalo" to "Đăng nhập Zalo").forEach { (id, label) ->
                    val configured = providers.optJSONObject(id)?.optBoolean("configured") == true
                    val value = if (id == "zalo") {
                        if (configured) "Đã cấu hình" else "Chưa cấu hình"
                    } else {
                        val check = checks.optJSONObject(id)
                        TnReadinessDisplay.providerLabel(check?.optString("status"), check?.optBoolean("healthy") == true, configured)
                    }
                    row(card, label, value) { }
                }
                description(card, "Trạng thái xác minh phản ánh dịch vụ phía máy chủ. Trạng thái này không thay thế việc thử trực tiếp micro, loa và đăng nhập trên thiết bị.")
            } else if (key == "support") {
                description(card, "TN Assistant\nPhiên bản ${BuildConfig.VERSION_NAME}")
                row(card, "Hướng dẫn sử dụng", "Mở") { action("guide") }
                row(card, "Liên hệ hỗ trợ", "support@tnfastvn.com") { action("support-email") }
                val support = response.optJSONObject("support") ?: JSONObject()
                TnSupportPolicy.pages.forEach { page ->
                    val address = TnSupportPolicy.url(support.optString(page.key))
                    row(card, page.title, if (address == null) "Chưa cấu hình" else if (page.qr) "Mã QR" else "Mở") {
                        if (address == null) {
                            confirm(page.title, "Chưa có địa chỉ hỗ trợ hợp lệ. Quản trị viên cần bổ sung cấu hình TN.",
                                "ĐÓNG", "TẢI LẠI", onRight = { action("support") })
                        } else {
                            supportDialog?.dismiss()
                            supportDialog = TnSupportDialog(context, page, address).also { it.show() }
                        }
                    }
                }
            } else {
                val items = response.optJSONArray("items") ?: response.optJSONArray("products") ?: org.json.JSONArray()
                if (items.length() == 0) description(card, "Chưa có dữ liệu.")
                for (index in 0 until items.length().coerceAtMost(100)) {
                    val item = items.optJSONObject(index) ?: continue
                    val name = when (key) { "devices" -> item.optString("name").ifBlank { item.optString("device_name", "Thiết bị TN") }; "history" -> item.optString("text", "Yêu cầu trợ lý"); else -> item.optString("name", "Gói dịch vụ") }
                    val value = when (key) { "devices" -> if (item.optString("id") == deviceId || item.optString("device_id") == deviceId) "Thiết bị này" else "Đã liên kết"; "history" -> item.optString("created_at"); else -> item.optString("description") }
                    row(card, name, value) { }
                }
            }
        }
    }
    fun floating() {
        val overlayGranted = TnFloatingControlService.hasPermission(context)
        val notificationGranted = TnMediaAccessService.hasAccess(context)
        prefs.edit().putBoolean("tn_overlay_granted", overlayGranted).apply()
        sub("Tiện ích nổi") { card ->
            toggle(card, "Tiện ích nổi", "floating_enabled", false,
                (overlayGranted && notificationGranted) || prefs.getBoolean("floating_enabled", false), pageToggle = true) { enable ->
                if (enable) save("floating_enabled", true)
                else confirm("Tắt Tiện ích nổi?", "Khi tắt, bạn sẽ không còn dùng được nút kích hoạt nhanh TN và tính năng Cảnh báo giao thông.",
                    "HỦY", "TẮT TIỆN ÍCH NỔI", onRight = { save("floating_enabled", false) })
            }
            floatingPermissionWarning(card, notificationGranted, overlayGranted)
        }
        // Original group decoration excludes its description-only item; the
        // options form a second card below that transparent description.
        description(body, "Tiện ích nổi cho phép một số tiện ích của TN luôn hiển thị trên màn hình.")
        if (prefs.getBoolean("floating_enabled", false)) {
            val card = card(body)
            choice(card, "Kích cỡ", "floating_size", listOf("To" to "large", "Tiêu chuẩn" to "standard", "Nhỏ" to "small"), "standard", plainNavigation = true)
            choice(card, "Độ đậm hiển thị", "floating_opacity", listOf("Tự động" to "auto", "Bình thường" to "normal", "Nhạt" to "faint", "Rất nhạt" to "very_faint"), "auto", plainNavigation = true)
            trimDivider(card)
        }
    }
    fun planDetails() = sub("Thông tin tài khoản") { card ->
        val plan = TnFloatingAccountPlan.read(context)
        val label = when(plan?.effectiveKind()) { "admin" -> "Admin"; "premium" -> "Premium"; "basic" -> "Basic"; else -> "Đang kiểm tra…" }
        description(card, "Tài khoản: $accountName\nGói hiện tại: $label")
        if (plan?.effectiveKind() == "premium") {
            val expiry = plan.expires?.let { java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)) }.orEmpty()
            description(card, "Còn ${plan.days()} ngày · Hết hạn: $expiry")
        }
        if (plan?.effectiveKind() == "admin") description(card, "Tài khoản quản trị có quyền sử dụng các tính năng TN Premium.")
    }
    private fun floatingPermissionWarning(card: LinearLayout, notification: Boolean, overlay: Boolean) {
        when {
            !notification -> {
                val canRequest = TnMediaAccessService.canRequestAccess(context)
                warning(card, "Tính năng cần cấp quyền truy cập thông báo để sử dụng",
                    if (canRequest) "Cấp quyền" else "", if (canRequest) ({ action("notification") }) else null)
            }
            !overlay -> warning(card, "Tính năng cần có quyền hiển thị để sử dụng", "Cấp quyền") { action("overlay") }
        }
    }
    private fun modeLabel() = trafficModes.firstOrNull { it.second == prefs.getString("traffic_warning_mode", "none") }?.first ?: "Tắt"
    fun traffic() = sub("Cảnh báo giao thông") { card ->
        choice(card, "Cảnh báo giao thông", "traffic_warning_mode", trafficModes, "none")
        choice(card, "Ngưỡng báo vượt tốc độ", "traffic_overspeed_offset", listOf("Dưới giới hạn 5km/h" to "-5", "Bằng tốc độ giới hạn" to "0", "Vượt giới hạn 5km/h" to "5"), "0")
        toggle(card, "Luôn hiện khi dùng bản đồ", "traffic_always_on_map", false)
        warning(card, "Chỉ hiển thị biển báo khi có dữ liệu đường đã xác minh.")
        description(card, "Nhận cảnh báo khi có biển báo cần chú ý: tốc độ giới hạn, camera giao thông và các biển cấm khác.")
    }
    private fun wake() = sub("TN ơi") { card ->
        accountError?.let { description(card, it) }
        description(card, vn.tn.assistant.audio.TnWakeService.status(context))
        if (premium != true) { description(card, "Nhận diện “TN ơi” thuộc gói Premium. Câu lệnh giọng nói cơ bản vẫn dùng được trong gói Basic."); row(card, "Nâng cấp TN Premium", "") { action("premium") } }
        else {
            toggle(card, "Kích hoạt TN bằng “TN ơi”", "wake_word_enabled", false)
            description(card, "Khi bật, TN dùng micro để nhận “ti en ơi” hoặc “tê nờ ơi” trên thiết bị. Lượt nói sau khi nhận từ đánh thức được gửi tới máy chủ TN. Có thể tắt nghe nền từ thông báo micro.")
            choice(card, "Ngủ khi không nói tiếp", "conversation_idle_timeout_ms", listOf(5, 10, 15, 30, 60).map { "$it giây" to (it * 1000).toString() }, "10000")
            choice(card, "Im lặng để thực thi câu lệnh", "command_end_silence_ms", listOf("0,5 giây" to "500", "1 giây" to "1000", "1,5 giây" to "1500", "2 giây" to "2000", "3 giây" to "3000"), "1000")
            toggle(card, "Chia sẻ dữ liệu", "wake_word_data_sharing", false)
        }
    }
    fun privacy(): Unit {
        sub("Mục đích sử dụng dữ liệu") { card ->
            row(card, "Quyền riêng tư và đồng ý sử dụng dữ liệu", "") { action("privacy") }
            toggle(card, "Lưu lịch sử hoạt động", "private_mode", false, inverse = true, commit = { privateMode ->
                if (privateMode) {
                    confirm(
                        "Tắt lưu lịch sử?",
                        "Các yêu cầu mới sẽ không xuất hiện trong lịch sử trợ lý. Lịch sử đã lưu trước đây vẫn còn. Bạn có muốn tiếp tục?",
                        "HỦY",
                        "TẮT LƯU",
                        onLeft = { privacy() },
                        onRight = { save("private_mode", true) },
                    )
                } else {
                    save("private_mode", false)
                }
            })
            row(card, "Xem lịch sử trợ lý", "") { action("history") }
            deleteAccountRow(card)
        }
    }
    private fun advanced() = sub("Cài đặt nâng cao") { card ->
        choice(card, "Chế độ ghi âm", "record_mode", listOf("Mặc định" to "default", "Nâng cao" to "advanced"), "default", confirmChange = true)
        choice(card, "Chế độ phát giọng nói", "playback_mode", listOf("Mặc định" to "default", "Nâng cao" to "advanced"), "default", confirmChange = true)
        row(card, "Thiết bị liên kết", deviceId) { action("devices") }
    }
    private fun description(parent: LinearLayout, text: String) = parent.addView(chrome.text(text, 24f).apply {
        setTextColor(0xb2ffffff.toInt()); setPadding(sd(32), sd(12), sd(32), sd(32))
    }, LinearLayout.LayoutParams(-1, -2))
    private fun divider(parent: LinearLayout) = parent.addView(View(context).apply {
        setBackgroundColor(0x59ffffff); tag = "divider"
    }, LinearLayout.LayoutParams(-1, (resources.displayMetrics.density * .75f).roundToInt()).apply { marginStart = sd(32); marginEnd = sd(32) })
    private fun trimDivider(parent: LinearLayout) {
        if (parent.childCount == 0) return
        val last = parent.getChildAt(parent.childCount - 1)
        if (last.tag == "divider") last.background = null
        if (last.tag == "tnSettingsRow") (last as ViewGroup).getChildAt(1).background = null
        if (last.tag == "tnSettingsWarningRow") (last as ViewGroup).getChildAt(1).background = null
    }
    private fun row(parent: LinearLayout, title: String, value: String, enabled: Boolean = true,
                    rootUtility: Boolean = false, badge: Int = 0, plainNavigation: Boolean = false, click: () -> Unit) {
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val verticalPadding = sd(if (rootUtility) 26 else 28)
            setPadding(sd(32), verticalPadding, sd(32), verticalPadding)
            // Source switch/navigation rows differ: the utility switch row has
            // themed feedback, while floating size/opacity navigation has none.
            background = if (plainNavigation) null else if (rootUtility) chrome.settingsItemRipple() else chrome.ripple()
            isEnabled = enabled; isFocusable = true; contentDescription = title
            setOnClickListener { if (!busy && enabled) click() }
        }
        val label = chrome.text(title).apply {
            alpha = if (enabled) 1f else .25f
            includeFontPadding = !rootUtility
            if (!rootUtility) setPadding(0, 0, sd(16), 0)
        }
        val labelContent: View = if (badge == 0) label else object : ViewGroup(context) {
            private val icon = chrome.icon(badge).apply {
                tag = if (badge == R.drawable.tn_settings_badge_beta) "tnSettingsBetaBadge" else "tnSettingsFeaturePremiumBadge"
                alpha = if (enabled) 1f else .3f
            }
            init { addView(label); addView(icon) }
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val iconWidth = sd(if (badge == R.drawable.tn_settings_badge_beta) 112 else 98)
                icon.measure(MeasureSpec.makeMeasureSpec(iconWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sd(32), MeasureSpec.EXACTLY))
                label.measure(MeasureSpec.makeMeasureSpec((width - iconWidth - sd(22) - sd(16)).coerceAtLeast(0), MeasureSpec.AT_MOST), h)
                setMeasuredDimension(width, maxOf(label.measuredHeight, icon.measuredHeight))
            }
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                label.layout(0, (height - label.measuredHeight) / 2, label.measuredWidth, (height + label.measuredHeight) / 2)
                val x = label.measuredWidth + sd(22)
                icon.layout(x, (height - icon.measuredHeight) / 2, x + icon.measuredWidth, (height + icon.measuredHeight) / 2)
            }
        }
        line.addView(labelContent, LinearLayout.LayoutParams(0, -2, 1f))
        if (value.isNotBlank()) line.addView(chrome.text(value).apply {
            includeFontPadding = false; alpha = if (enabled) 1f else .25f; maxWidth = resources.displayMetrics.widthPixels / 3
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = sd(if (rootUtility) 40 else 16) })
        line.addView(chrome.icon(R.drawable.tn_settings_ic_settings_arrow_right).apply { alpha = if (enabled) 1f else .25f }, LinearLayout.LayoutParams(sd(24), sd(24)))
        val wrapper = FrameLayout(context).apply { tag = "tnSettingsRow" }
        wrapper.addView(line, LayoutParams(-1, -2))
        wrapper.addView(View(context).apply { setBackgroundColor(0x59ffffff); tag = "divider" },
            LayoutParams(-1, (resources.displayMetrics.density * .75f).roundToInt(), Gravity.BOTTOM).apply {
                marginStart = sd(32); marginEnd = sd(32)
            })
        parent.addView(wrapper, LinearLayout.LayoutParams(-1, -2))
    }
    private fun toggle(parent: LinearLayout, title: String, key: String, default: Boolean, enabled: Boolean = true, inverse: Boolean = false, pageToggle: Boolean = false, commit: ((Boolean) -> Unit)? = null) {
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(sd(32), if (pageToggle) 0 else sd(26), sd(32), if (pageToggle) 0 else sd(26)) }
        line.addView(chrome.text(title).apply {
            includeFontPadding = false; alpha = if (enabled) 1f else .25f
            if (pageToggle) setPadding(0, sd(28), 0, sd(28))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val options = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL; alpha = if (enabled) 1f else .25f }
        val yes = chrome.radio("Bật").apply { id = View.generateViewId(); isEnabled = enabled && !busy; contentDescription = "$title: Bật" }
        val no = chrome.radio("Tắt").apply { id = View.generateViewId(); isEnabled = enabled && !busy; contentDescription = "$title: Tắt" }
        if (pageToggle) listOf(yes, no).forEach { it.setPadding(sd(8), sd(28), 0, sd(28)) }
        options.addView(yes); options.addView(no, RadioGroup.LayoutParams(-2, -2).apply { marginStart = sd(16) })
        val checked = prefs.getBoolean(key, default).let { if (inverse) !it else it }
        options.check(if (checked) yes.id else no.id)
        options.setOnCheckedChangeListener { _, id ->
            if (busy) return@setOnCheckedChangeListener
            val value = (id == yes.id).let { if (inverse) !it else it }
            if (commit == null) save(key, value) else commit(value)
        }
        line.addView(options)
        if (pageToggle) {
            val wrapper = FrameLayout(context).apply { tag = "tnSettingsRow" }
            wrapper.addView(line, LayoutParams(-1, -2))
            wrapper.addView(View(context).apply { setBackgroundColor(0x59ffffff); tag = "divider" },
                LayoutParams(-1, (resources.displayMetrics.density * .75f).roundToInt(), Gravity.BOTTOM).apply {
                    marginStart = sd(32); marginEnd = sd(32)
                })
            parent.addView(wrapper, LinearLayout.LayoutParams(-1, -2))
        } else { parent.addView(line); divider(parent) }
    }
    private fun warning(parent: LinearLayout, value: String, label: String = "", click: (() -> Unit)? = null) {
        if (parent.childCount > 0 && parent.getChildAt(parent.childCount - 1).tag == "divider") parent.removeViewAt(parent.childCount - 1)
        val wrapper = FrameLayout(context).apply { tag = "tnSettingsWarningRow" }
        wrapper.addView(TnSettingsWarningView(context, value, label, click), LayoutParams(-1, -2).apply {
            marginStart = sd(32); marginEnd = sd(32); bottomMargin = sd(16)
        })
        wrapper.addView(View(context).apply { tag = "divider"; setBackgroundColor(0x59ffffff) },
            LayoutParams(-1, (resources.displayMetrics.density * .75f).roundToInt(), Gravity.BOTTOM).apply {
                marginStart = sd(32); marginEnd = sd(32)
            })
        parent.addView(wrapper, LinearLayout.LayoutParams(-1, -2))
    }
    private fun selectedValue(key: String, default: String): String = prefs.all[key]?.toString() ?: default
    private fun choice(parent: LinearLayout, title: String, key: String, values: List<Pair<String, String>>, default: String, confirmChange: Boolean = false, plainNavigation: Boolean = false) {
        val value = selectedValue(key, default)
        row(parent, title, values.firstOrNull { it.second == value }?.first ?: value, plainNavigation = plainNavigation) {
            drawer(title, values, value) { selected ->
                if (selected == value) return@drawer
                val setting: Any = if (key in setOf("conversation_idle_timeout_ms", "command_end_silence_ms", "traffic_overspeed_offset")) selected.toInt() else selected
                if (confirmChange) confirm("Thay đổi cài đặt?",
                    "Thay đổi này có thể ảnh hưởng đến các thiết lập hiện tại của ứng dụng. Vui lòng chỉ thực hiện theo hướng dẫn từ KTV.",
                    "HỦY", "XÁC NHẬN", onRight = { save(key, setting) })
                else save(key, setting)
            }
        }
    }
    private fun appRow(parent: LinearLayout, title: String, key: String, values: List<Pair<String, String>>, default: String) {
        val value = selectedValue(key, default)
        val name = values.firstOrNull { it.second == value }?.first ?: if (value.isBlank()) "Tự động" else appName(value)
        row(parent, title, name) {
            drawer(title, values + ("Chọn ứng dụng đã cài…" to "installed-picker"), value) {
                if (it == "installed-picker") installedApps(title, key, value) else save(key, it)
            }
        }
    }
    private fun appName(pkg: String) = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    private fun installedApps(title: String, key: String, current: String) {
        val query = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val packages = context.packageManager.queryIntentActivities(query, 0).map { it.activityInfo.packageName }.distinct()
        drawer(title, listOf("Tự động" to "") + packages.map { appName(it) to it }.sortedBy { it.first.lowercase() }, current) { save(key, it) }
    }
    private fun drawer(title: String, values: List<Pair<String, String>>, current: String, pick: (String) -> Unit) {
        drawer?.let { removeView(it) }
        lateinit var modal: TnSettingsDrawerSurface
        modal = TnSettingsDrawerSurface(context, title, values, current,
            { sd(it) }, { value, size, strong -> chrome.text(value, size, strong) },
            { chrome.radio("") },
            { if (drawer === modal) { removeView(modal); drawer = null } },
            { if (!busy) pick(it) })
        drawer = modal
        addView(modal)
        requestLayout()
    }
    private fun dismissConfirmation() {
        confirmation?.let { removeView(it) }
        confirmation = null
    }
    private fun confirm(title: String, message: String, left: String, right: String,
                        onLeft: () -> Unit = {}, onRight: () -> Unit = {}): Unit {
        dismissConfirmation()
        val modal = TnSettingsConfirmation(context, title, message, left, right,
            { dismissConfirmation(); onLeft() },
            { dismissConfirmation(); onRight() },
            { dismissConfirmation(); onLeft() })
        confirmation = modal
        addView(modal)
        requestLayout()
    }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec); setMeasuredDimension(w, h)
        val toolbar = settingsToolbarHeight()
        val head = (sd(16) + toolbar).coerceAtMost(h / 4); val side = sidebarWidth(w)
        sidebar.visibility = if (side == 0) GONE else VISIBLE
        closeButton.visibility = if (side == 0 && detail != null) GONE else VISIBLE
        breadcrumbArrow.visibility = breadcrumb.visibility
        fun exact(v: View, width: Int, height: Int) = v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        exact(header, w, head); exact(closeButton, sd(50), sd(50))
        exact(breadcrumbArrow, sd(16), sd(28))
        val titleStart = if (side == 0) sd(106) else side + sd(16)
        heading.measure(MeasureSpec.makeMeasureSpec((w - titleStart - sd(16)).coerceAtLeast(1), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(toolbar, MeasureSpec.AT_MOST))
        breadcrumb.measure(MeasureSpec.makeMeasureSpec((w - side - sd(48)).coerceAtLeast(0), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(toolbar, MeasureSpec.AT_MOST))
        exact(sidebar, side, (h - head - statusInset).coerceAtLeast(0)); exact(scroll, w - side, (h - head - statusInset).coerceAtLeast(0))
        drawer?.let { exact(it, w, (h - statusInset).coerceAtLeast(0)) }; confirmation?.let { exact(it, w, h) }
    }
    override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t; val toolbar = settingsToolbarHeight()
        val head = (sd(16) + toolbar).coerceAtMost(h / 4); val side = sidebarWidth(w)
        header.layout(0, statusInset, w, head + statusInset)
        val closeTop = sd(16) + (toolbar - sd(50)) / 2
        closeButton.layout(sd(40), closeTop, sd(90), closeTop + sd(50))
        val titleStart = if (side == 0) sd(106) else side + sd(16)
        val x = if (detail == null) titleStart else side + ((w - side) / 2f).roundToInt() - heading.measuredWidth / 2
        val y = sd(16) + (toolbar - heading.measuredHeight) / 2
        heading.layout(x, y, x + heading.measuredWidth, y + heading.measuredHeight)
        val crumbTop = sd(16) + (toolbar - breadcrumb.measuredHeight) / 2
        breadcrumb.layout(side + sd(48), crumbTop, side + sd(48) + breadcrumb.measuredWidth, crumbTop + breadcrumb.measuredHeight)
        val arrowTop = sd(16) + (toolbar - breadcrumbArrow.measuredHeight) / 2
        breadcrumbArrow.layout(side + sd(16), arrowTop, side + sd(16) + breadcrumbArrow.measuredWidth, arrowTop + breadcrumbArrow.measuredHeight)
        sidebar.layout(0, head + statusInset, side, h); scroll.layout(side, head + statusInset, w, h)
        drawer?.layout(0, statusInset, w, h); confirmation?.layout(0, 0, w, h)
    }
    // The original fits a native Toolbar, hides its category rail on narrow
    // windows and places the root title after the close button in that layout.
    private fun settingsToolbarHeight(): Int = dp(if (resources.configuration.smallestScreenWidthDp >= 600) 64 else 56)
    private fun sidebarWidth(width: Int): Int =
        if (width / resources.displayMetrics.density < 600f) 0 else sd(360).coerceIn(width / 4, width * 2 / 5)
    companion object {
        val maps = listOf("Google Maps" to "com.google.android.apps.maps", "Navitel" to "com.navitel", "VietMap S1" to "com.vietmap.s1OBU", "VietMap S2" to "com.vietmap.S2OBU", "VietMap Live" to "vn.vietmap.live")
        val videos = listOf("Tự động" to "", "YouTube" to "com.google.android.youtube")
        val television = listOf("Tự động" to "", "VTV Go" to "vn.vtv.vtvgo", "TV360" to "com.viettel.tv360", "MyTV" to "vn.mytvnet.mobileb2c", "FPT Play" to "com.fplay.activity")
        val radios = listOf("Tự động" to "", "Radio Việt Nam" to "com.appmind.radios.vn")
    }
}

/** The independent source set uses the same dialog artwork, typography and two-action layout as SettingsConfirmation. */
internal class TnSettingsConfirmation(
    context: Context,
    title: String,
    message: String,
    leftText: String,
    rightText: String,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCancel: () -> Unit,
) : FrameLayout(context) {
    private val chrome = TnSettingsChrome(context)
    private fun sd(value: Int) = chrome.px(value)
    private var messageMaxHeight = Int.MAX_VALUE
    private val shell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val responsiveTexts = mutableListOf<Pair<TextView, Float>>()
    private fun dialogText(value: String, size: Float, strong: Boolean = false) = chrome.text(value, size, strong).also {
        responsiveTexts += it to it.textSize
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
            resources.displayMetrics.heightPixels else MeasureSpec.getSize(heightMeasureSpec)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED)
            resources.displayMetrics.widthPixels else MeasureSpec.getSize(widthMeasureSpec)
        shell.layoutParams.width = minOf(sd(800), (width - sd(32)).coerceAtLeast(1))
        val textScale = chrome.windowScale(width, height)
        responsiveTexts.forEach { (view, originalSize) -> view.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalSize * textScale) }
        messageMaxHeight = (height * .45f).roundToInt().coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    init {
        setBackgroundColor(0xaa000000.toInt())
        isClickable = true
        isFocusableInTouchMode = true
        setOnClickListener { onCancel() }
        if (android.os.Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = title

        addView(shell, LayoutParams(sd(800).coerceAtMost(resources.displayMetrics.widthPixels - sd(32)), -2, Gravity.CENTER))
        val frame = FrameLayout(context).apply {
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0xf200225b.toInt())
                cornerRadius = (2 * resources.displayMetrics.density).toInt().toFloat()
                setStroke((1.5f * resources.displayMetrics.density).toInt(), 0xaaffffff.toInt())
            }
        }
        shell.addView(frame, LinearLayout.LayoutParams(-1, -2))
        frame.addView(chrome.icon(R.drawable.tn_settings_dialog_light_bottom).apply { scaleType = ImageView.ScaleType.FIT_XY },
            LayoutParams(-1, sd(33), Gravity.BOTTOM))
        val panel = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = MeasureSpec.getSize(heightMeasureSpec)
                var fixed = paddingTop + paddingBottom
                var scrolling: View? = null
                for (index in 0 until childCount) {
                    val child = getChildAt(index)
                    if (child is ScrollView) { scrolling = child; continue }
                    child.measure(MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1), MeasureSpec.EXACTLY),
                        getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0, child.layoutParams.height))
                    fixed += child.measuredHeight
                }
                scrolling?.let { child ->
                    val budget = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) messageMaxHeight
                        else (available - fixed).coerceAtLeast(1)
                    child.measure(MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(minOf(messageMaxHeight, budget), MeasureSpec.AT_MOST))
                }
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(fixed + (scrolling?.measuredHeight ?: 0), heightMeasureSpec))
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(sd(32), 0, sd(32), sd(8)) }
        frame.addView(panel, LayoutParams(-1, -2))
        panel.addView(dialogText(title, 36f, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, sd(16), 0, sd(16))
        }, LinearLayout.LayoutParams(-1, -2))
        val content = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) messageMaxHeight
                    else minOf(MeasureSpec.getSize(heightMeasureSpec), messageMaxHeight)
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false }
        val text = dialogText(message, 22f).apply {
            gravity = Gravity.TOP or Gravity.START
            minLines = 2
            setLineSpacing(0f, 1.25f)
            setPadding(0, sd(16), 0, sd(16))
        }
        content.addView(FrameLayout(context).apply {
            setPadding(0, 0, 0, sd(16))
            addView(text, LayoutParams(-1, -2))
        })
        panel.addView(content, LinearLayout.LayoutParams(-1, -2))
        panel.addView(View(context).apply { setBackgroundColor(0x59ffffff) }, LinearLayout.LayoutParams(-1, sd(2)))
        val buttons = object : LinearLayout(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                if (childCount == 3) {
                    val available = (MeasureSpec.getSize(widthSpec) - sd(2)).coerceAtLeast(0)
                    (getChildAt(0).layoutParams as LinearLayout.LayoutParams).apply { width = (available + 1) / 2; weight = 0f }
                    (getChildAt(2).layoutParams as LinearLayout.LayoutParams).apply { width = available / 2; weight = 0f }
                }
                super.onMeasure(widthSpec, heightSpec)
            }
        }.apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(label: String, action: () -> Unit) = dialogText(label, 22f).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(chrome.cyan)
            setPadding(0, sd(20), 0, sd(20))
            background = chrome.ripple()
            isFocusable = true
            setOnClickListener {
                if (isEnabled) {
                    buttons.isEnabled = false
                    for (index in 0 until buttons.childCount) buttons.getChildAt(index).isEnabled = false
                    action()
                }
            }
        }
        buttons.addView(button(leftText, onLeft), LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(View(context).apply { setBackgroundColor(0x59ffffff) }, LinearLayout.LayoutParams(sd(2), sd(36)))
        buttons.addView(button(rightText, onRight), LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        shell.addView(View(context), LinearLayout.LayoutParams(-1, sd(8)))
    }
}

internal class TnSettingsChrome(private val context: Context) {
    private val config = context.resources.configuration
    private fun layoutFactor(smallest: Int, width: Int): Float = when {
        smallest >= 600 -> .85f
        smallest >= 400 && width >= 1200 -> 1f
        smallest >= 400 && width >= 682 -> .55f
        smallest >= 400 -> .6f
        smallest >= 300 && width >= 960 -> .6f
        smallest >= 300 -> .45f
        smallest >= 200 -> .3f
        else -> 1f
    }
    private val factor = layoutFactor(config.smallestScreenWidthDp, config.screenWidthDp)
    fun windowScale(width: Int, height: Int): Float {
        val density = context.resources.displayMetrics.density
        val smallest = minOf(config.smallestScreenWidthDp, (minOf(width, height) / density).toInt())
        return minOf(1f, layoutFactor(smallest.coerceAtLeast(200), (width / density).toInt()) / factor)
    }
    val cyan = 0xff37dee7.toInt()
    val foreground = 0xffe9f3ff.toInt()
    val regular = Typeface.createFromAsset(context.assets, "fonts/barlowregular.ttf")
    val medium = Typeface.createFromAsset(context.assets, "fonts/barlowmedium.ttf")
    private val semibold = Typeface.createFromAsset(context.assets, "fonts/barlowsemibold.ttf")
    fun px(value: Int) = (value * factor * context.resources.displayMetrics.density).roundToInt()
    fun text(value: String, size: Float = 28f, strong: Boolean = false) = TextView(context).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_PX, (size * factor * resources.displayMetrics.scaledDensity).roundToInt().toFloat())
        typeface = if (strong) semibold else regular; setTextColor(this@TnSettingsChrome.foreground); includeFontPadding = true
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    fun icon(resource: Int) = ImageView(context).apply { setImageResource(resource); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun surface(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius * context.resources.displayMetrics.density }
    fun ripple() = RippleDrawable(ColorStateList.valueOf(0x1fffffff), null, surface(Color.WHITE, 4))
    fun settingsItemRipple(): android.graphics.drawable.Drawable {
        val themed = android.view.ContextThemeWrapper(context, R.style.TnReferenceSettingsItemTheme)
        val attributes = themed.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return try { requireNotNull(attributes.getDrawable(0)) } finally { attributes.recycle() }
    }
    fun radio(value: String) = RadioButton(context).apply {
        text = value; typeface = regular; setTextColor(this@TnSettingsChrome.foreground)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, (28 * factor * resources.displayMetrics.scaledDensity).roundToInt().toFloat())
        buttonTintList = ColorStateList.valueOf(cyan); includeFontPadding = false
        minHeight = (32 * resources.displayMetrics.density).roundToInt(); setPadding(px(8), 0, 0, 0)
    }
}
