package vn.tn.assistant.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import vn.tn.assistant.R
import kotlin.math.roundToInt

/** Constraints and typography from item_settings_zalo_avatar.xml; account state comes only from TN. */
class TnSettingsAccountCard(
    context: Context,
    accountName: String,
    premium: Boolean?,
    subtitle: String,
    onAccount: () -> Unit,
) : ConstraintLayout(context) {
    init {
        val units = TnZaloReferenceMetrics(context)
        fun sd(value: Int) = units.px(value.toFloat())
        fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, units.sp(value), resources.displayMetrics).roundToInt()
        fun text(value: String, size: Float, viewTag: String) = TextView(context).apply {
            id = View.generateViewId(); tag = viewTag; text = value
            typeface = Typeface.createFromAsset(context.assets, "fonts/barlowregular.ttf")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size).toFloat())
            setTextColor(0xffe9f3ff.toInt()); includeFontPadding = true
        }
        tag = "tnSettingsAccountCard"; contentDescription = "Tài khoản"
        isClickable = true; isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x1fffffff), null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 4 * resources.displayMetrics.density })
        val gate = ClickGate(1000)
        setOnClickListener { if (gate.accept(SystemClock.elapsedRealtime())) onAccount() }

        val avatar = FrameLayout(context).apply {
            id = View.generateViewId(); tag = "tnSettingsProfileAvatar"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            setPadding(sd(2), sd(2), sd(2), sd(2))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(ImageView(context).apply {
                tag = "tnSettingsProfilePhoto"; setImageResource(R.drawable.tn_profile_avatar)
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
                }
                clipToOutline = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val name = text(accountName, 28f, "tnSettingsAccountName").apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val description = text(subtitle, 24f, "tnSettingsAccountSubtitle").apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
            setTextColor(if (premium == false) 0xffffaea6.toInt() else 0xc0e9f3ff.toInt())
        }
        // The source's hidden login action remains a constraint anchor even for a signed-in user.
        val loginAnchor = View(context).apply { id = View.generateViewId(); visibility = GONE }
        val badge: View = if (premium == null) View(context).apply { visibility = GONE }
            else TnSettingsAccountBadgeView(context, premium, sp(28f))
        badge.id = View.generateViewId()
        val arrow = ImageView(context).apply {
            id = View.generateViewId(); tag = "tnSettingsAccountArrow"
            setImageResource(R.drawable.tn_settings_ic_settings_arrow_right)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(avatar, LayoutParams(sd(80), sd(80)).apply {
            startToStart = PARENT_ID; topToTop = PARENT_ID; bottomToBottom = PARENT_ID
            marginStart = sd(32); topMargin = sd(28); bottomMargin = sd(28)
        })
        addView(loginAnchor, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            endToEnd = PARENT_ID; topToTop = PARENT_ID; bottomToBottom = PARENT_ID; marginEnd = sd(32)
        })
        addView(name, LayoutParams(0, WRAP_CONTENT).apply {
            startToEnd = avatar.id; endToStart = badge.id; topToTop = PARENT_ID; bottomToTop = description.id
            marginStart = sd(24); topMargin = sd(28); bottomMargin = sd(28)
            horizontalBias = 0f; horizontalChainStyle = LayoutParams.CHAIN_PACKED
            verticalChainStyle = LayoutParams.CHAIN_PACKED; matchConstraintDefaultWidth = LayoutParams.MATCH_CONSTRAINT_WRAP
        })
        addView(badge, LayoutParams(WRAP_CONTENT, sp(28f)).apply {
            startToEnd = name.id; endToStart = loginAnchor.id; topToTop = name.id; bottomToBottom = name.id
            marginStart = sd(12); marginEnd = sd(32); goneEndMargin = sd(72)
        })
        addView(description, LayoutParams(0, WRAP_CONTENT).apply {
            startToStart = name.id; endToEnd = loginAnchor.id; topToBottom = name.id
            topMargin = sd(2); bottomMargin = sd(28); goneEndMargin = sd(72)
        })
        addView(arrow, LayoutParams(sd(24), sd(24)).apply {
            endToEnd = PARENT_ID; topToTop = PARENT_ID; bottomToBottom = PARENT_ID; marginEnd = sd(32)
        })
    }
}
