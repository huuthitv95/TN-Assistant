package vn.tn.assistant.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.PaintDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import vn.tn.assistant.R

/** Settings' source badge: the animated gold vector is clipped by the ImageView's pill outline. */
class TnSettingsAccountBadgeView(
    context: Context,
    val premium: Boolean,
    val badgeHeight: Int,
) : ImageView(context) {
    private var badgeAnimation: Animatable? = null

    /** Intrinsic drawable dimensions include Android's density rounding, as adjustViewBounds does. */
    val badgeWidth: Int

    init {
        require(badgeHeight > 0) { "Badge height must be a positive, SP-resolved pixel dimension" }
        tag = "tnSettingsAccountBadge"
        contentDescription = if (premium) "Premium" else "Basic"
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = true
        setImageResource(if (premium) R.drawable.tn_settings_animated_badge_premium else R.drawable.tn_settings_badge_basic)
        badgeAnimation = drawable as? Animatable
        badgeWidth = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            (drawable.intrinsicWidth.toLong() * badgeHeight / drawable.intrinsicHeight).toInt()
        } else {
            badgeHeight * (if (premium) 90 else 67) / 28
        }
        // Original a3.h uses transparent PaintDrawable + BACKGROUND outline, radius _14sp.
        // The caller resolves _28sp height, so half that dimension retains its font scaling.
        background = PaintDrawable(Color.TRANSPARENT).apply { setCornerRadius(badgeHeight / 2f) }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        isClickable = false
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSizeAndState(badgeWidth, widthMeasureSpec, 0),
            resolveSizeAndState(badgeHeight, heightMeasureSpec, 0),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        background?.setBounds(0, 0, w, h)
        invalidateOutline()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        badgeAnimation?.stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        val animation = badgeAnimation ?: return
        val visible = isAttachedToWindow && isShown && windowVisibility == VISIBLE
        drawable?.setVisible(visible, false)
        if (visible) {
            if (!animation.isRunning) animation.start()
        } else if (animation.isRunning) {
            animation.stop()
        }
    }
}
