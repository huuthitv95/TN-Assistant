package vn.tn.assistant.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import vn.tn.assistant.driving.TnRoadMatcher
import vn.tn.assistant.driving.TnRoadMonitor
import vn.tn.assistant.driving.TnOverspeedPolicy
import vn.tn.assistant.driving.TnRoadAlertAudio
import vn.tn.assistant.driving.TnRoadService

/** The first road consumer is explicitly scoped to this visible screen. */
internal class TnRoadDialog(context: Context,
    private val start: (((TnRoadMatcher.Result) -> Unit) -> AutoCloseable)? = null
) : Dialog(context) {
    private val chrome=TnSettingsChrome(context)
    private var monitor: AutoCloseable?=null
    private var revision=0
    private var sharedTrip=false
    private val overspeed=TnOverspeedPolicy()
    private var sound:TnRoadAlertAudio?=null
    private var audioFailed=false
    private val alert=chrome.text("",22f,true).apply {tag="tnRoadAlert";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
    private val status=chrome.text("Đang chờ GPS…",24f).apply { tag="tnRoadStatus"; accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val sign=chrome.text("—",48f,true).apply {
        tag="tnRoadSign";gravity=Gravity.CENTER;setTextColor(Color.BLACK)
        background=GradientDrawable().apply {shape=GradientDrawable.OVAL;setColor(Color.WHITE);setStroke(dp(8),0xffd52a35.toInt())}
        visibility=View.GONE
    }
    private val roadName=chrome.text("",22f)
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(20),dp(24),dp(20));setBackgroundColor(0xff00225b.toInt())}
        content.addView(chrome.text("Giới hạn theo vị trí",30f,true))
        content.addView(status)
        content.addView(sign,LinearLayout.LayoutParams(dp(112),dp(112)).apply {gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(16)})
        content.addView(roadName)
        content.addView(alert)
        content.addView(chrome.text("Dữ liệu tham khảo; luôn tuân theo biển báo thực tế. Khi đã bắt đầu chuyến đi, cảnh báo tiếp tục sau khi đóng màn hình; dừng bằng thông báo TN. Giọng nói cảnh báo dùng giọng miền Nam hoặc miền Bắc đã chọn.",21f))
        content.addView(chrome.text("© OpenStreetMap contributors · ODbL 1.0",18f))
        content.addView(chrome.text("ĐÓNG",24f,true).apply {
            gravity=Gravity.CENTER;minHeight=dp(48);background=chrome.ripple();isFocusable=true
            contentDescription="Đóng màn hình vị trí";setOnClickListener {dismiss()}
        })
        // Short landscape windows and large accessibility fonts must still allow
        // the user to reach the stop/close action.
        setContentView(ScrollView(context).apply { isFillViewport=true;addView(content) })
    }
    private fun dp(value: Int)=(value*context.resources.displayMetrics.density).toInt()
    override fun show() {
        if (isShowing) return
        super.show();window?.setLayout((context.resources.displayMetrics.widthPixels*.9).toInt(),-2)
        val attempt=++revision
        val callback:(TnRoadMatcher.Result)->Unit={result -> if (isShowing && attempt==revision) render(result)}
        if(start!=null) monitor=start.invoke(callback)
        else {
            sharedTrip=true
            monitor=TnRoadService.observeIfRunning(callback)
            if(monitor==null) {
                sharedTrip=false
                monitor=TnRoadMonitor(context,callback).also {it.start()}
            }
        }
    }
    private fun render(result: TnRoadMatcher.Result) {
        val speed=result.road?.speed?.takeIf {result.state=="matched"}
        sign.visibility=if (speed==null) View.GONE else View.VISIBLE
        sign.text=if (speed==null) "—" else if (speed%1==0.0) speed.toInt().toString() else speed.toString()
        sign.contentDescription=if (speed==null) "Chưa xác định giới hạn" else "Giới hạn tham khảo $speed km/h"
        roadName.text=if (speed==null) "" else result.road?.name.orEmpty()
        status.text=when(result.state) {
            "matched" -> "Giới hạn tham khảo theo đoạn đường (km/h)"
            "trip_stopped" -> "Chuyến đi đã dừng. Đóng màn hình để bắt đầu lại."
            "permission_required" -> "Cần quyền vị trí chính xác. Đóng màn hình và cấp quyền để thử lại."
            "session_expired" -> "Phiên TN đã thay đổi hoặc hết hạn. Đóng màn hình và đăng nhập lại."
            "premium_required" -> "Tài khoản chưa có quyền sử dụng dữ liệu giao thông."
            "driving_disabled" -> "Dữ liệu giao thông chưa được bật trên máy chủ TN."
            "gps_disabled" -> "GPS đang tắt hoặc không có trên thiết bị."
            "waiting_gps" -> "Đang chờ vị trí GPS mới…"
            "location_unreliable" -> "Vị trí chưa đủ chính xác hoặc đã quá cũ."
            "direction_unavailable" -> "Chưa xác định được hướng di chuyển đáng tin cậy."
            "loading" -> "Đang lấy dữ liệu đoạn đường…"
            "query_outdated" -> "Đang chờ dữ liệu cho vị trí mới…"
            "ambiguous" -> "Có nhiều đoạn đường phù hợp; chưa xác định giới hạn."
            "source_stale" -> "Dữ liệu nguồn đã cũ hoặc chưa rõ ngày cập nhật."
            "limit_unknown" -> "Đoạn đường chưa có giới hạn tốc độ rõ ràng."
            "geometry_missing" -> "Chưa có hình học đoạn đường đủ tin cậy."
            "network_unavailable" -> "Chưa kết nối được dữ liệu đường. Sẽ thử lại khi còn mở màn hình."
            else -> "Chưa có dữ liệu phù hợp để xác định giới hạn."
        }
        val preferences=context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE)
        val mode=preferences.getString("traffic_warning_mode","none").orEmpty()
        val offset=preferences.all["traffic_overspeed_offset"]?.toString()?.toIntOrNull() ?: 0
        val accent=preferences.getString("agent_voice","south").orEmpty()
        val decision=overspeed.update(result,mode,offset,android.os.SystemClock.elapsedRealtime())
        sound?.retain(decision.overThreshold,mode,accent)
        if(!decision.overThreshold || mode !in setOf("sound","voice")) audioFailed=false
        alert.text=when {
            audioFailed -> "Đã đạt ngưỡng; chưa phát được cảnh báo âm thanh."
            decision.overThreshold -> "Đã đạt ngưỡng cảnh báo tốc độ đã chọn."
            else -> ""
        }
        if(decision.notify && mode in setOf("sound","voice") && !sharedTrip) {
            val player=sound ?: TnRoadAlertAudio(context) {audioFailed=true}.also {sound=it}
            player.retain(decision.overThreshold,mode,accent)
            audioFailed=!player.play(mode,accent)
            if(audioFailed) alert.text="Đã đạt ngưỡng; chưa phát được cảnh báo âm thanh."
        }
    }
    override fun dismiss() { revision++;monitor?.close();monitor=null;sound?.close();sound=null;overspeed.reset();super.dismiss() }
}
