package vn.tn.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.ImageButton
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import androidx.core.view.OneShotPreDrawListener
import vn.tn.assistant.R
import kotlin.math.max
import kotlin.math.min

/** TN onboarding using only the reference UI images explicitly authorized by the owner. */
class TnOnboardingSurface(
    context: Context,
    private val step: Int,
    title: String,
    description: CharSequence,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) : FrameLayout(context) {
    val contentHost = FrameLayout(context)
    val primaryButton: Button = action(primaryText, onPrimary)
    val secondaryButton: Button? = secondaryText?.let { text -> action(text) { onSecondary?.invoke() } }

    private val heading = TextView(context).apply {
        text = title
        setOriginalTextSize(R.dimen.tn_welcome_title_size)
        setTextColor(CYAN)
        typeface = tnTypeface(true)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, originalDimension(R.dimen.tn_welcome_small_padding))
        accessibilityHeadingCompat()
    }
    private val body = TextView(context).apply {
        text = description
        if (description is android.text.Spanned) {
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        setOriginalTextSize(R.dimen.tn_welcome_body_size)
        setTextColor(Color.WHITE)
        typeface = tnTypeface(false)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.25f)
        setPadding(0, 0, 0, originalDimension(R.dimen.tn_welcome_common_padding))
    }
    private val emblem = ImageView(context).apply {
        tag = "tnWelcomeIllustration"
        setImageDrawable(TnWelcomeIllustrationDrawable(context, step.coerceIn(0, 5)))
        scaleType = ImageView.ScaleType.FIT_CENTER
        // fragment_onboarding.xml centers the artwork between the top margin and title.
        // No extra translation is present in the source layout or its entry animation.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
    }
    private val dots = ProgressDots(context, step.coerceIn(0, 5))
    private val stage = Stage(context)
    private var contentMaxWidth = 420f
    private var backControl: TextView? = null
    private var overflowControl: ImageButton? = null
    private var overflowLayer: FrameLayout? = null
    private var overflowDismissing = false
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        background = TnWelcomeBackground(context)
        fitsSystemWindows = true
        clipChildren = false
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.addView(emblem)
        stage.addView(heading)
        stage.addView(body)
        stage.addView(contentHost)
        stage.addView(primaryButton)
        secondaryButton?.let(stage::addView)
        stage.addView(dots)
    }

    fun setContent(view: View, maxWidthDp: Float = 420f) {
        contentMaxWidth = maxWidthDp
        contentHost.removeAllViews()
        contentHost.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stage.requestLayout()
    }

    /** Only TN account forms need an in-app Back action; original onboarding has none. */
    fun setBackAction(back: (() -> Unit)?) {
        backControl?.let(::removeView)
        backControl = back?.let { callback ->
            TextView(context).apply {
                text = "‹"
                textSize = 24f
                setTextColor(CYAN)
                // Keep the glyph at the left edge of the large touch target, matching the
                // original toolbar arrow and leaving Android 16's status clock unobstructed.
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(dp(4f), 0, 0, 0)
                contentDescription = "Quay lại"
                isFocusable = true
                isClickable = true
                setBackgroundResource(R.drawable.tn_welcome_control_ripple)
                setOnClickListener { callback() }
            }.also { control ->
                addView(control, LayoutParams(dp(48f), dp(48f), Gravity.TOP or Gravity.START).apply {
                    leftMargin = 0; topMargin = dp(8f)
                })
            }
        }
        updateScrollClip()
    }

    /** Available only when a configured login alternative actually exists. Never shown by default. */
    fun setOverflowAction(action: (() -> Unit)?) {
        clearOverflowMenu()
        overflowControl?.let { it.animate().cancel(); removeView(it) }
        overflowControl = action?.let { callback ->
            val viewportWidth = resources.displayMetrics.widthPixels
            val controlSize = responsiveDp(42.5f, viewportWidth, 0.0731f)
            val edgeInset = responsiveDp(27.2f, viewportWidth, 0.0463f)
            ImageButton(context).apply {
                setImageResource(R.drawable.tn_welcome_overflow)
                scaleType = ImageView.ScaleType.CENTER
                setBackgroundResource(R.drawable.tn_welcome_overflow_background)
                setPadding(dp(13.6f), dp(13.6f), dp(13.6f), dp(13.6f))
                contentDescription = "Tùy chọn đăng nhập"
                elevation = dp(10.2f).toFloat()
                alpha = 0f
                val clickGate = ClickGate(500L)
                setOnClickListener {
                    if (clickGate.accept(android.os.SystemClock.elapsedRealtime())) {
                        if (overflowLayer == null) showOverflowMenu(callback) else dismissOverflowMenu()
                    }
                }
            }.also { control ->
                addView(control, LayoutParams(controlSize, controlSize, Gravity.TOP or Gravity.END).apply {
                    topMargin = edgeInset; marginEnd = edgeInset
                })
                control.animate().alpha(1f).setStartDelay(300L).start()
            }
        }
    }

    /**
     * Match the original Zalo recovery popover: one overflow control opens a compact card,
     * and its sole action switches to TN's real QR pairing flow. The blank area dismisses it.
     */
    private fun showOverflowMenu(callback: () -> Unit) {
        if (overflowLayer != null) return
        // The source card caps at 288dp on wide layouts and scales with the viewport on
        // portrait phones. These bounds match the original live app at 1080x1920 and 1600x900.
        val panelWidth = min(dp(288f), (width * 0.4935f + 0.5f).toInt())
            .coerceAtMost((width - 2 * dp(16f)).coerceAtLeast(0))
        val panelInset = responsiveDp(27.2f, width, 0.0463f)
            .coerceAtMost(((width - panelWidth) / 2).coerceAtLeast(0))
        val textInset = min(dp(27f), (panelWidth * 0.0938f + 0.5f).toInt())
            .coerceAtMost(((panelWidth - dp(40f)) / 2).coerceAtLeast(0))
        val actionWidth = min(dp(149.3f), (panelWidth * 0.5185f + 0.5f).toInt())
            .coerceAtMost((panelWidth - 2 * textInset).coerceAtLeast(0))
        val actionInset = responsiveDp(27.2f, panelWidth, 0.0938f)
            .coerceAtMost(((panelWidth - actionWidth) / 2).coerceAtLeast(0))
        val panelHeight = min(dp(92.6f), (panelWidth * 0.321f + 0.5f).toInt())
        val panelTopInset = responsiveDp(27.2f, width, 0.0463f)
        val titleTop = min(dp(13.3f), (panelHeight * 0.1436f + 0.5f).toInt())
        val titleHeight = min(dp(32f), (width * 0.0537f + 0.5f).toInt())
        val actionTop = min(dp(52f), (panelHeight * 0.562f + 0.5f).toInt())
        val actionHeight = min(dp(27.3f), (panelHeight * 0.2924f + 0.5f).toInt())
        val layer = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismissOverflowMenu() }
        }
        // j2.i registers the alternative on the entire recovery card. Share
        // dismissal with the CTA so taps during its delayed exit dispatch once.
        val recover: () -> Unit = { if (dismissOverflowMenu()) callback() }
        val panel = FrameLayout(context).apply {
            tag = "tnZaloRecoveryMenu"
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 49, 63))
                cornerRadius = dp(7f).toFloat()
            }
            // j2/i.java gives the recovery card the same _12dp elevation as its trigger.
            elevation = TnZaloReferenceMetrics(context).px(12f).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { recover() }
        }
        val title = TextView(context).apply {
            tag = "tnZaloRecoveryTitle"
            text = "Bạn gặp lỗi đăng nhập?"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.rgb(233, 243, 255))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 16, 24, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        panel.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, titleHeight, Gravity.TOP or Gravity.START).apply {
            topMargin = titleTop
            leftMargin = textInset
            rightMargin = textInset
        })
        val tryAlternative = TextView(context).apply {
            tag = "tnZaloRecoveryAction"
            text = "THỬ CÁCH KHÁC"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.CYAN)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            maxLines = 1
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 14, 20, 1, TypedValue.COMPLEX_UNIT_SP)
            contentDescription = text
            setBackgroundResource(R.drawable.tn_welcome_control_ripple)
            setOnClickListener { recover() }
        }
        panel.addView(tryAlternative, LayoutParams(actionWidth, actionHeight, Gravity.TOP or Gravity.END).apply {
            topMargin = actionTop
            marginEnd = actionInset
        })
        layer.addView(panel, LayoutParams(panelWidth, panelHeight, Gravity.TOP or Gravity.END).apply {
            topMargin = panelTopInset
            marginEnd = panelInset
        })
        overflowLayer = layer
        overflowDismissing = false
        addView(layer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        overflowControl?.let { control ->
            control.animate().cancel()
            if (isAttachedToWindow && recoveryAnimationsEnabled()) {
                // OnboardingFragment.v(): the overflow fades and shrinks as the hint opens.
                control.animate().alpha(0f).scaleX(0f).scaleY(0f).setStartDelay(0L).setDuration(300L)
                    .setInterpolator(recoveryInterpolator()).withEndAction { control.visibility = View.INVISIBLE }.start()
            } else control.visibility = View.INVISIBLE
        }
        if (isAttachedToWindow && recoveryAnimationsEnabled()) {
            panel.visibility = View.INVISIBLE
            OneShotPreDrawListener.add(panel) {
                if (overflowLayer === layer && !overflowDismissing && panel.isAttachedToWindow) {
                    panel.visibility = View.VISIBLE
                    panel.translationX = panel.width + TnZaloReferenceMetrics(context).px(32f).toFloat()
                    panel.animate().translationX(0f).setStartDelay(0L).setDuration(300L)
                        .setInterpolator(recoveryInterpolator()).start()
                }
            }
        }
    }

    /** Returns true only when an open popover was dismissed. */
    fun dismissOverflowMenu(): Boolean {
        val layer = overflowLayer ?: return false
        if (overflowDismissing) return false
        overflowDismissing = true
        val panel = layer.findViewWithTag<View>("tnZaloRecoveryMenu")
        if (!isAttachedToWindow || !recoveryAnimationsEnabled() || panel == null || !panel.isLaidOut) {
            clearOverflowMenu()
            return true
        }
        // OnboardingFragment.u() retains the card during a 400ms delay, then slides it out.
        panel.animate().cancel()
        panel.animate().translationX(panel.width + TnZaloReferenceMetrics(context).px(32f).toFloat())
            .setStartDelay(400L).setDuration(300L).setInterpolator(recoveryInterpolator())
            .withEndAction { if (overflowLayer === layer) clearOverflowMenu() }.start()
        return true
    }

    private fun clearOverflowMenu() {
        val layer = overflowLayer
        overflowLayer = null
        overflowDismissing = false
        layer?.findViewWithTag<View>("tnZaloRecoveryMenu")?.animate()?.cancel()
        if (layer != null) removeView(layer)
        overflowControl?.let { control ->
            control.animate().cancel()
            control.alpha = 1f; control.scaleX = 1f; control.scaleY = 1f
            control.visibility = View.VISIBLE
        }
    }

    private fun recoveryInterpolator() = AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in)
    private fun recoveryAnimationsEnabled() = android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A replacement surface attaches before its children have been measured. Start only
        // at pre-draw so the relative 15% translation uses the actual illustration width.
        OneShotPreDrawListener.add(emblem) {
            if (isAttachedToWindow && contentHost.childCount == 0 && emblem.width > 0) {
                emblem.startAnimation(AnimationUtils.loadAnimation(context, R.anim.tn_welcome_illustration_in))
            }
        }
    }

    override fun onDetachedFromWindow() {
        emblem.clearAnimation()
        overflowControl?.animate()?.cancel()
        clearOverflowMenu()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The source onboarding fragment starts below the Android status-bar inset even
        // when its window draws edge-to-edge. Keep the illustration/title/footer geometry
        // inside that same content rectangle while TN hides the status bar itself.
        val contentTop = if (contentHost.childCount == 0) originalStatusBarInset() else 0
        if (scroll.paddingTop != contentTop) scroll.setPadding(scroll.paddingLeft, contentTop, scroll.paddingRight, scroll.paddingBottom)
        stage.viewportHeight = (View.MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom - contentTop).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun originalStatusBarInset(): Int {
        val resource = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resource == 0) 0 else resources.getDimensionPixelSize(resource).coerceAtLeast(0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScrollClip()
    }

    private fun updateScrollClip() {
        // This reserved strip applies to TN forms only; normal onboarding has no toolbar.
        scroll.clipBounds = if (backControl != null) Rect(0, dp(56f), width, height) else null
    }

    private fun action(label: String, callback: () -> Unit): Button = Button(
        ContextThemeWrapper(context, R.style.TnReferenceWelcomeButtonTheme)
    ).apply {
        text = label
        setOriginalTextSize(R.dimen.tn_welcome_body_size)
        isAllCaps = true
        setLineSpacing(0f, 1.25f)
        setShadowLayer(8f, 0f, 0f, 0x88000000.toInt())
        typeface = tnTypeface(false)
        setTextColor(CYAN)
        gravity = Gravity.CENTER
        minimumHeight = 0
        minHeight = 0
        minimumWidth = 0
        minWidth = 0
        setBackgroundResource(R.drawable.tn_reference_welcome_button)
        val contentPadding = Rect()
        background.getPadding(contentPadding)
        setPadding(contentPadding.left, contentPadding.top, contentPadding.right, contentPadding.bottom)
        contentDescription = label
        setOnClickListener { callback() }
    }

    private fun TextView.accessibilityHeadingCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun TextView.setOriginalTextSize(resource: Int) {
        // Resolve the same smallest-width / width / height buckets as the reference XML.
        // SP dimensions retain font scaling; resource-pixel rounding matches TextView inflation.
        setTextSize(TypedValue.COMPLEX_UNIT_PX, originalDimension(resource).toFloat())
    }

    private fun originalDimension(resource: Int) = resources.getDimensionPixelSize(resource)

    /** A scrollable stage keeps enlarged text, account forms and a visible keyboard from overlapping. */
    private inner class Stage(context: Context) : ViewGroup(context) {
        var viewportHeight = 0
        private var hasForm = false
        private var compactLayout = false
        private var footerTop = 0
        private val divider = context.getDrawable(R.drawable.tn_reference_welcome_divider)!!
        private val glow = ImageView(context).apply {
            setImageResource(R.drawable.tn_reference_welcome_glow)
            scaleType = ImageView.ScaleType.FIT_START
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            setWillNotDraw(false)
            addView(glow)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = View.MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val innerWidth = (w - dp(32f)).coerceAtLeast(1)
            val textWidth = min(innerWidth, dp(980f))
            val buttonWidth = min((w - dp(48f)).coerceAtLeast(1), originalDimension(R.dimen.tn_welcome_button_width))
            hasForm = contentHost.childCount > 0
            val emblemWidth = min(innerWidth, originalDimension(R.dimen.tn_welcome_image_width))
            val emblemHeight = originalDimension(R.dimen.tn_welcome_image_height)
            emblem.measure(exact(emblemWidth), exact(emblemHeight))
            heading.measure(atMost(textWidth), unspecified())
            body.measure(atMost(textWidth), unspecified())
            val formWidth = min((w - dp(48f)).coerceAtLeast(1), dp(contentMaxWidth))
            contentHost.measure(exact(formWidth), unspecified())
            val buttonHeight = originalDimension(R.dimen.tn_welcome_button_height)
            primaryButton.measure(exact(buttonWidth), exact(buttonHeight))
            secondaryButton?.measure(exact(buttonWidth), exact(buttonHeight))
            dots.measure(unspecified(), unspecified())
            val secondaryHeight = secondaryButton?.let { it.measuredHeight + dp(8f) } ?: 0
            val minimum = if (hasForm) {
                dp(64f) + heading.measuredHeight + dp(8f) + body.measuredHeight + dp(18f) +
                    contentHost.measuredHeight + dp(28f) + primaryButton.measuredHeight +
                    secondaryHeight + dp(32f) + dots.measuredHeight + dp(16f)
            } else {
                dp(64f) + emblem.measuredHeight + dp(28f) + heading.measuredHeight +
                    body.measuredHeight + originalDimension(R.dimen.tn_welcome_common_padding) + primaryButton.measuredHeight +
                    secondaryHeight + dp(42f) + dots.measuredHeight + dp(16f)
            }
            var stageHeight = max(viewportHeight, minimum)
            compactLayout = minimum > viewportHeight || resources.configuration.fontScale > 1.3f ||
                (!hasForm && normalTitleTop(stageHeight) < dp(56f) + emblem.measuredHeight + dp(20f)) ||
                (hasForm && normalFooterBottom(stageHeight, secondaryHeight) > stageHeight)
            if (compactLayout && !hasForm) {
                val scrollableMinimum = dp(64f) + emblem.measuredHeight + dp(40f) +
                    heading.measuredHeight + dp(8f) + body.measuredHeight + dp(56f) +
                    primaryButton.measuredHeight + secondaryHeight + dp(28f) +
                    dots.measuredHeight + dp(16f)
                stageHeight = max(stageHeight, scrollableMinimum)
            }
            glow.measure(exact(w), exact(stageHeight))
            setMeasuredDimension(w, stageHeight)
        }

        private fun normalTitleTop(h: Int) =
            (h * CONTENT_GUIDELINE_FRACTION).toInt() - originalDimension(R.dimen.tn_welcome_common_padding) - body.measuredHeight - heading.measuredHeight

        private fun normalPrimaryTop(h: Int) =
            ((2f * (h * CONTENT_GUIDELINE_FRACTION).toInt() + h - dots.measuredHeight - primaryButton.measuredHeight) / 3f + 0.5f).toInt()

        private fun normalFooterBottom(h: Int, secondaryHeight: Int): Int {
            val primaryY = if (hasForm) {
                val headingTop = max(dp(64f), (h * 0.065f).toInt())
                val formBottom = headingTop + heading.measuredHeight + dp(8f) +
                    body.measuredHeight + dp(18f) + contentHost.measuredHeight
                max(formBottom + dp(28f), (h * 0.72f).toInt())
            } else {
                normalPrimaryTop(h)
            }
            val lastActionBottom = primaryY + primaryButton.measuredHeight + secondaryHeight
            val dotsTop = max(lastActionBottom + dp(28f), (h * 0.92f - dots.measuredHeight / 2f).toInt())
            return dotsTop + dots.measuredHeight + dp(10f)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val h = height
            val primaryY: Int
            if (compactLayout) {
                var y = dp(64f)
                emblem.visibility = if (hasForm) View.GONE else View.VISIBLE
                if (!hasForm) {
                    center(emblem, y)
                    y += emblem.measuredHeight + dp(40f)
                }
                center(heading, y)
                y += heading.measuredHeight + dp(8f)
                center(body, y)
                y += body.measuredHeight
                if (hasForm) {
                    y += dp(18f)
                    center(contentHost, y)
                    y += contentHost.measuredHeight
                }
                footerTop = y + dp(if (hasForm) 14f else 22f)
                primaryY = footerTop + dp(if (hasForm) 14f else 34f)
            } else if (hasForm) {
                // A login form replaces the large illustration so all controls remain reachable.
                emblem.visibility = View.GONE
                var y = max(dp(64f), (h * 0.065f).toInt())
                center(heading, y)
                y += heading.measuredHeight + dp(8f)
                center(body, y)
                y += body.measuredHeight + dp(18f)
                center(contentHost, y)
                primaryY = max(y + contentHost.measuredHeight + dp(28f), (h * 0.72f).toInt())
                footerTop = primaryY - dp(18f)
            } else {
                emblem.visibility = View.VISIBLE
                val titleTop = normalTitleTop(h)
                // Match the original ConstraintLayout: center the bounded artwork between
                // the top margin and the title, using the same resolved title size qualifier.
                center(emblem, ((originalDimension(R.dimen.tn_welcome_top_margin) + titleTop - emblem.measuredHeight) / 2f + 0.5f).toInt())
                center(heading, titleTop)
                center(body, heading.bottom)
                footerTop = (h * CONTENT_GUIDELINE_FRACTION).toInt()
                primaryY = normalPrimaryTop(h)
            }
            center(primaryButton, primaryY)
            val primaryHitArea = Rect(primaryButton.left, primaryButton.top, primaryButton.right, primaryButton.bottom)
            val touchPadding = ((dp(48f) - primaryButton.height) / 2).coerceAtLeast(0)
            primaryHitArea.inset(0, -touchPadding)
            touchDelegate = TouchDelegate(primaryHitArea, primaryButton)
            var lastBottom = primaryButton.bottom
            secondaryButton?.let {
                center(it, lastBottom + dp(8f))
                lastBottom = it.bottom
            }
            val dotsTop = if (!hasForm && !compactLayout && secondaryButton == null) {
                (lastBottom + h - dots.measuredHeight) / 2
            } else max(lastBottom + dp(28f), (h * 0.92f - dots.measuredHeight / 2f).toInt())
            center(dots, min(dotsTop, h - dots.measuredHeight - dp(10f)))
            glow.layout(0, footerTop, width, h)
            invalidate()
        }

        private fun center(view: View, y: Int) {
            val x = ((width - view.measuredWidth) / 2f + 0.5f).toInt()
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            divider.setBounds(0, footerTop, width, footerTop + originalDimension(R.dimen.tn_welcome_divider_height))
            divider.draw(canvas)
        }

        override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    private class ProgressDots(context: Context, private val step: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // These screen-size overrides exist only at mdpi in the reference resources.
        // Let Android resolve density-qualified values instead of applying them at all DPIs.
        private val diameter = resources.getDimension(R.dimen.tn_welcome_dot_size).toInt().coerceAtLeast(1)
        private val selectedDiameter = resources.getDimension(R.dimen.tn_welcome_selected_dot_size).toInt().coerceAtLeast(diameter)
        private val margin = resources.getDimension(R.dimen.tn_welcome_dot_margin).toInt()
        init { contentDescription = "Bước ${step + 1} trên 6" }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(5 * diameter + selectedDiameter + 12 * margin, selectedDiameter)
        }
        override fun onDraw(canvas: Canvas) {
            var x = margin.toFloat()
            for (i in 0..5) {
                paint.color = if (i == step) Color.rgb(64, 238, 254) else Color.rgb(178, 221, 235)
                val d = if (i == step) selectedDiameter else diameter
                canvas.drawCircle(x + d / 2f, height / 2f, d / 2f, paint)
                x += d + 2 * margin
            }
        }
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun responsiveDp(maxDp: Float, viewportWidth: Int, fraction: Float) =
        min(dp(maxDp), (viewportWidth * fraction + 0.5f).toInt().coerceAtLeast(1))
    private fun tnTypeface(bold: Boolean): Typeface = runCatching {
        ResourcesCompat.getFont(context, if (bold) R.font.tn_barlow_semibold else R.font.tn_barlow_regular)
    }.getOrNull() ?: Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.AT_MOST)
    private fun unspecified() = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

    companion object {
        /** The source onboarding XML constrains content and footer to guideline percent 0.668. */
        private const val CONTENT_GUIDELINE_FRACTION = 0.668f
        private val CYAN = Color.rgb(55, 222, 231)
    }
}

