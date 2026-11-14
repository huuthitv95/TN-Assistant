package vn.tn.assistant.driving

/** Reference q0/c: two consecutive observations and 5 s cooldown.
 * Incomplete decompiled d2/g does not prove the full original timing behavior;
 * distinct fixes, inclusive thresholds, stale-data rejection and road transitions
 * are explicit TN rules.
 */
internal class TnOverspeedPolicy {
    data class Decision(val overThreshold: Boolean, val notify: Boolean)
    private var key=""
    private var lastFix=Long.MIN_VALUE
    private var lastAlert:Long?=null
    private var count=0

    fun update(result:TnRoadMatcher.Result, mode:String, offset:Int, now:Long):Decision {
        val fix=result.fix
        val road=result.road
        val limit=road?.speed
        if (mode !in setOf("sound","voice","display") || offset !in setOf(-5,0,5) ||
            result.state!="matched" || fix==null || limit==null || !limit.isFinite() || limit !in 1.0..200.0 ||
            fix.speedMps==null || !fix.speedMps.isFinite() || fix.speedMps !in 0.0..70.0 ||
            now-fix.elapsedMs !in 0..10000) {
            key="";count=0;return Decision(false,false)
        }
        val nextKey="${road.group}|$limit|$mode|$offset"
        if (key!=nextKey) {count=0;key=nextKey}
        val over=fix.speedMps*3.6 >= limit+offset
        if (fix.elapsedMs<=lastFix) return Decision(over,false)
        if (lastFix!=Long.MIN_VALUE && fix.elapsedMs-lastFix>10000) count=0
        lastFix=fix.elapsedMs
        if (!over) {count=0;return Decision(false,false)}
        if (lastAlert?.let {now-it in 0..4999}==true) {count=0;return Decision(true,false)}
        count++
        if (count<2 || mode=="display") return Decision(true,false)
        lastAlert=now;count=0
        return Decision(true,true)
    }
    fun reset() {key="";lastFix=Long.MIN_VALUE;lastAlert=null;count=0}
}
