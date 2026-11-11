package vn.tn.assistant.ui

import android.content.Context
import android.content.res.ColorStateList
import android.animation.ValueAnimator
import android.os.Build
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import vn.tn.assistant.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class TnHomeSuggestion(val text: String, val icon: Int, val command: String)

/** TN home surface using the owner-authorized original home artwork and layout proportions. */
class TnAssistantHomeSurface(context: Context) : FrameLayout(context) {
    private val scale = HomeScale(context)
    private val gradient = View(context).apply { setBackgroundResource(R.drawable.tn_home_background_gradient) }
    private val artwork = View(context).apply { setBackgroundResource(R.drawable.tn_home_background) }
    private val examples = HomeExampleFlow(context)
    private var selectedSuggestion: Pair<String, View>? = null
    private var suggestionTransition = false
    private var transitionRevision = 0
    private var suggestionBlur: ValueAnimator? = null
    private var pendingSuggestionDraw: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var guideMode = false
    val status = TextView(context).apply {
        id = View.generateViewId()
        textSize = 20f * scale.factor
        typeface = scale.semibold
        gravity = Gravity.CENTER
        setTextColor(0xffe9f3ff.toInt())
        includeFontPadding = false
        visibility = GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val guide: View = HomeNavButton(context, "HƯỚNG DẪN", R.drawable.tn_home_guide)
    private val microphoneView = HomeMicrophoneView(context)
    val microphone: View get() = microphoneView
    val settings: View = HomeNavButton(context, "CÀI ĐẶT", R.drawable.tn_home_settings)
    val command = EditText(context).apply {
        hint = "Nhập lệnh cho TN"
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(0xffb2ddeb.toInt())
        typeface = scale.regular
        setPadding(scale.px(14), 0, scale.px(10), 0)
        background = GradientDrawable().apply {
            setColor(0x332b557e)
            cornerRadius = scale.px(8).toFloat()
            setStroke(scale.px(1), 0x6678c9da)
        }
    }
    val send = Button(context).apply {
        text = "GỬI"
        textSize = 16f
        typeface = scale.semibold
        isAllCaps = false
        setTextColor(0xff40eefe.toInt())
        background = RippleDrawable(ColorStateList.valueOf(0x3330d4e2), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(scale.px(1), 0x5578c9da)
            cornerRadius = scale.px(8).toFloat()
        }, null)
        contentDescription = "Gửi lệnh văn bản"
        minimumHeight = scale.px(48)
    }
    private val inputRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = GONE
        addView(command, LinearLayout.LayoutParams(0, scale.px(52), 1f).apply { marginEnd = scale.px(8) })
        addView(send, LinearLayout.LayoutParams(scale.px(90), scale.px(52)))
    }

    init {
        // The Activity window is translucent. Keep the home scene opaque so
        // launcher icons behind TN do not bleed through the upper half of the UI.
        setBackgroundColor(0xff000611.toInt())
        clipChildren = false
        clipToPadding = false
        addView(gradient)
        addView(artwork)
        addView(guide)
        addView(microphoneView)
        addView(settings)
        addView(examples)
        addView(status)
        addView(inputRow)
    }

    fun setSuggestions(items: List<TnHomeSuggestion>, onSelect: (String) -> Unit) {
        resetSuggestionTransition()
        selectedSuggestion = null
        examples.items.removeAllViews()
        items.take(3).forEach { item ->
            val icon = context.getDrawable(item.icon)
            icon?.setBounds(0, 0, scale.px(24), scale.px(24))
            val chip = HomeSuggestionChip(context).apply {
                text = item.text
                textSize = 20f * scale.factor
                typeface = scale.regular
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                minHeight = scale.px(64)
                isSingleLine = true
                setPadding(scale.px(24), 0, scale.px(24), 0)
                setCompoundDrawables(icon, null, null, null)
                compoundDrawablePadding = scale.px(10)
                contentDescription = "Gợi ý lệnh: ${item.text}"
                setOnClickListener {
                    selectedSuggestion = item.command to this
                    onSelect(item.command)
                }
            }
            examples.items.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, scale.px(64)).apply {
                leftMargin = scale.px(6)
                rightMargin = scale.px(6)
            })
        }
        requestLayout()
    }

    fun setListening(active: Boolean) = microphoneView.setListening(active)

    fun toggleTextEntry() {
        inputRow.visibility = if (inputRow.visibility == VISIBLE) GONE else VISIBLE
        requestLayout()
    }

    fun showStatus(value: String) {
        if (guideMode) { status.text = value; status.visibility = GONE; return }
        val selected = selectedSuggestion?.takeIf { it.first == value && examples.visibility == VISIBLE }
        selectedSuggestion = null
        resetSuggestionTransition()
        status.text = value
        status.visibility = if (value.isBlank()) GONE else VISIBLE
        suggestionTransition = selected != null && isAttachedToWindow &&
            (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())
        examples.visibility = if (status.visibility == VISIBLE && !suggestionTransition) INVISIBLE else VISIBLE
        if (suggestionTransition) {
            status.alpha = .3f
            val revision = transitionRevision
            pendingSuggestionDraw = object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    status.viewTreeObserver.removeOnPreDrawListener(this)
                    pendingSuggestionDraw = null
                    if (revision == transitionRevision && suggestionTransition) animateSuggestion(selected!!.second, revision)
                    return true
                }
            }
            status.viewTreeObserver.addOnPreDrawListener(pendingSuggestionDraw)
        }
        requestLayout()
    }

    private fun animateSuggestion(chip: View, revision: Int) {
        val from = IntArray(2); val to = IntArray(2)
        chip.getLocationOnScreen(from); status.getLocationOnScreen(to)
        status.translationX = from[0] - to[0] + (chip.width - status.width) / 2f
        status.translationY = from[1] - to[1] + (chip.height - status.height) / 2f
        val ratio = if (status.height == 0) 1f else chip.height.toFloat() / status.height
        status.scaleX = ratio; status.scaleY = ratio
        val easing = PathInterpolator(.4f, 0f, .2f, 1f)
        val fading = examples.animate().alpha(0f)
        if (Build.VERSION.SDK_INT >= 31) {
            fading.scaleX(.95f).scaleY(.3f).setInterpolator(easing).setDuration(220L)
            suggestionBlur = ValueAnimator.ofFloat(0f, scale.px(16).toFloat()).apply {
                duration = 220L; interpolator = easing
                addUpdateListener {
                    val radius = (it.animatedValue as Float).coerceAtLeast(.01f)
                    examples.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
                }
                start()
            }
        } else fading.setDuration(150L)
        fading.withEndAction {
            if (revision == transitionRevision) { suggestionTransition = false; examples.visibility = INVISIBLE }
        }.start()
        status.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setInterpolator(easing).setDuration(325L).start()
    }

    private fun resetSuggestionTransition() {
        transitionRevision++
        suggestionTransition = false
        pendingSuggestionDraw?.let { if (status.viewTreeObserver.isAlive) status.viewTreeObserver.removeOnPreDrawListener(it) }
        pendingSuggestionDraw = null
        status.animate().cancel(); examples.animate().cancel()
        suggestionBlur?.cancel(); suggestionBlur = null
        status.alpha = 1f; status.translationX = 0f; status.translationY = 0f; status.scaleX = 1f; status.scaleY = 1f
        examples.alpha = 1f; examples.scaleX = 1f; examples.scaleY = 1f
        if (Build.VERSION.SDK_INT >= 31) examples.setRenderEffect(null)
    }

    override fun onDetachedFromWindow() {
        resetSuggestionTransition()
        selectedSuggestion = null
        super.onDetachedFromWindow()
    }

    fun setGuideMode(active: Boolean) {
        guideMode = active
        setBackgroundColor(if (active) Color.TRANSPARENT else 0xff000611.toInt())
        isClickable = !active
        gradient.translationY = if (active) scale.px(50).toFloat() else 0f
        if (active) {
            resetSuggestionTransition()
            selectedSuggestion = null
            status.visibility = GONE
            examples.visibility = INVISIBLE
            inputRow.visibility = GONE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    private fun place(view: View, x: Int, y: Int, width: Int, height: Int) {
        view.measure(MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height.coerceAtLeast(0), MeasureSpec.EXACTLY))
        view.layout(x, y, x + width, y + height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val footer = scale.px(108).coerceAtMost(height / 3)
        val navWidth = scale.px(130)
        val centerY = height - scale.px(32) - footer
        place(gradient, 0, height - scale.px(290), width, scale.px(290))
        place(artwork, 0, height - scale.px(150), width, scale.px(150))
        place(guide, (width * .3f).toInt() - navWidth, centerY - scale.px(2), navWidth, footer + scale.px(4))
        place(settings, (width * .7f).toInt(), centerY - scale.px(2), navWidth, footer + scale.px(4))
        place(microphoneView, (width * .3f).toInt(), centerY, (width * .4f).toInt(), footer)
        val accessoryTop = centerY - scale.px(72)
        examples.visibility = if (guideMode || status.visibility == VISIBLE && !suggestionTransition) INVISIBLE else VISIBLE
        place(examples, 0, accessoryTop, width, scale.px(64))
        val statusWidth = width * 3 / 4
        status.measure(MeasureSpec.makeMeasureSpec(statusWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((centerY - scale.px(8)).coerceAtLeast(1), MeasureSpec.AT_MOST))
        val statusBottom = centerY - scale.px(8)
        status.layout(width / 8, (statusBottom - status.measuredHeight).coerceAtLeast(0), width / 8 + statusWidth, statusBottom)
        if (inputRow.visibility == VISIBLE) {
            place(inputRow, width / 8, centerY - scale.px(140), width * 3 / 4, scale.px(52))
        }
    }
}

internal class HomeScale(context: Context) {
    private val configuration = context.resources.configuration
    val factor: Float = when {
        configuration.smallestScreenWidthDp >= 600 -> .85f
        configuration.smallestScreenWidthDp >= 400 && configuration.screenWidthDp >= 1200 -> 1f
        configuration.smallestScreenWidthDp >= 400 && configuration.screenWidthDp >= 682 -> .55f
        configuration.smallestScreenWidthDp >= 400 -> .6f
        configuration.smallestScreenWidthDp >= 300 && configuration.screenWidthDp >= 960 -> .6f
        configuration.smallestScreenWidthDp >= 300 -> .45f
        configuration.smallestScreenWidthDp >= 200 -> .3f
        else -> 1f
    }
    private val density = context.resources.displayMetrics.density
    val regular = Typeface.createFromAsset(context.assets, "fonts/barlowregular.ttf")
    val semibold = Typeface.createFromAsset(context.assets, "fonts/barlowsemibold.ttf")
    fun px(value: Int) = (value * factor * density).roundToInt()
}

private class HomeNavButton(context: Context, private val label: String, iconId: Int) : View(context) {
    private val scale = HomeScale(context)
    private val icon: Drawable = requireNotNull(context.getDrawable(iconId))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 20f * scale.factor * resources.displayMetrics.scaledDensity
        typeface = scale.semibold
    }
    init {
        isClickable = true
        isFocusable = true
        contentDescription = label
        background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, null)
    }
    override fun onDraw(canvas: Canvas) {
        val iconWidth = scale.px(84)
        val iconHeight = scale.px(40)
        val iconLeft = (width - iconWidth) / 2
        val iconTop = scale.px(22)
        icon.setBounds(iconLeft, iconTop, iconLeft + iconWidth, iconTop + iconHeight)
        icon.draw(canvas)
        val metrics = paint.fontMetrics
        canvas.drawText(label, width / 2f, height * .76f - (metrics.ascent + metrics.descent) / 2f, paint)
    }
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }
}

