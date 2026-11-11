package vn.tn.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import vn.tn.assistant.R
import kotlin.math.max
import kotlin.math.roundToInt

/** Native TN guide laid out from the owner-authorized original fragment and runtime measurements. */
class TnAssistantGuideSurface(context: Context, private val close: () -> Unit, private val command: (String) -> Unit) : FrameLayout(context) {
    private val scale = HomeScale(context)
    private fun sd(value: Int) = scale.px(value)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private var selected: TextView? = null
    private var lastCommandAt = Long.MIN_VALUE
    private var closing = false
    private var entered = false
    private var alphaMotion: ValueAnimator? = null
    private val fadeExit = Runnable {
        if (closing && isAttachedToWindow) {
            alphaMotion = ValueAnimator.ofFloat(pane.alpha, 0f).apply {
                duration = 150
                addUpdateListener { pane.alpha = it.animatedValue as Float }
                start()
            }
        }
    }
    private val easing = PathInterpolator(Path().apply {
        moveTo(0f, 0f); cubicTo(.05f, 0f, .133333f, .06f, .166666f, .4f)
        cubicTo(.208333f, .82f, .25f, 1f, 1f, 1f)
    })
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, sd(16), 0, sd(160))
    }
    val scroll = ScrollView(context).apply {
        tag = "tnGuideScroll"
        isVerticalScrollBarEnabled = true; isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(sd(50)); clipChildren = false
        if (Build.VERSION.SDK_INT >= 29) {
            verticalScrollbarThumbDrawable = context.getDrawable(R.drawable.tn_settings_scroll_thumb)
            verticalScrollbarTrackDrawable = context.getDrawable(R.drawable.tn_settings_scroll_track)
        }
        addView(body, LayoutParams(-1, -2))
    }
    val closeButton = ImageButton(context).apply {
        tag = "tnGuideClose"; contentDescription = "Đóng hướng dẫn"
        setImageResource(R.drawable.tn_settings_ic_close)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(sd(16), sd(16), sd(16), sd(16))
        setBackgroundResource(R.drawable.tn_settings_background_btn_ripple_rounded_8dp)
        setOnClickListener { closeWithAnimation() }
    }
    private val pane = object : FrameLayout(context) {
        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
            closeButton.measure(exact(sd(50)), exact(sd(50)))
            scroll.measure(exact((measuredWidth - sd(130)).coerceAtLeast(1)), exact(measuredHeight))
        }
        override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
            closeButton.layout(sd(40), sd(16), sd(90), sd(66))
            scroll.layout(sd(130), 0, width, height)
        }
    }
    init {
        tag = "tnGuideSurface"
        setBackgroundColor(0xe5021f4e.toInt())
        pane.addView(closeButton); pane.addView(scroll); addView(pane, LayoutParams(-1, -1))
        val groups = JSONArray(context.assets.open("tn-guide-commands.json").bufferedReader().use { it.readText() })
        for (index in 0 until groups.length()) {
            val group = groups.getJSONObject(index)
            val heading = LinearLayout(context).apply {
                tag = "tnGuideHeading"; gravity = Gravity.CENTER_VERTICAL; minimumHeight = sd(50)
            }
            val icon = ImageView(context).apply {
                val resource = when (group.getString("icon")) {
                    "tn_ic_guide_navigate" -> R.drawable.tn_ic_guide_navigate
                    "tn_ic_guide_music" -> R.drawable.tn_ic_guide_music
                    "tn_ic_radio" -> R.drawable.tn_ic_radio
                    "tn_ic_guide_video" -> R.drawable.tn_ic_guide_video
                    "tn_ic_guide_tv" -> R.drawable.tn_ic_guide_tv
                    "tn_ic_guide_utilities" -> R.drawable.tn_ic_guide_utilities
                    "tn_ic_guide_news" -> R.drawable.tn_ic_guide_news
                    else -> R.drawable.tn_ic_guide_search
                }
                setImageResource(resource); imageTintList = ColorStateList.valueOf(0xff37dee7.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            heading.addView(icon, LinearLayout.LayoutParams(sd(56), sd(56)))
            heading.addView(TextView(context).apply {
                text = group.getString("title")
                setTextSize(TypedValue.COMPLEX_UNIT_PX, (32f * scale.factor * resources.displayMetrics.scaledDensity).roundToInt().toFloat())
                typeface = scale.semibold; setTextColor(Color.WHITE); includeFontPadding = false
                accessibilityHeadingCompat()
            }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = sd(8) })
            body.addView(heading, LinearLayout.LayoutParams(-1, -2))
            val flow = GuideFlow(context, dp(16), dp(12))
            val examples = group.getJSONArray("examples")
            for (position in 0 until examples.length()) {
                val value = examples.getString(position)
                val chip = TextView(context).apply {
                    tag = "tnGuideCommand"; text = value
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (20f * scale.factor * resources.displayMetrics.scaledDensity).roundToInt().toFloat())
                    typeface = scale.regular; gravity = Gravity.CENTER
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setPadding(sd(24), sd(10), sd(24), sd(12))
                    setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                        intArrayOf(0xff37dee7.toInt(), 0xfffcfcfc.toInt())))
                    background = chipBackground()
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        val now = SystemClock.elapsedRealtime()
                        if (lastCommandAt != Long.MIN_VALUE && now - lastCommandAt < 1000) return@setOnClickListener
                        lastCommandAt = now
                        selected?.isSelected = false; selected = this; isSelected = true
                        command(value)
                    }
                }
                flow.addView(chip, ViewGroup.LayoutParams(-2, -2))
            }
            body.addView(flow, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = sd(10); bottomMargin = sd(40); marginEnd = sd(16)
            })
        }
    }
    private fun TextView.accessibilityHeadingCompat() { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
    private fun chipBackground(): RippleDrawable {
        fun stroke(color: Int) = GradientDrawable().apply {
            setColor(Color.TRANSPARENT); cornerRadius = sd(100).toFloat(); setStroke(sd(2).coerceAtLeast(1), color)
        }
        val state = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), stroke(0xff37dee7.toInt()))
            addState(intArrayOf(), stroke(0xffb0c2e1.toInt()))
        }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = sd(100).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(0x33ffffff), state, mask)
    }
    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)
    private fun animationsEnabled() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!entered) {
            entered = true
            post {
                if (isAttachedToWindow && !closing && animationsEnabled()) {
                    pane.translationY = height * .75f; pane.alpha = 0f
                    pane.animate().translationY(0f).setDuration(400).setInterpolator(easing).start()
                    // Use a separate animator: source entry alpha finishes before translation.
                    alphaMotion = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 150; interpolator = easing
                        addUpdateListener { if (isAttachedToWindow && !closing) pane.alpha = it.animatedValue as Float }
                        start()
                    }
                }
            }
        }
    }
    fun closeWithAnimation() {
        if (closing) return
        closing = true; closeButton.isEnabled = false
        alphaMotion?.cancel()
        pane.animate().cancel()
        if (!animationsEnabled()) { close(); return }
        pane.animate().translationY(height * .75f).setDuration(400).setInterpolator(easing)
            .withEndAction { close() }.start()
        pane.postDelayed(fadeExit, 250)
    }
    override fun onDetachedFromWindow() {
        pane.removeCallbacks(fadeExit); pane.animate().cancel(); alphaMotion?.cancel()
        super.onDetachedFromWindow()
    }
}

private class GuideFlow(context: Context, private val horizontalGap: Int, private val verticalGap: Int) : ViewGroup(context) {
    override fun onMeasure(w: Int, h: Int) {
        val available = MeasureSpec.getSize(w)
        var x = 0; var y = 0; var row = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + horizontalGap + child.measuredWidth > available) { x = 0; y += row + verticalGap; row = 0 }
            if (x > 0) x += horizontalGap
            x += child.measuredWidth; row = max(row, child.measuredHeight)
        }
        setMeasuredDimension(available, y + row)
    }
    override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0; var y = 0; var row = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (x > 0 && x + horizontalGap + child.measuredWidth > width) { x = 0; y += row + verticalGap; row = 0 }
            if (x > 0) x += horizontalGap
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth; row = max(row, child.measuredHeight)
        }
    }
}
