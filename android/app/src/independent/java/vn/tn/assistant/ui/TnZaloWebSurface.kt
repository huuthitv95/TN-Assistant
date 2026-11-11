package vn.tn.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import vn.tn.assistant.R

/**
 * Source SDK chrome around the genuine Zalo authorization page. Web content remains Zalo-owned:
 * TN neither recreates its credential form nor overlays a locally generated login QR.
 * Geometry: zalosdk_activity_zalo_web_login + zalosdk_videw_web_login_toolbar.xml.
 */
class TnZaloWebSurface(
    context: Context,
    content: View,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAlternative: () -> Unit,
) : FrameLayout(context) {
    private val page = content
    private val loading = TnZaloSweepRing(context)
    private val recovery = TnZaloRecoveryHint(context, onAlternative)
    private val errorMessage = text("", 17f, Color.rgb(45, 45, 45)).apply {
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val errorLayer = ScrollView(context).apply {
        setBackgroundColor(Color.WHITE)
        isFillViewport = true
        visibility = View.GONE
    }
    private val verifyingMessage = TextView(context).apply {
        typeface = tnZaloFont(context)
        textSize = TnZaloReferenceMetrics(context).sp(16f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val verifyingLayer = FrameLayout(context).apply {
        setBackgroundColor(0x88000000.toInt())
        visibility = GONE
        isClickable = true
    }

    init {
        setBackgroundColor(Color.WHITE)
        fitsSystemWindows = true
        // LoginTheme in the reference app inherits Holo.Light. Resolve its platform
        // metrics instead of forcing the phone's 48dp ActionBar onto tablet layouts.
        val hostContext = ContextThemeWrapper(context, android.R.style.Theme_Holo_Light)
        val hostAttributes = hostContext.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        val hostBarHeight = hostAttributes.getDimensionPixelSize(0, dp(48))
        hostAttributes.recycle()
        // The source Holo home-view offset comes from its legacy up bitmap:
        // 25px at 240dpi and 44px at 420dpi, rather than the modern 24dp icon.
        val hostTitleStart = (17f * resources.displayMetrics.density).toInt()
        val vertical = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val appToolbar = FrameLayout(context).apply {
            tag = "tnZaloAppToolbar"
            setBackgroundColor(Color.rgb(250, 242, 205))
        }
        appToolbar.addView(TextView(hostContext).apply {
            text = "Liên kết TN Assistant với Zalo để tăng cường trải nghiệm cá nhân hóa của bạn"
            setTextAppearance(hostContext, android.R.style.TextAppearance_Holo_Widget_ActionBar_Title)
            setTextColor(Color.BLACK)
            tag = "tnZaloAppTitle"
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            leftMargin = hostTitleStart
            rightMargin = dp(16)
        })
        vertical.addView(appToolbar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, hostBarHeight))

        val toolbar = FrameLayout(context).apply { setBackgroundColor(BLUE) }
        toolbar.tag = "tnZaloSdkToolbar"
        // The original SDK uses 18dp, system sans-serif here (not the app's Barlow font).
        toolbar.addView(text("Login with Zalo", 18f, Color.WHITE).apply {
            tag = "tnZaloSdkTitle"
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        toolbar.addView(ImageView(context).apply {
            contentDescription = "Quay lại"
            tag = "tnZaloSdkBack"
            setImageResource(R.drawable.tn_zalo_back_arrow)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(0, dp(10), 0, dp(10))
            background = tnZaloRipple(0f, Color.TRANSPARENT)
            isFocusable = true
            isClickable = true
            setOnClickListener { onBack() }
        }, LayoutParams(dp(42), dp(48), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(10)
        })
        vertical.addView(toolbar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))

        val contentFrame = FrameLayout(context).apply {
            tag = "tnZaloWebContent"
            setBackgroundColor(Color.WHITE)
        }
        (page.parent as? ViewGroup)?.removeView(page)
        contentFrame.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The drawable declares 76dp, but Holo's default ProgressBar clamps the
        // actual view to 48dp (the source XML uses wrap_content).
        loading.tag = "tnZaloLoadingRing"
        contentFrame.addView(loading, LayoutParams(dp(48), dp(48), Gravity.CENTER))

        errorLayer.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(errorMessage, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(Button(context).apply {
                text = "THỬ LẠI"
                textSize = 15f
                minimumHeight = dp(48)
                setTextColor(BLUE)
                background = tnZaloRipple(dp(4).toFloat(), Color.TRANSPARENT)
                setOnClickListener { onRetry() }
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        contentFrame.addView(errorLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        vertical.addView(contentFrame, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(vertical, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val metrics = TnZaloReferenceMetrics(context)
        addView(recovery, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply {
            topMargin = hostBarHeight + dp(48) + metrics.px(32f)
            marginEnd = metrics.px(32f)
            leftMargin = metrics.px(32f)
        })

        // fake_login_activity.xml: dim scrim, centered circle, 16sp message during verification.
        val verifyingColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), 0)
            addView(TnZaloSweepRing(context, Color.rgb(55, 222, 231)),
                LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(verifyingMessage, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT).apply { topMargin = metrics.px(10f) })
        }
        verifyingLayer.addView(verifyingColumn, LayoutParams(LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(verifyingLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun loading(visible: Boolean) {
        loading.visibility = if (visible && errorLayer.visibility != VISIBLE && verifyingLayer.visibility != VISIBLE) VISIBLE else GONE
    }

    fun error(message: String?) {
        val present = !message.isNullOrBlank()
        errorMessage.text = message.orEmpty()
        errorLayer.visibility = if (present) VISIBLE else GONE
        page.importantForAccessibility = if (present) IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (present) {
            loading.visibility = GONE
            verifyingLayer.visibility = GONE
            recovery.reveal()
        } else recovery.dismiss()
    }

    fun status(message: String) {
        verifyingMessage.text = message
        verifyingLayer.visibility = if (message.isBlank()) GONE else VISIBLE
        if (message.isNotBlank()) { loading.visibility = GONE; recovery.dismiss() }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    companion object { private val BLUE = Color.rgb(1, 142, 229) }
}
