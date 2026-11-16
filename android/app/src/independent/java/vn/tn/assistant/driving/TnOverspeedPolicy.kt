package vn.tn.assistant.driving

/** Reference q0/c and d2/f use two over-limit fixes and a 5 s cooldown.
 * d2/g compares integer km/h strictly above the limit, and resets the alert
 * frequency counter after more than 5 km. TN keeps stale-data and road guards.
 */
internal class TnOverspeedPolicy {
    data class Decision(val overThreshold: Boolean, val notify: Boolean)
    private var key=""
    private var lastFix=Long.MIN_VALUE
    private var lastPoint:TnRoadMatcher.Point?=null
    private var lastAlert:Long?=null
    private var intervalDistanceM=0
    private var alertsInInterval=0
    private var count=0

    fun update(result:TnRoadMatcher.Result, mode:String, offset:Int, now:Long):Decision {
        val fix=result.fix
        val road=result.road
        val limit=road?.speed
        if (mode !in setOf("sound","voice","display") || offset !in setOf(-5,0,5)) {
            reset();return Decision(false,false)
        }

        if (result.state=="matched" && road!=null && limit!=null && limit.isFinite() && limit in 1.0..200.0) {
            val nextKey="${road.group}|$limit|$mode|$offset"
            if (key!=nextKey) {
                key=nextKey;count=0;alertsInInterval=0;intervalDistanceM=0;lastAlert=null;lastPoint=null
            }
        }

        val point=fix?.let { TnRoadMatcher.Point(it.lat,it.lon) }
        val validPoint=point?.let { it.lat.isFinite() && it.lon.isFinite() && kotlin.math.abs(it.lat)<=90 && kotlin.math.abs(it.lon)<=180 }==true
        val freshFix=fix!=null && validPoint && now-fix.elapsedMs in 0..10000 && fix.elapsedMs>lastFix
        if (freshFix) {
            val current=point!!
            lastPoint?.let { previous ->
                intervalDistanceM=(intervalDistanceM+TnRoadMatcher.distanceMeters(previous,current).toInt().coerceAtLeast(0))
                if (intervalDistanceM>5000) {intervalDistanceM=0;alertsInInterval=0}
            }
            lastPoint=current;lastFix=fix!!.elapsedMs
        }

        if (result.state!="matched" || road==null || limit==null || !limit.isFinite() || limit !in 1.0..200.0 ||
            fix==null || !validPoint || fix.speedMps==null || !fix.speedMps.isFinite() || fix.speedMps !in 0.0..70.0 ||
            now-fix.elapsedMs !in 0..10000) {
            count=0;return Decision(false,false)
        }
        val speedKmh=(fix.speedMps.toFloat()*3.6f).toInt()
        val over=speedKmh > limit+offset
        if (!over) {count=0;alertsInInterval=0;intervalDistanceM=0;lastAlert=null;return Decision(false,false)}
        if (!freshFix) return Decision(true,false)
        if (count<2) count++
        if (count<2 || mode=="display" || alertsInInterval>=2 || lastAlert?.let {now-it in 0..4999}==true) return Decision(true,false)
        lastAlert=now;alertsInInterval++
        return Decision(true,true)
    }
    fun reset() {key="";lastFix=Long.MIN_VALUE;lastPoint=null;lastAlert=null;intervalDistanceM=0;alertsInInterval=0;count=0}
}
