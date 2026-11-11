package vn.tn.assistant.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import vn.tn.assistant.R
import kotlin.math.roundToInt

/** Original fragment_settings navigation drawer, with TN values and callbacks. */
internal class TnSettingsDrawerSurface(
    context: Context,
    title: String,
    values: List<Pair<String, String>>,
    selected: String,
    private val px: (Int) -> Int,
    text: (String, Float, Boolean) -> TextView,
    radio: () -> RadioButton,
    private val dismissed: () -> Unit,
    pick: (String) -> Unit,
) : DrawerLayout(context) {
    private val panel = FrameLayout(context).apply {
        tag = "tnSettingsDrawerPanel"
        setBackgroundColor(0xf200225b.toInt())
        isClickable = true
    }
    private var closing = false
    private var afterClose: (() -> Unit)? = null

    init {
        tag = "tnSettingsDrawer"
        addView(FrameLayout(context), LayoutParams(-1, -1))
        addView(panel, LayoutParams(px(520), -1, Gravity.END))
        panel.addView(ImageView(context).apply {
            setImageResource(R.drawable.tn_settings_dialog_light_bottom)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, px(32), Gravity.BOTTOM))
        panel.addView(View(context).apply { setBackgroundColor(0xaaffffff.toInt()) },
            FrameLayout.LayoutParams((1.5f * resources.displayMetrics.density).roundToInt(), -1, Gravity.START))
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(content, FrameLayout.LayoutParams(-1, -1))
        content.addView(text(title, 32f, true).apply {
            tag = "tnSettingsDrawerTitle"
            gravity = Gravity.CENTER
            setPadding(0, px(8), 0, px(8))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16); bottomMargin = px(16) })
        fun divider(parent: LinearLayout, inset: Boolean) {
            parent.addView(View(context).apply { setBackgroundColor(0x59ffffff) },
                LinearLayout.LayoutParams(-1, (resources.displayMetrics.density + .5f).toInt()).apply {
                    if (inset) { marginStart = px(16); marginEnd = px(16) }
                })
        }
        divider(content, true)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(context).apply {
            tag = "tnSettingsDrawerList"
            isVerticalScrollBarEnabled = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { marginStart = px(16); marginEnd = px(16) })
        values.forEachIndexed { index, (label, value) ->
            val option = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(80)
                setPadding(0, px(16), 0, px(16))
                contentDescription = label
                tag = "tnSettingsDrawerOption:$value"
                isFocusable = true
                setOnClickListener { dismiss { pick(value) } }
            }
            option.addView(text(label, 28f, false), LinearLayout.LayoutParams(0, -2, 1f))
            option.addView(radio().apply {
                tag = "tnSettingsDrawerRadio:$value"
                isChecked = value == selected
                isClickable = false
                isFocusable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0, 0, 0, 0)
            })
            list.addView(option, LinearLayout.LayoutParams(-1, -2))
            if (index < values.lastIndex) divider(list, false)
        }
        divider(content, true)
        content.addView(text("ĐÓNG", 22f, false).apply {
            tag = "tnSettingsDrawerClose"
            gravity = Gravity.CENTER
            setTextColor(0xff37dee7.toInt())
            // The original's explicit textAppearance keeps single line spacing.
            // Only this close action has selectableItemBackground; options do not.
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff), null,
                GradientDrawable().apply { setColor(Color.WHITE) })
            minimumHeight = px(72)
            setPadding(0, px(16), 0, px(16))
            isFocusable = true
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
        addDrawerListener(object : SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                dismissed()
                afterClose?.invoke()
                afterClose = null
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { if (isAttachedToWindow && !closing) openDrawer(panel) }
    }

    fun dismiss(after: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        afterClose = after
        if (!isAttachedToWindow || !isDrawerVisible(panel)) {
            dismissed()
            afterClose?.invoke()
            afterClose = null
        } else closeDrawer(panel)
    }

    /** Source geometry tests measure a detached surface without animations. */
    internal fun showImmediatelyForMeasurement() = openDrawer(panel, false)
}
