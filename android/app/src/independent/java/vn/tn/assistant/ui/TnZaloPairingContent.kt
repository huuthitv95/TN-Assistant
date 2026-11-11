package vn.tn.assistant.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Side by side on a dashboard, stacked and scrollable on a phone. */
class TnZaloPairingContent(
    context: Context,
    private val instructions: View,
    private val qr: View,
    private val code: View,
    private val status: View,
) : ViewGroup(context) {
    private var wide = false
    init { addView(instructions); addView(qr); addView(code); addView(status) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun text(view: View, width: Int) = view.measure(exact(width), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        wide = w >= dp(600)
        val q = minOf(dp(175), w)
        qr.measure(exact(q), exact(q))
        val textWidth = if (wide) (w - dp(230)).coerceAtLeast(1) else w
        text(instructions, textWidth); text(code, textWidth); text(status, textWidth)
        val textHeight = instructions.measuredHeight + code.measuredHeight + status.measuredHeight + dp(16)
        val h = if (wide) maxOf(q, textHeight) else textHeight + q + dp(16)
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = 0
        instructions.layout(0, y, instructions.measuredWidth, instructions.measuredHeight)
        y = instructions.bottom + dp(8)
        if (wide) {
            val x = width - qr.measuredWidth - dp(16)
            val top = (height - qr.measuredHeight) / 2
            qr.layout(x, top, x + qr.measuredWidth, top + qr.measuredHeight)
        } else {
            val x = (width - qr.measuredWidth) / 2
            qr.layout(x, y, x + qr.measuredWidth, y + qr.measuredHeight)
            y = qr.bottom + dp(8)
        }
        code.layout(0, y, code.measuredWidth, y + code.measuredHeight)
        y = code.bottom + dp(8)
        status.layout(0, y, status.measuredWidth, y + status.measuredHeight)
    }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
