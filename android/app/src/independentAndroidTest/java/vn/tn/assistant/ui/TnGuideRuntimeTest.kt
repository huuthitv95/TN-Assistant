package vn.tn.assistant.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.MainActivity

class TnGuideRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun measure(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun bounds(root: ViewGroup, child: View): Rect = Rect().also { child.getDrawingRect(it); root.offsetDescendantRectToMyCoords(child, it) }

    @Test fun originalPortraitGuidePositionsMatchTheCapturedDeviceBounds() {
        instrumentation.runOnMainSync {
            val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                screenWidthDp = 411; screenHeightDp = 659; smallestScreenWidthDp = 411; fontScale = 1f
            }
            val context = instrumentation.targetContext.createConfigurationContext(config)
            val guide = TnAssistantGuideSurface(context, {}, {})
            measure(guide, 1080, 1731)
            val measured = org.json.JSONArray()
            val expected = mapOf(
                "Tìm đường" to Rect(306, 39, 1080, 99),
                "Dẫn đường đến chợ Bến Thành" to Rect(205, 129, 690, 206),
                "Chỉ đường đến số 72 Nguyễn Cơ Thạch" to Rect(205, 238, 792, 315),
                "Tìm trạm xăng gần đây" to Rect(205, 347, 585, 424),
                "Nghe nhạc" to Rect(306, 501, 1080, 561)
            )
            for ((text, reference) in expected) {
                val child = views(guide).filterIsInstance<TextView>().single { it.text.toString() == text }
                val actual = bounds(guide, child)
                measured.put(org.json.JSONObject().put("text", text).put("bounds", org.json.JSONArray(listOf(actual.left,actual.top,actual.right,actual.bottom))))
                for ((a,b) in listOf(actual.left to reference.left, actual.top to reference.top, actual.right to reference.right, actual.bottom to reference.bottom))
                    assertTrue("Captured original guide bounds for $text: expected=$reference, actual=$actual", kotlin.math.abs(a-b) <= 2)
            }
            assertEquals(Rect(63,25,142,104), bounds(guide,guide.closeButton))
            assertEquals("Source guide has no extra app title or logo row", 8, views(guide).count { it.tag == "tnGuideHeading" })
            java.io.File(instrumentation.targetContext.cacheDir,"tn177-guide-portrait-bounds.json").writeText(measured.toString(2))
        }
    }

    @Test fun originalLandscapeGuidePositionsMatchTheCapturedDeviceBounds() {
        instrumentation.runOnMainSync {
            val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                screenWidthDp = 683; screenHeightDp = 387; smallestScreenWidthDp = 411; fontScale = 1f
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
            val context = instrumentation.targetContext.createConfigurationContext(config)
            val guide = TnAssistantGuideSurface(context, {}, {})
            measure(guide, 1794, 1017)
            val expected = mapOf(
                "Tìm đường" to Rect(281, 36, 1794, 91),
                "Dẫn đường đến chợ Bến Thành" to Rect(188, 118, 640, 188),
                "Chỉ đường đến số 72 Nguyễn Cơ Thạch" to Rect(682, 118, 1230, 188),
                "Tìm trạm xăng gần đây" to Rect(1272, 118, 1626, 188),
                "Nghe nhạc" to Rect(281, 259, 1794, 314)
            )
            for ((text, reference) in expected) {
                val child = views(guide).filterIsInstance<TextView>().single { it.text.toString() == text }
                val actual = bounds(guide, child)
                for ((a,b) in listOf(actual.left to reference.left, actual.top to reference.top, actual.right to reference.right, actual.bottom to reference.bottom))
                    assertTrue("Landscape source bounds for $text: expected=$reference, actual=$actual", kotlin.math.abs(a-b) <= 2)
            }
            assertEquals(Rect(58,23,130,95), bounds(guide,guide.closeButton))
        }
    }

    @Test fun guideCommandsKeepSelectionDebounceAcrossChipsAndFitNarrowLargeTextLayouts() {
        instrumentation.runOnMainSync {
            for (width in listOf(480,1080,1794)) for (font in listOf(1f,1.5f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale = font }
                val context = instrumentation.targetContext.createConfigurationContext(config)
                var commands = 0; var closed = 0
                val guide = TnAssistantGuideSurface(context, {closed++}, {commands++})
                measure(guide,width,900)
                val chips = views(guide).filterIsInstance<TextView>().filter {it.tag == "tnGuideCommand"}
                assertEquals(25,chips.size)
                for (chip in chips) {
                    assertTrue("Chip must remain inside its row",chip.right <= (chip.parent as View).width)
                    assertTrue("All text lines must fit the chip height",chip.layout.getLineBottom(0) <= chip.height-chip.paddingBottom)
                }
                chips[0].performClick(); chips[1].performClick()
                assertEquals(1,commands); assertTrue(chips[0].isSelected); assertFalse(chips[1].isSelected)
                assertEquals("Selecting a command keeps the original guide visible",0,closed)
            }
        }
    }

    @Test fun actualGuideKeepsHomeControlsAndClosesAfterTheSourceExitMotion() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        Thread.sleep(1500)
        try {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("renderHome").apply {isAccessible=true}.invoke(activity)
                views(activity.window.decorView).single {it.contentDescription=="HƯỚNG DẪN"}.performClick()
            }
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                val guide=views(activity.window.decorView).filterIsInstance<TnAssistantGuideSurface>().single()
                assertTrue(guide.isShown)
                assertEquals("Guide must never expose the launcher behind its translucent scrim", android.graphics.Color.rgb(0,8,23),
                    ((guide.parent as View).background as android.graphics.drawable.ColorDrawable).color)
                assertEquals(View.VISIBLE,views(activity.window.decorView).single {it.contentDescription=="CÀI ĐẶT"}.visibility)
                assertFalse("Guide must hide home suggestion tags",views(activity.window.decorView).any {it.tag=="tnHomeSuggestionScroll" && it.visibility==View.VISIBLE})
                guide.closeButton.performClick()
                assertTrue("Close does not remove the guide before its exit animation",guide.isAttachedToWindow)
            }
            Thread.sleep(150)
            instrumentation.runOnMainSync {assertTrue(views(activity.window.decorView).any {it is TnAssistantGuideSurface})}
            val deadline=SystemClock.uptimeMillis()+1500
            var closed=false
            while(!closed && SystemClock.uptimeMillis()<deadline) {
                instrumentation.runOnMainSync { closed=views(activity.window.decorView).none {it is TnAssistantGuideSurface} }
                if(!closed)Thread.sleep(40)
            }
            assertTrue("Guide must close after its 400ms exit",closed)
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