private class HomeMicrophoneView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val idleIcon = requireNotNull(context.getDrawable(R.drawable.tn_home_microphone_idle))
    private val waveIcon = requireNotNull(context.getDrawable(R.drawable.tn_home_microphone_wave))
    private var waveShader: Shader? = null
    private var circleShader: Shader? = null
    var active = false
        private set(value) {
            field = value
            contentDescription = if (value) "Dừng nghe TN" else "Bắt đầu nghe TN"
            invalidate()
        }
    fun setListening(value: Boolean) { active = value }
    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Bắt đầu nghe TN"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val radius = height / 102f * 40f
        circleShader = LinearGradient(0f, height / 2f - radius, 0f, height / 2f + radius,
            intArrayOf(0xff40eefe.toInt(), 0xff0144f8.toInt()), null, Shader.TileMode.CLAMP)
        val start = width / 2f - 12 * 10 * height / 102f
        waveShader = LinearGradient(start, 0f, width - start, 0f,
            intArrayOf(0xff14e4eb.toInt(), 0xff0050fc.toInt()), null, Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        val unit = height / 102f
        val radius = unit * 40f
        if (!active) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = 0xff001d4d.toInt()
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = unit * 3.5f
            paint.shader = circleShader
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
            val size = unit * 96f
            idleIcon.setBounds(((width - size) / 2).toInt(), ((height - size) / 2).toInt(),
                ((width + size) / 2).toInt(), ((height + size) / 2).toInt())
            idleIcon.draw(canvas)
            return
        }
        paint.shader = waveShader
        val spacing = 10 * unit
        val count = 25
        val start = width / 2f - (count - 1) * spacing / 2
        val phase = android.os.SystemClock.uptimeMillis() / 180.0
        for (index in 0 until count) {
            val amplitude = ((8 + 18 * (sin(index * .58 + phase) * .5 + .5)) * unit).toFloat()
            val x = start + index * spacing
            canvas.drawRoundRect(x, height / 2f - amplitude / 2, x + 4 * unit,
                height / 2f + amplitude / 2, 2 * unit, 2 * unit, paint)
        }
        paint.shader = null
        if (isShown && windowVisibility == VISIBLE) postInvalidateDelayed(33)
    }
}

