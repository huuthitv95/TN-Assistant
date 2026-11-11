package vn.tn.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import vn.tn.assistant.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Values transcribed from the existing source XML qualifiers, not a new visual scale. */
internal class TnZaloReferenceMetrics(private val context: Context) {
    val scale: Float
        get() {
            val configuration = context.resources.configuration
            val smallest = configuration.smallestScreenWidthDp
            val width = configuration.screenWidthDp
            return when {
                smallest >= 600 -> .85f
                smallest >= 400 && width >= 1200 -> 1f
                smallest >= 400 && width >= 682 -> .55f
                smallest >= 400 -> .6f
                smallest >= 300 && width >= 960 -> .6f
                smallest >= 300 -> .45f
                smallest >= 200 -> .3f
                else -> 1f
            }
        }
    fun px(value: Float) = (value * scale * context.resources.displayMetrics.density).roundToInt()
    fun sp(value: Float) = value * scale
}

internal fun tnZaloFont(context: Context, semibold: Boolean = false): Typeface =
    ResourcesCompat.getFont(context, if (semibold) R.font.tn_barlow_semibold else R.font.tn_barlow_regular)
        ?: Typeface.create("sans-serif-condensed", if (semibold) Typeface.BOLD else Typeface.NORMAL)

internal fun tnZaloRipple(radius: Float, fill: Int = 0x0FFFFFFF): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius
    }, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius })

/** Source close icon uses a 26x26 viewport and rounded 3-unit diagonal strokes. */
internal class TnZaloCloseButton(context: Context, onClose: () -> Unit) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 222, 231)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val metrics = TnZaloReferenceMetrics(context)
    init {
        isFocusable = true
        isClickable = true
        contentDescription = "Đóng đăng nhập Zalo"
        background = tnZaloRipple(metrics.px(8f).toFloat())
        setOnClickListener { onClose() }
    }
    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height) - metrics.px(32f)
        val save = canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(side / 26f, side / 26f)
        canvas.drawLine(23.589f, 2.412f, 2.412f, 23.588f, ink)
        canvas.drawLine(23.589f, 23.588f, 2.412f, 2.412f, ink)
        canvas.restoreToCount(save)
    }
}

/** SDK sweep drawable with the Holo ProgressBar's 3500ms repeat duration. */
internal class TnZaloSweepRing(context: Context, color: Int = Color.rgb(1, 142, 229)) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rotation = Matrix()
    private val endColor = color
    private var animator: ValueAnimator? = null
    private var angle = 0f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ink.shader = SweepGradient(w / 2f, h / 2f, intArrayOf(Color.TRANSPARENT, endColor), null)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        ink.strokeWidth = side / 8f
        rotation.setRotate(angle, width / 2f, height / 2f)
        ink.shader?.setLocalMatrix(rotation)
        canvas.drawCircle(width / 2f, height / 2f, side / 3f + side / 16f, ink)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // View's constructor can dispatch this before subclass fields initialize.
        if (isAttachedToWindow) updateAnimation()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) updateAnimation()
    }
    private fun updateAnimation() {
        val enabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        if (!isShown || windowVisibility != VISIBLE || !enabled) {
            animator?.cancel(); animator = null
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }
}

/** The original onboarding's dark slide-in recovery card (j2/i.java), shown on failure. */
internal class TnZaloRecoveryHint(context: Context, onAlternative: () -> Unit) : LinearLayout(context) {
    private val metrics = TnZaloReferenceMetrics(context)
    private var dismissing = false
    private val clickGate = ClickGate(500L)
    init {
        orientation = VERTICAL
        gravity = Gravity.END
        background = GradientDrawable().apply {
            setColor(Color.rgb(34, 49, 63))
            cornerRadius = metrics.px(8f).toFloat()
        }
        elevation = metrics.px(12f).toFloat()
        setPadding(metrics.px(32f), metrics.px(16f), metrics.px(32f), metrics.px(16f))
        val recover = {
            if (visibility == VISIBLE && !dismissing && clickGate.accept(android.os.SystemClock.elapsedRealtime())) onAlternative()
        }
        isClickable = true; isFocusable = true
        setOnClickListener { recover() }
        addView(TextView(context).apply {
            text = "Bạn gặp lỗi đăng nhập?"
            typeface = tnZaloFont(context)
            textSize = metrics.sp(28f)
            setTextColor(Color.rgb(233, 243, 255))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(Button(context).apply {
            text = "THỬ CÁCH KHÁC"
            textSize = metrics.sp(24f)
            typeface = tnZaloFont(context)
            setTextColor(Color.CYAN)
            background = tnZaloRipple(metrics.px(8f).toFloat(), Color.TRANSPARENT)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minWidth = 0; minimumWidth = 0
            minHeight = dp(48); minimumHeight = dp(48)
            contentDescription = "Gặp lỗi đăng nhập Zalo, thử cách khác"
            setOnClickListener { recover() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = metrics.px(8f) })
        visibility = GONE
    }
    fun reveal() {
        if (visibility == VISIBLE && !dismissing) return
        animate().cancel()
        dismissing = false
        visibility = VISIBLE
        if (!isAttachedToWindow || !animationsEnabled()) { translationX = 0f; return }
        androidx.core.view.OneShotPreDrawListener.add(this) {
            if (!isAttachedToWindow || visibility != VISIBLE || dismissing) return@add
            translationX = width + metrics.px(32f).toFloat()
            animate().translationX(0f).setStartDelay(0L).setDuration(300L)
                .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in)).start()
        }
    }
    fun dismiss() {
        if (visibility != VISIBLE || dismissing) return
        if (!isAttachedToWindow || !isLaidOut || !animationsEnabled()) { hideImmediately(); return }
        dismissing = true
        animate().cancel()
        animate().translationX(width + metrics.px(32f).toFloat()).setStartDelay(400L).setDuration(300L)
            .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in))
            .withEndAction { hideImmediately() }.start()
    }
    private fun hideImmediately() { animate().cancel(); dismissing = false; visibility = GONE; translationX = 0f }
    private fun animationsEnabled() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    override fun onDetachedFromWindow() { hideImmediately(); super.onDetachedFromWindow() }
}
