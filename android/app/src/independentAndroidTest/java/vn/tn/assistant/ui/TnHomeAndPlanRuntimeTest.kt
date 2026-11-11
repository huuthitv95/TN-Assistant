package vn.tn.assistant.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import vn.tn.assistant.R

class TnHomeAndPlanRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun tagsRemainOneScrollableRowAndLongMessagesFitAboveMicrophone() {
        instrumentation.runOnMainSync {
            for(width in listOf(400,1080,1794)) {
                val home = TnAssistantHomeSurface(instrumentation.targetContext)
                home.setSuggestions(listOf("Thời tiết tại Thành phố Hồ Chí Minh", "Mở video yêu thích của tôi", "Mở nhạc mới nhất").map {
                    TnHomeSuggestion(it,R.drawable.tn_home_settings,it)
                }) {}
                measure(home,width,400)
                val scroll = home.findViewWithTag<HorizontalScrollView>("tnHomeSuggestionScroll")
                val row = scroll.getChildAt(0) as ViewGroup
                assertEquals(row.getChildAt(0).top,row.getChildAt(2).top)
                if (row.measuredWidth>scroll.width) {
                    scroll.scrollTo(80,0)
                    assertTrue("The visible tag row must actually scroll",scroll.scrollX>0)
                } else assertEquals(0,scroll.scrollX)
                val message="Bạn chưa cấp quyền micro; TN chưa ghi âm. Hãy cấp quyền để sử dụng trợ lý giọng nói."
                home.showStatus(message)
                measure(home,width,400)
                val text=home.status
                assertTrue("Last rendered line must be entirely inside the status view",text.layout.getLineBottom(text.layout.lineCount-1)<=text.height)
                assertEquals(message.length,text.layout.getLineEnd(text.layout.lineCount-1))
                assertTrue("Status must never cover microphone",text.bottom<=home.microphone.top)
            }
        }
    }
    @Test fun allPlanTypesExpandTheBodyKeepBothTargetsAndDrawWithoutClipping() {
        instrumentation.runOnMainSync {
            for(kind in listOf("basic","premium","admin")) for(size in listOf(100,135,170)) {
                val view=TnFloatingClusterView(instrumentation.targetContext).apply {
                    unit=size;suggestionVisible=false
                    accountPlan=TnFloatingAccountPlan(kind,Long.MAX_VALUE,1,android.os.SystemClock.elapsedRealtime())
                }
                val dims=TnFloatingGeometry.dimensions(size,false,view.resources.displayMetrics.density,true)
                measure(view,dims.first,dims.second)
                assertTrue(view.accountBadge.right<=view.width)
                assertTrue(view.accountBadge.bottom<=view.height)
                assertTrue(view.microButton.right<=view.accountBadge.left)
                var mic=0;var badge=0
                view.microButton.setOnClickListener {mic++};view.accountBadge.setOnClickListener {badge++}
                view.clickAt((view.microButton.left+view.microButton.right)/2f,(view.microButton.top+view.microButton.bottom)/2f)
                view.clickAt((view.accountBadge.left+view.accountBadge.right)/2f,(view.accountBadge.top+view.accountBadge.bottom)/2f)
                assertEquals(1,mic);assertEquals(1,badge)
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap));bitmap.recycle()
            }
        }
    }
    private fun measure(v:View,w:Int,h:Int) {v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)}
}
