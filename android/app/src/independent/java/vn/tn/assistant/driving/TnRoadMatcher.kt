package vn.tn.assistant.driving

import kotlin.math.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** Conservative candidate selection, not a navigation/map-matching engine.
 * MFRI: platform 5 + accessibility 4 - interaction 1 - performance 2 - offline 4 = 2.
 * Keep the first consumer foreground-only; uncertainty never becomes a speed limit.
 */
internal object TnRoadMatcher {
    data class Fix(val lat: Double, val lon: Double, val accuracy: Double,
        val bearing: Double?, val speedMps: Double?, val elapsedMs: Long)
    data class Point(val lat: Double, val lon: Double)
    data class Road(val id: String, val group: String, val anchor: Point, val bearing: Double,
        val start: Point?, val end: Point?, val speed: Double?, val updatedAt: Long?, val name: String)
    data class Snapshot(val roads: List<Road>, val complete: Boolean, val elapsedMs: Long,
        val center: Point? = null, val radius: Double? = null)
    data class Result(val state: String, val road: Road? = null, val fix: Fix? = null)

    fun parse(response: JSONObject, elapsedMs: Long): Snapshot {
        val input = response.optJSONArray("road_candidates") ?: return Snapshot(emptyList(), false, elapsedMs)
        var valid = response.opt("road_candidates_version") == 1 && response.opt("road_candidates_complete") == true && input.length() <= 10000
        val roads = mutableListOf<Road>()
        if (input.length() > 10000) return Snapshot(emptyList(), false, elapsedMs)
        fun number(row: JSONObject, key: String): Double? = (row.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        fun point(row: JSONObject, lat: String, lon: String): Point? {
            val a = number(row, lat) ?: return null; val b = number(row, lon) ?: return null
            return Point(a, b).takeIf { coordinates(it) }
        }
        for (index in 0 until input.length()) {
            val row = input.optJSONObject(index)
            if (row == null) { valid = false; continue }
            val anchor = point(row, "latitude", "longitude")
            val bearing = number(row, "bearing")
            val id = row.optString("id")
            if (anchor == null || bearing == null || bearing !in 0.0..<360.0 || id.isBlank()) { valid = false; continue }
            val rawSpeed = number(row, "max_speed")
            val limit = rawSpeed?.takeIf { it in 1.0..200.0 }
            if (row.has("max_speed") && !row.isNull("max_speed") && limit == null) valid = false
            roads += Road(id, row.optString("road_group"), anchor, bearing,
                point(row,"start_latitude","start_longitude"), point(row,"end_latitude","end_longitude"),
                limit, timestamp(row.optString("source_updated_at")), row.optString("name").take(160))
        }
        return Snapshot(roads, valid, elapsedMs)
    }

    fun match(fix: Fix, snapshot: Snapshot, nowElapsed: Long, nowUtc: Long): Result {
        if (!coordinates(Point(fix.lat,fix.lon)) || !fix.accuracy.isFinite() || fix.accuracy !in 0.0..20.0 ||
            nowElapsed - fix.elapsedMs !in 0..10000) return Result("location_unreliable")
        if (fix.bearing == null || !fix.bearing.isFinite() || fix.bearing !in 0.0..<360.0 ||
            fix.speedMps == null || !fix.speedMps.isFinite() || fix.speedMps !in 2.0..70.0) return Result("direction_unavailable")
        if (!snapshot.complete || nowElapsed - snapshot.elapsedMs !in 0..15000) return Result("data_unavailable")
        val point = Point(fix.lat,fix.lon)
        // Leave enough margin for a segment's midpoint and a parallel unknown
        // road. A cached query cannot establish coverage after the car leaves it.
        if (snapshot.center != null && (snapshot.radius == null || !snapshot.radius.isFinite() ||
                distance(point,snapshot.center) > snapshot.radius-100)) return Result("query_outdated")
        val candidates = mutableListOf<Pair<Road,Double>>()
        var uncertain = false
        for (road in snapshot.roads) {
            if (angle(road.bearing,fix.bearing) > 30) continue
            val start = road.start; val end = road.end
            if (start == null || end == null || road.group.isBlank()) {
                if (distance(point,road.anchor) <= 50 + fix.accuracy) uncertain = true
                continue
            }
            val length = distance(start,end)
            if (!coordinates(start) || !coordinates(end) || length !in .1..100.0 ||
                angle(heading(start,end),road.bearing)>5 || distance(road.anchor, Point((start.lat+end.lat)/2,(start.lon+end.lon)/2))>1) {
                uncertain = true; continue
            }
            val crossTrack = distanceToSegment(point,start,end) ?: continue
            if (crossTrack <= max(8.0,fix.accuracy)) candidates += road to crossTrack
        }
        if (uncertain) return Result("geometry_missing")
        if (candidates.isEmpty()) return Result("no_matching_road")
        val best = candidates.minBy { it.second }
        // Two nearby roads, or conflicting speeds on one road, are ambiguous
        // even when the nearest point has a valid limit. Unknowns participate.
        val alternatives = candidates.filter { it.second <= best.second + max(5.0,fix.accuracy) }
        if (alternatives.any { it.first.group != best.first.group || it.first.speed != best.first.speed }) return Result("ambiguous")
        if (alternatives.any { it.first.updatedAt == null || nowUtc - it.first.updatedAt!! !in 0..90L*86400000 }) return Result("source_stale")
        if (best.first.speed == null) return Result("limit_unknown")
        return Result("matched",best.first,fix)
    }

    private fun coordinates(p: Point) = p.lat.isFinite() && p.lon.isFinite() && abs(p.lat)<=90 && abs(p.lon)<=180
    private fun angle(a: Double,b: Double): Double { val delta=abs(a-b)%360; return min(delta,360-delta) }
    private fun distance(a: Point,b: Point): Double {
        val x=(b.lon-a.lon)*PI/180*cos((a.lat+b.lat)*PI/360)
        val y=(b.lat-a.lat)*PI/180
        return hypot(x,y)*6371000
    }
    private fun heading(a: Point,b: Point): Double {
        val aLat=a.lat*PI/180;val bLat=b.lat*PI/180;val d=(b.lon-a.lon)*PI/180
        return (atan2(sin(d)*cos(bLat),cos(aLat)*sin(bLat)-sin(aLat)*cos(bLat)*cos(d))*180/PI+360)%360
    }
    private fun distanceToSegment(p: Point,a: Point,b: Point): Double? {
        val scale=6371000*PI/180;val xScale=scale*cos(p.lat*PI/180)
        val ax=(a.lon-p.lon)*xScale;val ay=(a.lat-p.lat)*scale
        val dx=(b.lon-a.lon)*xScale;val dy=(b.lat-a.lat)*scale
        val t=-(ax*dx+ay*dy)/(dx*dx+dy*dy)
        if (t !in 0.0..1.0) return null
        return hypot(ax+t*dx,ay+t*dy)
    }
    private fun timestamp(value: String): Long? {
        // RFC3339 offsets and optional fractional seconds, available on API 21.
        if (!Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})").matches(value)) return null
        val normalized=value.replace(Regex("\\.\\d+(?=Z|[+-])"),"")
            .replace(Regex("Z$"),"+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"),"$1$2")
        return runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ",Locale.US).apply { isLenient=false }.parse(normalized)?.time }.getOrNull()
    }
}