/** Use the complete owner-authorized original artwork for each onboarding card. */
private fun onboardingIllustration(step: Int): Int = when (step) {
    0 -> R.drawable.tn_reference_onboarding_start
    1 -> R.drawable.tn_reference_onboarding_mic
    2 -> R.drawable.tn_reference_onboarding_music
    3 -> R.drawable.tn_reference_onboarding_overlay
    4 -> R.drawable.tn_reference_onboarding_zalo
    else -> R.drawable.tn_reference_onboarding_done
}

/** Preserve the original composition while replacing the former product mark with TN's icon. */
private class TnWelcomeIllustrationDrawable(context: Context, private val step: Int) : Drawable() {
    private val reference = requireNotNull(context.getDrawable(onboardingIllustration(step)))
    private val appMark = requireNotNull(context.getDrawable(R.drawable.tn_user_welcome_circle))

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val mark = if (step == 0) Rect(111, 21, 369, 279) else Rect(26, 21, 284, 279)
        val sx = b.width() / ART_WIDTH.toFloat()
        val sy = b.height() / ART_HEIGHT.toFloat()
        fun x(value: Int) = b.left + (value * sx + 0.5f).toInt()
        fun y(value: Int) = b.top + (value * sy + 0.5f).toInt()
        val markBounds = Rect(x(mark.left), y(mark.top), x(mark.right), y(mark.bottom))
        val centerX = (markBounds.left + markBounds.right) / 2f
        val centerY = (markBounds.top + markBounds.bottom) / 2f
        val circleRadius = min(markBounds.width(), markBounds.height()) / 2f
        val iconClip = Path().apply {
            addCircle(centerX, centerY, circleRadius, Path.Direction.CW)
            if (step > 0) {
                val rightCircle = Path().apply {
                    addCircle(x(RIGHT_CIRCLE_CENTER_X).toFloat(), y(RIGHT_CIRCLE_CENTER_Y).toFloat(),
                        RIGHT_CIRCLE_RADIUS * min(sx, sy), Path.Direction.CW)
                }
                op(rightCircle, Path.Op.DIFFERENCE)
            }
        }
        val iconSide = (min(markBounds.width(), markBounds.height()) * ICON_SCALE + 0.5f).toInt()
        val iconLeft = (centerX - iconSide / 2f + 0.5f).toInt()
        val iconTop = (centerY - iconSide / 2f + 0.5f).toInt()
        val iconBounds = Rect(iconLeft, iconTop, iconLeft + iconSide, iconTop + iconSide)
        val save = canvas.save()
        canvas.clipRect(b)
        reference.bounds = b
        reference.draw(canvas)
        canvas.clipPath(iconClip)
        appMark.bounds = iconBounds
        appMark.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        reference.alpha = alpha
        appMark.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        reference.colorFilter = colorFilter
        appMark.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth() = reference.intrinsicWidth
    override fun getIntrinsicHeight() = reference.intrinsicHeight

