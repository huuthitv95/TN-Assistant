package vn.tn.assistant.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.tn.assistant.R

/** Guard that all app marks use the owner icon and onboarding retains the reference composition. */
class TnWelcomeAppMarkRuntimeTest {
    @Test fun markUsesTheOwnerSuppliedTnCircleAndFitsResponsiveBounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (bounds in listOf(Rect(0, 0, 44, 44), Rect(0, 0, 80, 80), Rect(0, 0, 258, 258), Rect(0, 0, 44, 80))) {
            val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
            TnWelcomeAppMark(context).apply {
                setBounds(bounds)
                draw(Canvas(bitmap))
            }
            val center = bitmap.getPixel(bounds.width() / 2, bounds.height() / 2)
            assertTrue("TN icon center should retain its white logo at ${bounds.width()}x${bounds.height()}",
                Color.alpha(center) > 0 && Color.red(center) > 200 && Color.green(center) > 200 && Color.blue(center) > 200)
            assertTrue("Owner-supplied green TN circle remains visible at ${bounds.width()}x${bounds.height()}",
                (0 until bitmap.height).any { y -> (0 until bitmap.width).any { x ->
                    val pixel = bitmap.getPixel(x, y)
                    Color.green(pixel) > 100 && Color.green(pixel) > Color.red(pixel) * 1.5f && Color.green(pixel) > Color.blue(pixel) * 1.15f
                } })
            assertTrue("The former cyan-blue product ring is replaced by the TN icon",
                (0 until bitmap.height).none { y -> (0 until bitmap.width).any { x ->
                    val pixel = bitmap.getPixel(x, y)
                    Color.green(pixel) > 160 && Color.blue(pixel) > 170 && Color.red(pixel) < 120
                } })
            if (bounds.height() > bounds.width()) {
                assertTrue("A nonsquare destination must letterbox without stretching", Color.alpha(bitmap.getPixel(1, 1)) == 0)
            }
            bitmap.recycle()
        }
    }

    @Test fun allSixWelcomeIllustrationsReplaceOnlyTheAppMarkWithTheOwnerIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val illustrationResources = listOf(
            R.drawable.tn_reference_onboarding_start,
            R.drawable.tn_reference_onboarding_mic,
            R.drawable.tn_reference_onboarding_music,
            R.drawable.tn_reference_onboarding_overlay,
            R.drawable.tn_reference_onboarding_zalo,
            R.drawable.tn_reference_onboarding_done,
        )
        for (step in 0..5) {
            val surface = TnOnboardingSurface(context, step, "TN", "Description", "CONTINUE", {})
            val image = requireNotNull(surface.findViewWithTag<ImageView>("tnWelcomeIllustration"))
            val actual = Bitmap.createBitmap(480, 301, Bitmap.Config.ARGB_8888)
            image.drawable.apply {
                setBounds(0, 0, 480, 301)
                draw(Canvas(actual))
            }
            val expected = Bitmap.createBitmap(480, 301, Bitmap.Config.ARGB_8888)
            requireNotNull(context.getDrawable(illustrationResources[step])).apply {
                setBounds(0, 0, 480, 301)
                draw(Canvas(expected))
            }
            val markBounds = if (step == 0) Rect(111, 21, 369, 279) else Rect(26, 21, 284, 279)
            val centerX = markBounds.exactCenterX()
            val centerY = markBounds.exactCenterY()
            val iconClip = Path().apply {
                addCircle(centerX, centerY, markBounds.width() / 2f, Path.Direction.CW)
                if (step > 0) {
                    val rightCircle = Path().apply { addCircle(326f, 150f, 126f, Path.Direction.CW) }
                    op(rightCircle, Path.Op.DIFFERENCE)
                }
            }
            val side = (markBounds.width() * 1.05f + 0.5f).toInt()
            val left = (centerX - side / 2f + 0.5f).toInt()
            val top = (centerY - side / 2f + 0.5f).toInt()
            val expectedCanvas = Canvas(expected)
            val save = expectedCanvas.save()
            expectedCanvas.clipPath(iconClip)
            TnWelcomeAppMark(context).apply {
                setBounds(left, top, left + side, top + side)
                draw(expectedCanvas)
            }
            expectedCanvas.restoreToCount(save)
            assertTrue("Welcome card $step keeps the reference composition and owner-supplied TN icon", actual.sameAs(expected))
            actual.recycle()
            expected.recycle()
        }
    }

}
