package vn.tn.assistant.driving

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import vn.tn.assistant.BuildConfig
import vn.tn.assistant.MainActivity
import vn.tn.assistant.R
import vn.tn.assistant.auth.TnSessionStore

/** User-started trip. No automatic restart, boot start, or persisted location. */
class TnRoadService : Service() {
    private var monitor:TnRoadMonitor?=null
    private var sound:TnRoadAlertAudio?=null
    private val policy=TnOverspeedPolicy()
    private val observers=linkedSetOf<(TnRoadMatcher.Result)->Unit>()
    private var result=TnRoadMatcher.Result("waiting_gps")
    private var owner=Long.MIN_VALUE
    private var active=false
    private var notificationText=""
    private var soundUnavailable=false
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private val guard=object:Runnable {
        override fun run() {
            if(!active) return
            if(!TnSessionStore.get(this@TnRoadService).ownsActions(owner) ||
                !TnRoadMonitor.preciseAllowed(this@TnRoadService) || !notificationsAllowed(this@TnRoadService) ||
                preferences.getString("traffic_warning_mode","none") !in setOf("sound","voice","display")) finishTrip()
            else main.postDelayed(this,1000)
        }
    }
    private val preferences by lazy {getSharedPreferences("tn-session-v1",Context.MODE_PRIVATE)}
    override fun onBind(intent:Intent?)=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action!=START) {finishTrip();return START_NOT_STICKY}
        val session=TnSessionStore.get(this)
        val requestedOwner=intent.getLongExtra(OWNER,Long.MIN_VALUE)
        if(!session.ownsActions(requestedOwner) || !TnRoadMonitor.preciseAllowed(this) || !notificationsAllowed(this) ||
            preferences.getString("traffic_warning_mode","none") !in setOf("sound","voice","display")) {
            finishTrip();return START_NOT_STICKY
        }
        if(active && owner==requestedOwner) return START_NOT_STICKY
        if(active) {finishTrip();return START_NOT_STICKY}
        owner=requestedOwner
        try {
            val notification=notification("Đang chờ vị trí GPS mới…")
            if(Build.VERSION.SDK_INT>=29) startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID,notification)
            active=true;instance=this
            sound=TnRoadAlertAudio(this) {soundUnavailable=true}
            monitor=TnRoadMonitor(this,::update,fetch=if(BuildConfig.DEBUG) testFetch else null)
            monitor?.start()
            if(active) main.post(guard)
        } catch(_:RuntimeException) {finishTrip()}
        return START_NOT_STICKY
    }

    private fun update(next:TnRoadMatcher.Result) {
        if(!active) return
        val mode=preferences.getString("traffic_warning_mode","none").orEmpty()
        if(next.state in setOf("permission_required","session_expired","premium_required","driving_disabled")) {
            finishTrip(next.state);return
        }
        if(!TnSessionStore.get(this).ownsActions(owner) || mode !in setOf("sound","voice","display")) {
            finishTrip();return
        }
        result=next
        val offset=preferences.all["traffic_overspeed_offset"]?.toString()?.toIntOrNull() ?: 0
        val accent=preferences.getString("agent_voice","south").orEmpty()
        val decision=policy.update(next,mode,offset,SystemClock.elapsedRealtime())
        sound?.retain(decision.overThreshold,mode,accent)
        if(!decision.overThreshold || mode !in setOf("sound","voice")) soundUnavailable=false
        if(decision.notify && mode in setOf("sound","voice")) soundUnavailable=sound?.play(mode,accent)!=true
        val text=when {
            soundUnavailable -> "Đã đạt ngưỡng; chưa phát được âm báo."
            decision.overThreshold -> "Đã đạt ngưỡng cảnh báo. ${limitText(next)}"
            else -> limitText(next)
        }
        if(text!=notificationText) {
            notificationText=text
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID,notification(text))
        }
        observers.toList().forEach {it(next)}
    }

    private fun limitText(next:TnRoadMatcher.Result)=when(next.state) {
        "matched" -> "Giới hạn tham khảo ${next.road?.speed?.toInt()} km/h · ${next.road?.name.orEmpty()}"
        "gps_disabled" -> "GPS đang tắt; chưa cảnh báo."
        "network_unavailable" -> "Mất kết nối dữ liệu đường; chưa cảnh báo."
        "waiting_gps" -> "Đang chờ vị trí GPS mới…"
        else -> "Chưa xác định giới hạn đáng tin cậy; chưa cảnh báo."
    }

    private fun notification(text:String):Notification {
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel(CHANNEL,"Cảnh báo giao thông TN",NotificationManager.IMPORTANCE_LOW))
        val pendingFlags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open=PendingIntent.getActivity(this,ID,Intent(this,MainActivity::class.java)
            .putExtra(vn.tn.assistant.ui.TnFloatingControlService.EXTRA_OPEN_TRAFFIC,true),pendingFlags)
        val stop=PendingIntent.getService(this,ID,Intent(this,TnRoadService::class.java).setAction(STOP),pendingFlags)
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.tn_floating_microphone).setContentTitle("TN đang dùng vị trí để cảnh báo")
            .setContentText(text).setContentIntent(open).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(0,"DỪNG",stop).build()).build()
    }

    private fun release(reason:String="trip_stopped") {
        active=false
        main.removeCallbacksAndMessages(null)
        if(instance===this) instance=null
        monitor?.close();monitor=null;sound?.close();sound=null;policy.reset()
        lastState=reason
        observers.toList().forEach {it(TnRoadMatcher.Result(reason))};observers.clear()
    }
    private fun finishTrip(reason:String="trip_stopped") {release(reason);@Suppress("DEPRECATION") stopForeground(true);stopSelf()}
    override fun onTaskRemoved(rootIntent:Intent?) {finishTrip()}
    override fun onDestroy() {if(active) release();super.onDestroy()}

    companion object {
        private const val CHANNEL="tn-road-trip"
        private const val ID=4601
        private const val START="vn.tn.assistant.road.START"
        internal const val STOP="vn.tn.assistant.road.STOP"
        private const val OWNER="owner"
        private var instance:TnRoadService?=null
        private var lastState="trip_stopped"
        // In-process instrumentation seam. Ignored in release builds; never read
        // from intents, preferences or remote configuration.
        internal var testFetch:((TnRoadMatcher.Fix,Long)->TnRoadMatcher.Snapshot)?=null
        fun isRunning()=instance?.active==true
        fun status()=if(isRunning()) "Chuyến đi đang hoạt động; dừng từ thông báo TN hoặc nút Dừng chuyến đi." else when(lastState) {
            "driving_disabled" -> "Chuyến đi đã dừng: dữ liệu giao thông chưa được bật trên máy chủ TN."
            "premium_required" -> "Chuyến đi đã dừng: tài khoản chưa có quyền sử dụng dữ liệu giao thông."
            "session_expired" -> "Chuyến đi đã dừng: phiên đăng nhập đã hết hạn hoặc thay đổi."
            "permission_required" -> "Chuyến đi đã dừng: cần quyền vị trí chính xác."
            else -> "Chưa có chuyến đi đang hoạt động."
        }
        fun notificationsAllowed(context:Context):Boolean {
            if(!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return Build.VERSION.SDK_INT<26 || manager.getNotificationChannel(CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
        }
        fun start(context:Context):Boolean {
            val session=TnSessionStore.get(context)
            val owner=session.actionGeneration()
            if(!session.ownsActions(owner) || !TnRoadMonitor.preciseAllowed(context) || !notificationsAllowed(context)) return false
            return try {
                ContextCompat.startForegroundService(context,Intent(context,TnRoadService::class.java).setAction(START).putExtra(OWNER,owner));true
            } catch(_:RuntimeException) {false}
        }
        // stopService identifies the Android component, not the Intent object's identity.
        @android.annotation.SuppressLint("ImplicitSamInstance")
        fun stop(context:Context) {
            instance?.release()
            context.stopService(Intent(context,TnRoadService::class.java))
        }
        internal fun observeIfRunning(show:(TnRoadMatcher.Result)->Unit):AutoCloseable? {
            val service=instance?.takeIf {it.active} ?: return null
            service.observers+=show;show(service.result)
            return AutoCloseable {service.observers-=show}
        }
    }
}
