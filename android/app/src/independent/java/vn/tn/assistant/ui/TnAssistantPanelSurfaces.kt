package vn.tn.assistant.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import vn.tn.assistant.R
import kotlin.math.max

/** Seven continuous settings sections with a fixed category rail, matching the source app's navigation model. */
class TnAssistantSettingsSurface(
    context: Context,
    close: () -> Unit,
    linkZalo: () -> Unit,
    setup: () -> Unit,
    floating: () -> Unit,
    media: () -> Unit,
    privacy: () -> Unit,
    logout: () -> Unit,
    update: () -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val regular = Typeface.createFromAsset(context.assets, "fonts/barlowregular.ttf")
    private val semibold = Typeface.createFromAsset(context.assets, "fonts/barlowsemibold.ttf")
    private fun dp(value: Int) = (value * density).toInt()

    init {
        setBackgroundColor(0xe5021f4e.toInt())
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(root, LayoutParams(-1, -1))
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(24), 0)
        }
        root.addView(View(context), LinearLayout.LayoutParams(-1, dp(24)))
        val closeButton = text("‹", 42f, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Đóng cài đặt"
            background = ripple()
            isClickable = true
            setOnClickListener { close() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(58), dp(64)))
        header.addView(text("Cài đặt TN Assistant", 28f, true), LinearLayout.LayoutParams(0, -1, 1f))
        val logo = ImageView(context).apply {
            setImageDrawable(TnWelcomeAppMark(context))
            contentDescription = "TN Assistant"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(72)))
        root.addView(View(context).apply { setBackgroundResource(R.drawable.tn_reference_welcome_divider) },
            LinearLayout.LayoutParams(-1, dp(2)))

        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(8), dp(12))
        }
        val pane = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
        val sections = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(22), dp(64))
        }
        pane.addView(sections, ViewGroup.LayoutParams(-1, -2))
        body.addView(nav, LinearLayout.LayoutParams(dp(142), -1))
        body.addView(View(context).apply { setBackgroundColor(0x55ffffff) }, LinearLayout.LayoutParams(dp(1), -1))
        body.addView(pane, LinearLayout.LayoutParams(0, -1, 1f))

        val sectionViews = mutableListOf<View>()
        fun section(title: String, key: String, configure: LinearLayout.() -> Unit) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(0x0fffffff)
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(1), 0x33ffffff)
                }
            }
            val heading = text(title, 23f, true).apply { setTextColor(0xff37dee7.toInt()) }
            card.addView(heading, LinearLayout.LayoutParams(-1, dp(46)))
            card.configure()
            sections.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
            sectionViews.add(card)
            val item = text(title, 14f, true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                background = ripple()
                isClickable = true
                isFocusable = true
                setPadding(dp(3), dp(7), dp(3), dp(7))
                setOnClickListener { pane.smoothScrollTo(0, card.top) }
                contentDescription = "Mục cài đặt $title"
            }
            nav.addView(item, LinearLayout.LayoutParams(-1, dp(66)))
        }
        fun row(parent: LinearLayout, title: String, value: String, action: () -> Unit) {
            val line = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(10), dp(6))
                background = ripple()
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            line.addView(text(title, 18f), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(text(value, 15f, true).apply { setTextColor(0xff37dee7.toInt()); gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(105), -2))
            parent.addView(line, LinearLayout.LayoutParams(-1, dp(58)))
            parent.addView(View(context).apply { setBackgroundColor(0x22ffffff) }, LinearLayout.LayoutParams(-1, dp(1)))
        }

        section("Tài khoản", "account") {
            row(this, "Tài khoản TN", "QUẢN LÝ") { linkZalo() }
            row(this, "Liên kết Zalo", "LIÊN KẾT") { linkZalo() }
            row(this, "Đăng xuất tài khoản", "ĐĂNG XUẤT", logout)
        }
        section("Tiện ích", "utilities") {
            row(this, "Tiện ích nổi TN", "CẤU HÌNH", floating)
            row(this, "Điều khiển trình phát nhạc", "CẤU HÌNH", media)
            row(this, "Quyền truy cập Android", "HƯỚNG DẪN", setup)
        }
        section("Dẫn đường", "navigation") {
            row(this, "Ứng dụng bản đồ", "ANDROID") {
                android.widget.Toast.makeText(context, "Ứng dụng bản đồ được mở theo lựa chọn trên thiết bị.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        section("Giải trí", "entertainment") {
            row(this, "Điều khiển nhạc", "MỞ CÀI ĐẶT", media)
            row(this, "Ứng dụng giải trí", "TRÊN THIẾT BỊ") {
                android.widget.Toast.makeText(context, "TN chỉ điều khiển ứng dụng tương thích đã cài trên thiết bị.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        section("Điều khoản", "terms") {
            row(this, "Quyền riêng tư và dữ liệu", "XEM", privacy)
            row(this, "Thông tin ứng dụng TN", "XEM", privacy)
        }
        section("Cập nhật", "updates") {
            row(this, "Kiểm tra phiên bản mới", "KIỂM TRA", update)
        }
        section("Cài đặt khác", "other") {
            row(this, "Hướng dẫn cài đặt", "MỞ", setup)
            row(this, "Phiên bản", "${vn.tn.assistant.BuildConfig.VERSION_NAME}") {
                android.widget.Toast.makeText(context, vn.tn.assistant.BuildConfig.VERSION_NAME, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun text(value: String, size: Float, strong: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        typeface = if (strong) semibold else regular
        setTextColor(Color.WHITE)
        includeFontPadding = false
    }

    private fun ripple() = RippleDrawable(ColorStateList.valueOf(0x33ffffff),
        GradientDrawable().apply { setColor(0x0fffffff); cornerRadius = dp(4).toFloat() }, null)
}

private class TnPanelFlow(context: Context, private val horizontalGap: Int, private val verticalGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            measureChild(child, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + horizontalGap + child.measuredWidth > available) {
                x = 0
                y += rowHeight + verticalGap
                rowHeight = 0
            }
            if (x > 0) x += horizontalGap
            x += child.measuredWidth
            rowHeight = max(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(available, y + rowHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (x > 0 && x + horizontalGap + child.measuredWidth > width) {
                x = 0
                y += rowHeight + verticalGap
                rowHeight = 0
            }
            if (x > 0) x += horizontalGap
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth
            rowHeight = max(rowHeight, child.measuredHeight)
        }
    }
}