/** Original RoundedShadowView touch response: Rebound 40/7, scale 1 -> .875. */
private class HomeSuggestionChip(context: Context) : TextView(context) {
    private val body = HomeSuggestionBackground(context)
    private val spring = TnFloatingSpring()
    private var motion: TnFloatingSpringSystem? = null
    init { background = body }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        motion = TnFloatingSpringSystem().apply {
            register(spring) {
                val pressed = it.toFloat().coerceIn(0f, 1f)
                scaleX = 1f - .125f * pressed; scaleY = scaleX
                body.pressed = pressed
            }
        }
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> motion?.target(spring, 1.0)
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> motion?.target(spring, 0.0)
        }
        return super.onTouchEvent(event)
    }
    override fun onDetachedFromWindow() {
        motion?.dispose(); motion = null; spring.snap(0.0)
        scaleX = 1f; scaleY = 1f; body.pressed = 0f
        super.onDetachedFromWindow()
    }
}

private class HomeSuggestionBackground(context: Context) : Drawable() {
    var pressed = 0f
        set(value) { field = value; invalidateSelf() }
    private val scale = HomeScale(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fill: Shader? = null
    private var border: Shader? = null
    override fun onBoundsChange(bounds: android.graphics.Rect) {
        rect.set(bounds)
        rect.inset(scale.px(6).toFloat(), scale.px(6).toFloat())
        fill = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(0x407bc9cf, 0x44325059), null, Shader.TileMode.CLAMP)
        border = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(0xbf9effff.toInt(), 0x229effff), null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        val stroke = scale.px(2).toFloat()
        val radius = rect.height() / 2
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.shader = fill
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.shader = border
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        if (pressed > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((51 * pressed).roundToInt(), 255, 255, 255)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Original CenteringHorizontalScrollView: short rows center; overflow starts at left. */
private class HomeExampleFlow(context: Context) : android.widget.HorizontalScrollView(context) {
    val items = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    init {
        tag = "tnHomeSuggestionScroll"
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(HomeScale(context).px(50))
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(items, FrameLayout.LayoutParams(-2, -1))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val params = items.layoutParams as FrameLayout.LayoutParams
        val alignment = if (items.measuredWidth < measuredWidth) Gravity.CENTER_HORIZONTAL else Gravity.START
        if (params.gravity != alignment) { params.gravity = alignment; items.layoutParams = params }
        super.onLayout(changed, left, top, right, bottom)
    }
}