    private companion object {
        const val ART_WIDTH = 480
        const val ART_HEIGHT = 301
        const val ICON_SCALE = 1.05f
        const val RIGHT_CIRCLE_CENTER_X = 326
        const val RIGHT_CIRCLE_CENTER_Y = 150
        const val RIGHT_CIRCLE_RADIUS = 126f
    }
}

/** Use the exact owner-supplied TN circle icon anywhere the app brand is shown. */
class TnWelcomeAppMark(context: Context) : Drawable() {
    private val source = requireNotNull(context.getDrawable(R.drawable.tn_user_welcome_circle))
    private var drawableAlpha = 255
    private var filter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val side = min(b.width(), b.height())
        val left = b.centerX() - side / 2
        val top = b.centerY() - side / 2
        val iconBounds = Rect(left, top, left + side, top + side)
        val save = canvas.save()
        canvas.clipRect(b)
        source.alpha = drawableAlpha
        source.colorFilter = filter
        source.bounds = iconBounds
        source.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        filter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth() = source.intrinsicWidth
    override fun getIntrinsicHeight() = source.intrinsicHeight
}

/** Owner-authorized reference UI background; no-argument fallback preserves existing callers. */
class TnWelcomeBackground(context: Context? = null) : Drawable() {
    private val reference = context?.getDrawable(R.drawable.tn_reference_welcome_background)
    private val foundation = Paint().apply { color = Color.rgb(0, 8, 23) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        reference?.let {
            // The reference PNG's final row is translucent; match its original dark parent.
            canvas.drawRect(bounds, foundation)
            it.bounds = bounds
            it.draw(canvas)
            return
        }
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        paint.alpha = 255
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(Color.rgb(0, 8, 23), Color.rgb(0, 15, 39), Color.rgb(0, 34, 85), Color.rgb(0, 50, 116)),
            floatArrayOf(0f, 0.34f, 0.7f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(w * 0.5f, h * 1.1f, max(w * 0.52f, h * 0.55f),
            intArrayOf(Color.argb(205, 91, 154, 212), Color.argb(50, 32, 95, 175), Color.TRANSPARENT),
            floatArrayOf(0f, 0.47f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val pitch = min(w / 38f, h / 25f).coerceAtLeast(10f)
        // Edge clusters leave the centered content and actions unobstructed.
        for (row in 0..9) for (col in 0..7) {
            val attenuation = (1f - col / 8f) * (1f - kotlin.math.abs(row - 4.5f) / 5.5f)
            if (attenuation <= 0f) continue
            paint.color = Color.argb((72 * attenuation).toInt(), 42, 205, 220)
            val radius = pitch * (0.035f + ((row * 3 + col * 5) % 4) * 0.018f) * attenuation
            canvas.drawCircle(col * pitch * 0.72f, h * 0.255f + row * pitch, radius, paint)
            canvas.drawCircle(w - col * pitch * 0.72f, h * 0.735f + row * pitch, radius, paint)
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; foundation.alpha = alpha; reference?.alpha = alpha; invalidateSelf() }
    override fun getAlpha() = foundation.alpha
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; foundation.colorFilter = colorFilter; reference?.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = if (foundation.alpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
}
