package vn.tn.assistant.driving

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/** Queried serially by the overlay worker, never on the UI thread. */
internal class TnMapActivityMonitor(private val context:Context) {
    private val state=TnForegroundMapState()
    private var cursor=0L
    fun reset() {state.reset();cursor=0}
    fun isMapForeground():Boolean {
        if(!hasPermission(context)) {reset();return false}
        val now=System.currentTimeMillis()
        // After a clock jump or suspended polling, discard the previous app.
        if(cursor==0L || now-cursor !in 0..10000) {state.reset();cursor=now-3000}
        return try {
            // UsageStatsManager exists on API 21; the Context constant was added in 22.
            val manager=context.getSystemService("usagestats") as UsageStatsManager
            val events=manager.queryEvents(cursor,now) ?: run {reset();return false}
            val event=UsageEvents.Event()
            while(events.hasNextEvent()) {
                events.getNextEvent(event)
                state.accept(TnForegroundMapState.Event(event.eventType,event.packageName.orEmpty(),event.className.orEmpty()))
            }
            cursor=now
            state.isMap()
        } catch(_:RuntimeException) {reset();false}
    }
    companion object {
        fun hasPermission(context:Context):Boolean = try {
            val ops=context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            val mode=if(Build.VERSION.SDK_INT>=29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),context.packageName)
                else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),context.packageName)
            mode==AppOpsManager.MODE_ALLOWED
        } catch(_:RuntimeException) {false}
    }
}
