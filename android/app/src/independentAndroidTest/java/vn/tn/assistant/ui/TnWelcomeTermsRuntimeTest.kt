package vn.tn.assistant.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.UnderlineSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual rendered TextView, link hit testing and ordinary readable body text. */
class TnWelcomeTermsRuntimeTest {
    @Test fun finalWelcomeTermsRemainUnderlinedClickableAndReadableAtBothOrientations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val sentence = "TN Assistant đã sẵn sàng. "
            val terms = "Điều khoản dịch vụ"
            val expected = sentence + terms
            for ((width, height) in listOf(1600 to 900, 1080 to 1920)) {
                var callbacks = 0
                val description = SpannableStringBuilder(expected).apply {
                    setSpan(UnderlineSpan(), sentence.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { callbacks++ }
                        // Source j2.j keeps the body color; UnderlineSpan supplies the underline.
                        override fun updateDrawState(ds: TextPaint) = Unit
                    }, sentence.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val surface = TnOnboardingSurface(instrumentation.targetContext, 5,
                    "Cài đặt hoàn tất", description, "BẮT ĐẦU SỬ DỤNG", {})
                surface.measure(exact(width), exact(height))
                surface.layout(0, 0, width, height)
                val body = descendants(surface).filterIsInstance<TextView>().single { it.text.toString() == expected }
                assertTrue("A displayed terms span retains Android's link movement", body.movementMethod is LinkMovementMethod)
                assertEquals("The source link does not flash a selection background", Color.TRANSPARENT, body.highlightColor)
                val rendered = body.text as Spanned
                val underline = rendered.getSpans(0, rendered.length, UnderlineSpan::class.java).single()
                val click = rendered.getSpans(0, rendered.length, ClickableSpan::class.java).single()
                for (span in listOf(underline, click)) {
                    assertEquals("Only the terms label is interactive or underlined", sentence.length, rendered.getSpanStart(span))
                    assertEquals(expected.length, rendered.getSpanEnd(span))
                }
                val bitmap = Bitmap.createBitmap(body.width.coerceAtLeast(1), body.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try { body.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
                val painted = TextPaint(body.paint).apply { isUnderlineText = false }
                underline.updateDrawState(painted)
                click.updateDrawState(painted)
                assertTrue("The rendered terms glyphs retain their underline", painted.isUnderlineText)
                assertEquals("The source final body and terms retain the same foreground", Color.WHITE, painted.color)

                val info = body.createAccessibilityNodeInfo()
                try {
                    assertEquals("Accessibility reads the full natural sentence", expected, info.text.toString())
                    assertFalse("Terms remain ordinary body text rather than a password field", info.isPassword)
                    assertFalse("Terms are not exposed as an editable account field", info.isEditable)
                    assertNull("No duplicate spoken content replaces the body text", info.contentDescription)
                } finally { info.recycle() }

                val layout = requireNotNull(body.layout)
                fun tapCharacter(offset: Int) {
                    val line = layout.getLineForOffset(offset)
                    val x = (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(offset + 1)) / 2f + body.totalPaddingLeft - body.scrollX
                    val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + body.totalPaddingTop - body.scrollY
                    val at = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = MotionEvent.obtain(at, at + 10, MotionEvent.ACTION_UP, x, y, 0)
                    try { body.dispatchTouchEvent(down); body.dispatchTouchEvent(up) }
                    finally { down.recycle(); up.recycle() }
                }
                tapCharacter(2)
                assertEquals("Reading or tapping the sentence must not activate the terms", 0, callbacks)
                tapCharacter(sentence.length + 2)
                assertEquals("A real TextView touch on the underlined label activates the link once", 1, callbacks)
            }
        }
    }

    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
