package vn.tn.assistant.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Source CBL layout with TN-issued QR, code and session. Source XML positions the two numbered
 * steps horizontally in landscape and vertically in portrait. Recovery actions appear on failure;
 * they no longer push the QR/code away from their source positions during a healthy attempt.
 */
class TnZaloPairingSurface(
    context: Context,
    private val qr: ImageView,
    private val code: TextView,
    private val status: TextView,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onWeb: () -> Unit,
) : FrameLayout(context) {
    private val metrics = TnZaloReferenceMetrics(context)
    private val title = text("Đăng nhập TN Assistant", 48f, true).apply {
        gravity = Gravity.CENTER
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
    private val close = TnZaloCloseButton(context, onBack)
    private val header = Header(context)
    private val firstIndex = index("1")
    private val secondIndex = index("2")
    private val firstDescription = text("Dùng Zalo quét mã QR và chọn Liên kết tài khoản", 24f)
    private val secondDescription = text("Xác nhận mã số trên màn hình và chọn Đăng nhập", 24f)
    private val progress = TnZaloSweepRing(context, Color.rgb(55, 222, 231))
    private val qrFrame = FrameLayout(context)
    private val retry = action("TẢI LẠI MÃ", onRetry)
    private val web = action("ĐĂNG NHẬP TRÊN THIẾT BỊ NÀY", onWeb)
    private val body = PairingBody(context)
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        clipToPadding = false
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        background = TnWelcomeBackground(context)
        qr.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Mã QR đăng nhập Zalo cho TN Assistant"
        }
        qrFrame.addView(qr, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        qrFrame.addView(progress, LayoutParams(dp(48), dp(48), Gravity.CENTER))
        code.apply {
            textSize = metrics.sp(56f)
            setTextColor(Color.WHITE)
            // KikiTextView.PairCode uses the real Barlow Bold face, not synthesized regular.
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, vn.tn.assistant.R.font.tn_floating_barlow_bold)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextIsSelectable(false)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        status.apply {
            textSize = metrics.sp(20f)
            setTextColor(Color.rgb(233, 243, 255))
            typeface = tnZaloFont(context)
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        listOf(firstIndex, secondIndex, firstDescription, secondDescription, qrFrame, code, status, retry, web).forEach(body::addView)
        header.addView(title); header.addView(close)
        addView(scroll); addView(header)
        val observer = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshState()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        code.addTextChangedListener(observer)
        status.addTextChangedListener(observer)
        refreshState()
    }

    private fun refreshState() {
        val ready = qr.drawable != null && code.text.toString().replace(" ", "").matches(Regex("[0-9]{6}"))
        val message = status.text.toString()
        val failed = listOf("không", "chưa", "hết hạn", "từ chối", "thử lại").any { message.contains(it, ignoreCase = true) }
        qr.visibility = if (ready) VISIBLE else INVISIBLE
        progress.visibility = if (ready || failed) GONE else VISIBLE
        code.visibility = if (ready) VISIBLE else INVISIBLE
        // A normal pending state is already communicated by the two source instructions.
        status.visibility = if (ready && !failed) GONE else VISIBLE
        retry.visibility = if (failed) VISIBLE else GONE
        web.visibility = if (failed) VISIBLE else GONE
        body.requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val h = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        header.measure(exact(w), unspecified())
        scroll.measure(exact(w), exact((h - header.measuredHeight).coerceAtLeast(1)))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        header.layout(0, 0, width, header.measuredHeight)
        scroll.layout(0, header.bottom, width, height)
    }

    private inner class Header(context: Context) : ViewGroup(context) {
        private var titleY = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val closeSide = maxOf(dp(48), metrics.px(72f))
            close.measure(exact(closeSide), exact(closeSide))
            title.measure(exact((w - metrics.px(120f)).coerceAtLeast(1)), unspecified())
            titleY = metrics.px(60f)
            // On narrow windows the full title can wrap below the close target rather than overlap.
            if (w < dp(480)) titleY = maxOf(titleY, dp(56))
            setMeasuredDimension(w, maxOf(titleY + title.measuredHeight + dp(8), closeSide + dp(8)))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val tx = (width - title.measuredWidth) / 2
            title.layout(tx, titleY, tx + title.measuredWidth, titleY + title.measuredHeight)
            val x = metrics.px(60f)
            val y = if (width < dp(480)) dp(8) else titleY + (title.measuredHeight - close.measuredHeight) / 2
            close.layout(x, y, x + close.measuredWidth, y + close.measuredHeight)
        }
        override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    private inner class PairingBody(context: Context) : ViewGroup(context) {
        private var wide = false
        private var start = 0
        private var contentX = 0
        private var secondStart = 0
        private var firstTop = 0
        private var secondTop = 0
        private var qrTop = 0
        private var codeTop = 0
        private var footerTop = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            wide = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT
            val badgePadding = metrics.px(8f)
            firstIndex.setPadding(badgePadding, badgePadding, badgePadding, badgePadding)
            secondIndex.setPadding(badgePadding, badgePadding, badgePadding, badgePadding)
            firstIndex.measure(unspecified(), unspecified())
            val badge = maxOf(firstIndex.measuredWidth, firstIndex.measuredHeight)
            firstIndex.measure(exact(badge), exact(badge)); secondIndex.measure(exact(badge), exact(badge))
            val insideGap = metrics.px(if (wide) 36f else 24f)
            val middleGap = metrics.px(80f)
            val leftMargin = metrics.px(60f)
            val rightMargin = metrics.px(if (wide) 90f else 60f)
            val descriptionWidth = if (wide) minOf(metrics.px(400f),
                ((w - leftMargin - rightMargin - middleGap) / 2 - badge - insideGap).coerceAtLeast(1))
                else (w - leftMargin - rightMargin - badge - insideGap).coerceAtLeast(1)
            firstDescription.measure(exact(descriptionWidth), unspecified())
            secondDescription.measure(exact(descriptionWidth), unspecified())
            val groupWidth = badge + insideGap + descriptionWidth
            val spare = (w - leftMargin - rightMargin - if (wide) groupWidth * 2 + middleGap else groupWidth).coerceAtLeast(0)
            start = leftMargin + spare / 2
            contentX = start + badge + insideGap
            secondStart = if (wide) start + groupWidth + middleGap else start
            firstTop = metrics.px(if (wide) 40f else 80f)
            val side = minOf(metrics.px(240f), descriptionWidth)
            qrFrame.measure(exact(side), exact(side))
            qrTop = firstTop + firstDescription.measuredHeight + metrics.px(if (wide) 32f else 36f)
            secondTop = if (wide) firstTop else qrTop + side + metrics.px(60f)
            codeTop = secondTop + secondDescription.measuredHeight + metrics.px(if (wide) 32f else 24f)
            code.measure(exact(descriptionWidth), unspecified())
            val contentBottom = maxOf(qrTop + side, codeTop + code.measuredHeight)
            footerTop = contentBottom + metrics.px(32f)
            val footerWidth = (w - dp(40)).coerceAtLeast(1)
            var h = footerTop
            listOf(status, retry, web).forEach { child ->
                if (child.visibility != GONE) {
                    child.measure(exact(minOf(footerWidth, dp(480))), unspecified())
                    h += child.measuredHeight + dp(4)
                }
            }
            setMeasuredDimension(w, h + metrics.px(48f))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            fun place(view: View, x: Int, y: Int) = view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
            val badgeOffset = if (wide) metrics.px(8f) else (firstDescription.measuredHeight - firstIndex.measuredHeight) / 2
            place(firstIndex, start, firstTop + badgeOffset)
            place(firstDescription, contentX, firstTop)
            place(qrFrame, contentX, qrTop)
            place(secondIndex, secondStart, secondTop + if (wide) metrics.px(8f) else (secondDescription.measuredHeight - secondIndex.measuredHeight) / 2)
            val secondX = secondStart + firstIndex.measuredWidth + metrics.px(if (wide) 36f else 24f)
            place(secondDescription, secondX, secondTop)
            place(code, secondX, codeTop)
            var y = footerTop
            listOf(status, retry, web).forEach { child ->
                if (child.visibility != GONE) {
                    place(child, (width - child.measuredWidth) / 2, y)
                    y += child.measuredHeight + dp(4)
                }
            }
        }
        override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    private fun index(value: String) = text(value, 24f).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun text(label: String, sp: Float, semibold: Boolean = false) = TextView(context).apply {
        text = label; textSize = metrics.sp(sp)
        setTextColor(Color.WHITE); typeface = tnZaloFont(context, semibold)
    }
    private fun action(label: String, callback: () -> Unit) = Button(context).apply {
        text = label; textSize = metrics.sp(24f); typeface = tnZaloFont(context)
        setTextColor(Color.rgb(55, 222, 231))
        background = tnZaloRipple(metrics.px(8f).toFloat(), Color.TRANSPARENT)
        minimumWidth = 0; minWidth = 0
        minimumHeight = dp(48); minHeight = dp(48)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { callback() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
}
