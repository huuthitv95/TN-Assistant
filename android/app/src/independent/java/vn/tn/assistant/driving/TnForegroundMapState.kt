package vn.tn.assistant.driving

/** Activity transitions only; no usage history is persisted or sent to the backend. */
internal class TnForegroundMapState {
    data class Event(val type:Int,val packageName:String,val activity:String)
    private var foreground:Pair<String,String>?=null
    fun reset() {foreground=null}
    fun accept(event:Event) {
        when(event.type) {
            1 -> foreground=event.packageName to event.activity // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
            2,23 -> if(foreground==(event.packageName to event.activity)) reset() // PAUSED / STOPPED
            16,17,26,27 -> reset() // screen off, keyguard shown, shutdown, startup
        }
    }
    fun isMap()=foreground?.first in PACKAGES
    companion object {
        val PACKAGES=setOf("com.google.android.apps.maps","com.navitel","com.vietmap.s1OBU","com.vietmap.S2OBU","vn.vietmap.live")
    }
}
