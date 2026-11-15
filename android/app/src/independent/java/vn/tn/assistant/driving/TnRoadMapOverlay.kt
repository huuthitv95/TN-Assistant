package vn.tn.assistant.driving

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import vn.tn.assistant.MainActivity
import vn.tn.assistant.ui.TnFloatingControlService
import java.util.concurrent.Executors

/** Passive trip-owned sign: no GPS, account, audio, or service restart of its own. */
internal class TnRoadMapOverlay(private val context:Context):AutoCloseable {
    private val prefs=context.getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE)
    private val windows=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val apps=TnMapActivityMonitor(context)
    private var generation=0
    private var workerGeneration=-1
    private var pending=false
    private var closed=false
    private var mapForeground=false
    private var result=TnRoadMatcher.Result("waiting_gps")
    private var view:LinearLayout?=null
    private var sign:TextView?=null
    private var x=dp(16)
    private var y=dp(100)
    internal val isVisible get()=view?.isAttachedToWindow==true
    private val poll=object:Runnable {
        override fun run() {
            if(closed) return
            if(!allowed()) {generation++;mapForeground=false;detach()}
            else if(!pending) {
                pending=true
                val request=generation
                worker.execute {
                    if(workerGeneration!=request) {apps.reset();workerGeneration=request}
                    val visible=apps.isMapForeground()
                    main.post {
                        pending=false
                        if(!closed && request==generation) {mapForeground=visible;render()}
                    }
                }
            }
            render()
            main.postDelayed(this,1000)
        }
    }
    init {main.post(poll)}
    fun update(value:TnRoadMatcher.Result) {if(!closed) {result=value;render()}}
    private fun allowed():Boolean {
        val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock=context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return prefs.getBoolean("traffic_always_on_map",false) &&
            prefs.getString("traffic_warning_mode","none") in setOf("voice","sound","display") &&
            TnFloatingControlService.hasPermission(context) && TnMapActivityMonitor.hasPermission(context) &&
            power.isInteractive && !lock.isKeyguardLocked && !TnFloatingControlService.isHidden(context) &&
            !TnFloatingControlService.isAppForegroundSuppressed(context)
    }
    private fun render() {
        val speed=result.road?.speed
        val age=result.fix?.let {SystemClock.elapsedRealtime()-it.elapsedMs}
        if(closed || !mapForeground || !allowed() || result.state!="matched" || speed==null ||
            !speed.isFinite() || speed !in 1.0..200.0 || age==null || age !in 0..10000) {detach();return}
        try {
            if(view==null) attach()
            sign?.text=if(speed%1==0.0) speed.toInt().toString() else speed.toString()
            view?.contentDescription="TN: giới hạn tham khảo ${sign?.text} km/h. Mở cài đặt cảnh báo giao thông."
        } catch(_:RuntimeException) {detach()}
    }
    private fun attach() {
        val body=object:LinearLayout(context) {override fun performClick():Boolean=super.performClick()}.apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            setPadding(dp(4),dp(4),dp(4),dp(4))
            background=GradientDrawable().apply {setColor(0xff00225b.toInt());cornerRadius=dp(14).toFloat()}
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable=true
            setOnClickListener {
                try {context.startActivity(Intent(context,MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(TnFloatingControlService.EXTRA_OPEN_TRAFFIC,true))} catch(_:RuntimeException) {}
            }
        }
        val label=TextView(context).apply {
            gravity=Gravity.CENTER;setTextColor(Color.BLACK);textSize=32f;typeface=Typeface.DEFAULT_BOLD
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=GradientDrawable().apply {shape=GradientDrawable.OVAL;setColor(Color.WHITE);setStroke(dp(5),0xffd52a35.toInt())}
        }
        body.addView(label,LinearLayout.LayoutParams(dp(72),dp(72)))
        body.addView(TextView(context).apply {
            setText(vn.tn.assistant.R.string.tn_road_overlay_label);textSize=11f;setTextColor(Color.WHITE);gravity=Gravity.CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(-1,-2))
        // Drag coordinates are physical screen coordinates, including in RTL locales.
        @android.annotation.SuppressLint("RtlHardcoded")
        @Suppress("DEPRECATION")
        val params=WindowManager.LayoutParams(dp(96),-2,
            if(Build.VERSION.SDK_INT>=26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT)
            .apply {gravity=Gravity.TOP or Gravity.LEFT;title="TN road limit";x=this@TnRoadMapOverlay.x;y=this@TnRoadMapOverlay.y}
        var downX=0f;var downY=0f;var startX=0;var startY=0;var dragged=false
        val slop=ViewConfiguration.get(context).scaledTouchSlop
        body.setOnTouchListener {_,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {downX=event.rawX;downY=event.rawY;startX=params.x;startY=params.y;dragged=false;true}
                MotionEvent.ACTION_MOVE -> {
                    val dx=event.rawX-downX;val dy=event.rawY-downY
                    if(kotlin.math.abs(dx)>slop || kotlin.math.abs(dy)>slop) dragged=true
                    if(dragged) {
                        val metrics=context.resources.displayMetrics
                        params.x=(startX+dx.toInt()).coerceIn(0,(metrics.widthPixels-params.width).coerceAtLeast(0))
                        params.y=(startY+dy.toInt()).coerceIn(0,(metrics.heightPixels-body.height-dp(48)).coerceAtLeast(0))
                        x=params.x;y=params.y
                        try {windows.updateViewLayout(body,params)} catch(_:RuntimeException) {detach()}
                    };true
                }
                MotionEvent.ACTION_UP -> {if(!dragged) body.performClick();true}
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        windows.addView(body,params);view=body;sign=label
    }
    private fun detach() {
        view?.let {try {windows.removeViewImmediate(it)} catch(_:RuntimeException) {}}
        view=null;sign=null
    }
    override fun close() {if(closed) return;closed=true;generation++;main.removeCallbacksAndMessages(null);worker.shutdownNow();detach()}
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
}
