package vn.tn.assistant.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import vn.tn.assistant.R
import kotlin.math.roundToInt

/** Source warning typography and compound-icon geometry, with a real optional permission action. */
class TnSettingsWarningView(context: Context, message: String, actionLabel: String = "", onAction: (() -> Unit)? = null) : ConstraintLayout(context) {
    init {
        val units = TnZaloReferenceMetrics(context)
        fun sd(value: Int) = units.px(value.toFloat())
        setPadding(sd(24), sd(26), sd(24), sd(26))
        val fill = GradientDrawable().apply { setColor(0x19ffffff); cornerRadius = 4 * resources.displayMetrics.density }
        background = RippleDrawable(ColorStateList.valueOf(0x3dffffff), fill, GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = 4 * resources.displayMetrics.density
        })
        elevation = sd(4).toFloat()
        val actionable = actionLabel.isNotBlank() && onAction != null
        isClickable = actionable; isFocusable = actionable
        if (actionable) setOnClickListener { onAction?.invoke() }
        val title = TextView(context).apply {
            id = View.generateViewId()
            text = message; includeFontPadding = false; gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.createFromAsset(context.assets, "fonts/barlowregular.ttf")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (units.sp(28f) * resources.displayMetrics.scaledDensity).roundToInt().toFloat())
            setTextColor(0xffe9f3ff.toInt())
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.tn_settings_ic_warning_circle, 0, 0, 0)
            compoundDrawablePadding = sd(16)
        }
        val action = TextView(context).apply {
            id = View.generateViewId(); visibility = if (actionable) VISIBLE else GONE
            text = actionLabel; includeFontPadding = false
            typeface = Typeface.createFromAsset(context.assets, "fonts/barlowmedium.ttf")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (units.sp(28f) * resources.displayMetrics.scaledDensity).roundToInt().toFloat())
            setTextColor(0xff37dee7.toInt()); setPadding(sd(16), sd(8), sd(16), sd(8))
            background = RippleDrawable(ColorStateList.valueOf(0x1fffffff), null, null)
            val gate = ClickGate(400)
            setOnClickListener { if (gate.accept(SystemClock.elapsedRealtime())) onAction?.invoke() }
        }
        addView(title, LayoutParams(0, -2).apply {
            startToStart = PARENT_ID; endToStart = action.id
            topToTop = PARENT_ID; bottomToBottom = PARENT_ID
            marginEnd = sd(16); goneEndMargin = 0
        })
        addView(action, LayoutParams(-2, -2).apply {
            endToEnd = PARENT_ID; topToTop = PARENT_ID; bottomToBottom = PARENT_ID
        })
    }
}
