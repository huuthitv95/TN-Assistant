package vn.tn.assistant.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import vn.tn.assistant.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Single-microphone floating control with the original first-use briefing hint. */
internal class TnFloatingClusterView(context: Context) : FrameLayout(context) {
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x660b0b0b }
    private val foreground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backing = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backingBounds = RectF()
    private val eraseBacking = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val microphone = context.getDrawable(R.drawable.tn_floating_microphone)!!
    private val pressedMicrophone = context.getDrawable(R.drawable.tn_floating_microphone_pressed) as AnimatedVectorDrawable
    private val microBounds = Rect()
    private val microHitBounds = Rect()
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintRect = android.graphics.RectF()
    private val hintRotation = android.graphics.Matrix()
    private var hintSweep: android.graphics.SweepGradient? = null
    private var hintStroke: android.graphics.LinearGradient? = null
    private val hintClock = object : Runnable {
        override fun run() {
            if (suggestionVisible && isAttachedToWindow) { invalidate(); postDelayed(this, 35) }
        }
    }
    private val foregroundSpring = TnFloatingSpring()
    private var foregroundAt = 0L
    private var pressAnimationActive = false
    private val fadeForeground = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (foregroundSpring.advance((now - foregroundAt) / 1000.0)) postOnAnimation(this)
            foregroundAt = now
            invalidate()
        }
    }

    val accountBadge = TnFloatingPlanBadgeView(context)
    var accountPlan: TnFloatingAccountPlan? = null
        set(value) { field = value; accountBadge.plan = value; accountBadge.visibility = if(value == null) GONE else VISIBLE; requestLayout(); invalidate() }
    var unit = 108
        set(value) { field = value.coerceAtLeast(1); requestLayout(); invalidate() }
    var suggestionVisible = true
        set(value) {
            if (field == value) return
            field = value
            suggestionHint.visibility = if (value) VISIBLE else GONE
            removeCallbacks(hintClock)
            if (value && isAttachedToWindow) post(hintClock)
            requestLayout()
            invalidate()
        }
    var microEnabled = true
        set(value) { field = value; microButton.isEnabled = value; invalidate() }
    var routeTouch: ((MotionEvent) -> Boolean)? = null
    var dismiss: (() -> Unit)? = null

    val microButton = Button(context).apply {
        background = null
        minWidth = 0
        minHeight = 0
        setPadding(0, 0, 0, 0)
        contentDescription = "Mở trợ lý giọng nói TN"
    }

    val suggestionHint = TextView(context).apply {
        text = "Nghe điểm báo AI"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.createFromAsset(context.assets, "fonts/barlowmedium.ttf")
        gravity = android.view.Gravity.CENTER
        isSingleLine = true
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.tn_floating_news, 0, 0, 0)
        compoundDrawablePadding = dp(6f)
        setPadding(dp(12f), 0, dp(12f), 0)
        contentDescription = "Gợi ý: Nghe điểm báo AI"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = false
        isFocusable = false
        elevation = 0f
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        addView(suggestionHint)
        addView(microButton)
        addView(accountBadge)
        accountBadge.visibility = GONE
        microphone.callback = this
        pressedMicrophone.callback = this
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            pressedMicrophone.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(drawable: Drawable?) { pressAnimationActive = false; invalidate() }
            })
        }
    }

    override fun verifyDrawable(who: Drawable) = who === microphone || who === pressedMicrophone || super.verifyDrawable(who)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = routeTouch?.invoke(event) ?: super.dispatchTouchEvent(event)

    val anchorCenterX: Int get() = if (microBounds.width() > 0) microBounds.centerX()
        else if (width > 0) width / 2 else measuredWidth / 2
    val anchorCenterY: Int get() = if (microBounds.height() > 0) microBounds.centerY()
        else TnFloatingGeometry.hintAnchorOffset(unit, suggestionVisible, resources.displayMetrics.density)

    // The reference hint is a separate passive window. The combined TN view
    // must keep the microphone body's drag rectangle and scale pivot intact.
    val dragBodyWidth: Int get() = TnFloatingGeometry.dimensions(unit, false, resources.displayMetrics.density, accountPlan != null).first
    val dragBodyHeight: Int get() = TnFloatingGeometry.dimensions(unit, false, resources.displayMetrics.density).second
    val dragBodyLeft: Int get() = ((if (width > 0) width else measuredWidth) - dragBodyWidth) / 2
    val dragBodyTop: Int get() = if (suggestionVisible) TnFloatingGeometry.hintHeight(unit) else 0

    fun pressAt(x: Float, y: Float) {
        if (!microEnabled || !microHitBounds.contains(x.toInt(), y.toInt())) return
        removeCallbacks(fadeForeground)
        foregroundSpring.snap(1.0)
        if (!pressAnimationActive) {
            pressAnimationActive = true
            pressedMicrophone.bounds = microBounds
            pressedMicrophone.start()
            if (android.os.Build.VERSION.SDK_INT < 23) postDelayed({ pressAnimationActive = false; invalidate() }, 300)
        }
        invalidate()
    }

    fun releasePress() {
        removeCallbacks(fadeForeground)
        foregroundSpring.target = 0.0
        foregroundAt = SystemClock.uptimeMillis()
        postOnAnimation(fadeForeground)
    }

    fun clickAt(x: Float, y: Float) {
        if (accountPlan != null && x >= accountBadge.left && x < accountBadge.right && y >= accountBadge.top && y < accountBadge.bottom) { accountBadge.performClick(); return }
        if (microEnabled && microHitBounds.contains(x.toInt(), y.toInt())) microButton.performClick()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_DISMISS) { dismiss?.invoke(); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(hintClock) }

    override fun onDetachedFromWindow() {
        pressedMicrophone.stop()
        removeCallbacks(fadeForeground)
        removeCallbacks(hintClock)
        pressAnimationActive = false
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val (desiredWidth, desiredHeight) = TnFloatingGeometry.dimensions(unit, suggestionVisible, density, accountPlan != null)
        val width = resolveSize(desiredWidth, widthMeasureSpec).coerceAtLeast(1)
        val height = resolveSize(desiredHeight, heightMeasureSpec).coerceAtLeast(1)
        setMeasuredDimension(width, height)

        // b2.o.onLayout rounds all four edges independently. Its inset starts
        // at the truncated shadow margin; the artwork is not window-centered.
        val margin = .06349207f * unit
        val slotPadding = .05f * unit
        val slot = unit - slotPadding * 2
        val artSize = .8f * slot
        val artInset = (slot - artSize) / 2f
        val baseWidth = TnFloatingGeometry.dimensions(unit, false, density, accountPlan != null).first
        val baseLeft = (width - baseWidth) / 2
        val micStart = if (suggestionVisible) TnFloatingGeometry.hintHeight(unit) else 0
        microBounds.set(baseLeft + (margin.toInt() + artInset).roundToInt(),
            micStart + (margin + slotPadding + artInset).roundToInt(),
            baseLeft + (margin.toInt() + artInset + artSize).roundToInt(),
            micStart + (margin + slotPadding + artInset + artSize).roundToInt())
        val remainingInset = unit - artInset - artSize
        microHitBounds.set(baseLeft + margin.toInt(), micStart + (margin + slotPadding).roundToInt(),
            baseLeft + (margin.toInt() + unit - remainingInset / 2f).roundToInt(),
            micStart + (unit - slotPadding + margin).roundToInt())
        val backingWidth = (artInset * 2 + microBounds.width() + if(accountPlan != null) unit else 0).toInt()
        backingBounds.set(baseLeft + margin, micStart + margin + slotPadding,
            baseLeft + margin + backingWidth, micStart + unit - slotPadding + margin)
        shadow.maskFilter = BlurMaskFilter((margin - 1).coerceAtLeast(.1f), BlurMaskFilter.Blur.OUTER)
        microButton.measure(exact(microBounds.width()), exact(microBounds.height()))
        accountBadge.measure(exact(if(accountPlan != null) unit else 0), exact(if(accountPlan != null) (unit * 1.1f).roundToInt() else 0))

        if (suggestionVisible) {
            val hintHeight = min(TnFloatingGeometry.hintHeight(unit), height).coerceAtLeast(1)
            val inset = hintHeight * 32f / 175f
            hintRect.set(inset, inset, width - inset, hintHeight - inset)
            hintSweep = android.graphics.SweepGradient(hintRect.centerX(), hintRect.centerY(),
                intArrayOf(0x229effff, 0xff9effff.toInt(), 0x229effff), floatArrayOf(0f, .5f, 1f))
            hintStroke = android.graphics.LinearGradient(hintRect.left, hintRect.top, hintRect.right, hintRect.top,
                0xffa5f7ff.toInt(), 0xff27aafc.toInt(), android.graphics.Shader.TileMode.CLAMP)
            val icon = context.getDrawable(R.drawable.tn_floating_news)!!
            val side = (hintRect.height() * .431f).roundToInt()
            icon.setBounds(0, 0, side, side)
            suggestionHint.setCompoundDrawablesRelative(icon, null, null, null)
            suggestionHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, hintRect.height() * .28f)
            suggestionHint.setPadding((hintRect.width() * .06666667f).roundToInt(), 0, (hintRect.width() * .05f).roundToInt(), 0)
            suggestionHint.measure(exact(hintRect.width().roundToInt()), exact(hintRect.height().roundToInt()))
        } else {
            suggestionHint.measure(exact(0), exact(0))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        pivotX = dragBodyLeft + dragBodyWidth / 2f
        pivotY = dragBodyTop + dragBodyHeight / 2f
        microButton.layout(microBounds.left, microBounds.top, microBounds.right, microBounds.bottom)
        val badgeLeft = dragBodyLeft + (.95f * unit).roundToInt()
        val badgeTop = dragBodyTop + (.04f * unit).roundToInt()
        accountBadge.layout(badgeLeft, badgeTop, badgeLeft + accountBadge.measuredWidth, badgeTop + accountBadge.measuredHeight)
        if (suggestionVisible) suggestionHint.layout(hintRect.left.roundToInt(), hintRect.top.roundToInt(),
            hintRect.left.roundToInt() + suggestionHint.measuredWidth, hintRect.top.roundToInt() + suggestionHint.measuredHeight)
        else suggestionHint.layout(0, 0, 0, 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (microBounds.width() <= 0) return
        if (suggestionVisible) {
            val factor = TnFloatingGeometry.hintHeight(unit) / 175f
            val phase = ((SystemClock.uptimeMillis() % 2400) / 1200f).let { if (it <= 1f) it else 2f - it }
            val blur = (15f * (1f - kotlin.math.cos(phase * Math.PI).toFloat())) * factor
            hintPaint.style = Paint.Style.FILL
            hintPaint.color = 0xff9effff.toInt()
            hintPaint.maskFilter = BlurMaskFilter((30f * factor - 1).coerceAtLeast(.1f), BlurMaskFilter.Blur.OUTER)
            hintRotation.setRotate((SystemClock.uptimeMillis() % 2000) * .18f, hintRect.centerX(), hintRect.centerY())
            hintSweep?.setLocalMatrix(hintRotation); hintPaint.shader = hintSweep
            canvas.drawRoundRect(hintRect, 36 * factor, 36 * factor, hintPaint)
            hintPaint.shader = null
            hintPaint.maskFilter = if (blur > 1) BlurMaskFilter(blur - 1, BlurMaskFilter.Blur.OUTER) else null
            if (blur > 1) canvas.drawRoundRect(hintRect, 36 * factor, 36 * factor, hintPaint)
            hintPaint.maskFilter = null; hintPaint.color = -233433317
            canvas.drawRoundRect(hintRect, 36 * factor, 36 * factor, hintPaint)
            hintPaint.style = Paint.Style.STROKE; hintPaint.strokeWidth = 2 * factor
            hintPaint.shader = hintStroke
            canvas.drawRoundRect(hintRect, 36 * factor, 36 * factor, hintPaint)
            hintPaint.shader = null
        }
        // Source I0/E1: a gray round-rect with an outside-only shadow. Keeping
        // its real alpha also makes the user's opacity choice apply uniformly.
        val margin = .06349207f * unit
        val saved = canvas.saveLayer(null, null)
        backing.setShadowLayer(margin, 0f, .02116402f * unit, 0x3a000000)
        canvas.drawRoundRect(backingBounds, backingBounds.height(), backingBounds.height(), backing)
        backing.clearShadowLayer()
        backing.xfermode = eraseBacking
        backing.color = Color.BLACK
        canvas.drawRoundRect(backingBounds, backingBounds.height(), backingBounds.height(), backing)
        backing.xfermode = null
        canvas.restoreToCount(saved)
        backing.color = 0x99eeeeee.toInt()
        canvas.drawRoundRect(backingBounds, backingBounds.height(), backingBounds.height(), backing)
        if (pressAnimationActive) {
            pressedMicrophone.bounds = microBounds
            pressedMicrophone.draw(canvas)
        } else {
            shadow.alpha = if (microEnabled) 102 else 51
            canvas.drawCircle((microBounds.left + microBounds.right) / 2f,
                (microBounds.top + microBounds.bottom) / 2f, microBounds.width() / 2f + 1, shadow)
            microphone.alpha = if (microEnabled) 255 else 127
            microphone.bounds = microBounds
            microphone.draw(canvas)
            val alpha = (56 * foregroundSpring.value.toFloat().coerceIn(0f, 1f)).roundToInt()
            if (alpha > 0) {
                foreground.color = Color.argb(alpha, 0, 0, 0)
                canvas.drawCircle(microBounds.centerX().toFloat(), microBounds.centerY().toFloat(), microBounds.width() / 2f, foreground)
            }
        }
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}
