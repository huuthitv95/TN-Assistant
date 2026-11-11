package vn.tn.assistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Actual b2.o.draw capture from the signed-in original on TNParity30, not a TN-generated golden. */
class TnFloatingOriginalPixelsRuntimeTest {
    @Test fun originalBackingHitRegionOpensTheMicrophoneButOuterPaddingDoesNot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = TnFloatingClusterView(instrumentation.targetContext).apply { unit = 135; suggestionVisible = false }
            view.measure(exact(152), exact(158)); view.layout(0, 0, 152, 158)
            var clicks = 0
            view.microButton.setOnClickListener { clicks++ }
            // b2.o.H from onLayout / b2.j.onSingleTapUp, larger than the artwork G.
            view.clickAt(9f, 16f)
            assertEquals("A tap on the original backing region reaches the mic", 1, clicks)
            view.clickAt(129f, 136f)
            assertEquals("The other inside corner remains clickable", 2, clicks)
            view.clickAt(7f, 76f); view.clickAt(130f, 76f); view.clickAt(68f, 137f)
            assertEquals("Rect.contains excludes surrounding padding and right/bottom edges", 2, clicks)
            view.microEnabled = false
            view.clickAt(9f, 16f)
            assertEquals("Disabled state still blocks the enlarged target", 2, clicks)
        }
    }

    @Test fun idleMicrophoneMatchesOriginalNativeDrawingAndHitTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = instrumentation.context.assets.open("reference/original-floating-unit135.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })!!
        }
        instrumentation.runOnMainSync {
            val view = TnFloatingClusterView(instrumentation.targetContext).apply {
                unit = 135
                suggestionVisible = false
            }
            view.measure(exact(152), exact(158))
            view.layout(0, 0, 152, 158)
            assertEquals("Source separately rounded microphone edges", listOf(20, 27, 117, 125),
                listOf(view.microButton.left, view.microButton.top, view.microButton.right, view.microButton.bottom))
            assertEquals("The source microphone anchor is left of the window center", 68, view.anchorCenterX)
            assertEquals(76, view.anchorCenterY)
            val actual = Bitmap.createBitmap(152, 158, Bitmap.Config.ARGB_8888)
            try {
                // The reference is an established overlay. Its shared Paint has
                // already rendered a frame; compare the same steady state.
                val warmup = Bitmap.createBitmap(152, 158, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(warmup))
                warmup.recycle()
                view.draw(Canvas(actual))
                var totalDifference = 0L
                var changedPixels = 0
                for (y in 0 until expected.height) for (x in 0 until expected.width) {
                    val a = actual.getPixel(x, y)
                    val e = expected.getPixel(x, y)
                    // Compare premultiplied channels: hidden RGB of transparent pixels is irrelevant.
                    val aa = Color.alpha(a); val ea = Color.alpha(e)
                    val differences = listOf(abs(aa - ea),
                        abs(Color.red(a) * aa / 255 - Color.red(e) * ea / 255),
                        abs(Color.green(a) * aa / 255 - Color.green(e) * ea / 255),
                        abs(Color.blue(a) * aa / 255 - Color.blue(e) * ea / 255))
                    totalDifference += differences.sum()
                    if (differences.max() > 4) changedPixels++
                }
                val mean = totalDifference.toDouble() / (expected.width * expected.height * 4)
                val fraction = changedPixels.toDouble() / (expected.width * expected.height)
                java.io.File(instrumentation.targetContext.cacheDir, "tn-floating-source-metrics.json").writeText(
                    org.json.JSONObject().put("mean_premultiplied_channel_difference", mean)
                        .put("pixels_with_channel_difference_over_4", changedPixels)
                        .put("changed_pixel_fraction", fraction)
                        .put("reference_unit", 135).put("reference_width", 152).put("reference_height", 158)
                        .put("reference_origin", "unmodified_original_b2.o_draw_on_TNParity30").toString(2))
                if (mean > 1.0 || fraction > .01) {
                    java.io.File(instrumentation.targetContext.cacheDir, "tn-floating-native171.png").outputStream().use {
                        actual.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                assertTrue("Mean native original drawing difference=$mean; changed fraction=$fraction", mean <= 1.0 && fraction <= .01)
            } finally {
                actual.recycle()
                expected.recycle()
            }
        }
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
