package vn.tn.assistant.driving

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TnRoadMatcherTest {
    private val now=1800000000000L
    private val fix=TnRoadMatcher.Fix(10.0,106.0,5.0,0.0,15.0,10000)
    private val road=TnRoadMatcher.Road("north-1","north",TnRoadMatcher.Point(10.0,106.0),0.0,
        TnRoadMatcher.Point(9.9998,106.0),TnRoadMatcher.Point(10.0002,106.0),50.0,now-10000,"Road")
    private fun match(roads: List<TnRoadMatcher.Road>, location: TnRoadMatcher.Fix=fix,
        complete: Boolean=true, received: Long=10000)=TnRoadMatcher.match(location,TnRoadMatcher.Snapshot(roads,complete,received),10000,now)

    @Test fun matchesOnlyTheDirectedSegmentContainingTheFix() {
        assertEquals("matched",match(listOf(road)).state)
        assertEquals("no_matching_road",match(listOf(road),fix.copy(bearing=180.0)).state)
        assertEquals("no_matching_road",match(listOf(road),fix.copy(lat=10.0003)).state)
        assertEquals("no_matching_road",match(listOf(road),fix.copy(lon=106.001)).state)
    }
    @Test fun uncertainFixOrStationaryDirectionCannotClaimALimit() {
        for (f in listOf(fix.copy(accuracy=100.0),fix.copy(accuracy=Double.NaN),fix.copy(elapsedMs=-1),fix.copy(elapsedMs=10001)))
            assertEquals("location_unreliable",match(listOf(road),f).state)
        for (f in listOf(fix.copy(bearing=null),fix.copy(speedMps=0.0),fix.copy(bearing=360.0)))
            assertEquals("direction_unavailable",match(listOf(road),f).state)
    }
    @Test fun nearbyUnknownRoadCannotBeDiscardedInFavorOfKnownSpeed() {
        assertEquals("ambiguous",match(listOf(road,road.copy(id="unknown",group="parallel",speed=null))).state)
        assertEquals("limit_unknown",match(listOf(road.copy(speed=null))).state)
        assertEquals("ambiguous",match(listOf(road,road.copy(speed=70.0))).state)
        assertEquals("matched",match(listOf(road,road.copy(id="same-road-sample"))).state)
    }
    @Test fun incompleteStaleAndPointOnlyDataRemainExplicit() {
        assertEquals("data_unavailable",match(listOf(road),complete=false).state)
        assertEquals("data_unavailable",match(listOf(road),received=-10000).state)
        assertEquals("source_stale",match(listOf(road.copy(updatedAt=now-91L*86400000))).state)
        assertEquals("source_stale",match(listOf(road.copy(updatedAt=now+1000))).state)
        assertEquals("geometry_missing",match(listOf(road.copy(start=null,end=null))).state)
        assertEquals("geometry_missing",match(listOf(road.copy(anchor=TnRoadMatcher.Point(11.0,106.0)))).state)
    }
    @Test fun parserRejectsMalformedOrLegacyEnvelopeInsteadOfInventingGeometry() {
        assertFalse(TnRoadMatcher.parse(JSONObject("{\"data\":[]}"),0).complete)
        assertFalse(TnRoadMatcher.parse(JSONObject("{\"road_candidates_version\":1,\"road_candidates_complete\":true,\"road_candidates\":[{}]}"),0).complete)
        assertTrue(TnRoadMatcher.parse(JSONObject("{\"road_candidates_version\":1,\"road_candidates_complete\":true,\"road_candidates\":[]}"),0).complete)
    }

    @Test fun aRecentResponseCannotCoverTravelOutsideItsQueryWindow() {
        val response=TnRoadMatcher.Snapshot(listOf(road),true,10000,TnRoadMatcher.Point(10.001,106.0),150.0)
        assertEquals("query_outdated",TnRoadMatcher.match(fix,response,10000,now).state)
        assertEquals("matched",TnRoadMatcher.match(fix,response.copy(center=TnRoadMatcher.Point(10.0,106.0)),10000,now).state)
    }

    @Test fun parsesUtcAndOffsetDatesWithoutApi24DatePatterns() {
        fun date(value: String): Long? {
            val row=JSONObject().put("id","road").put("latitude",10.0).put("longitude",106.0)
                .put("bearing",0).put("source_updated_at",value)
            val response=JSONObject().put("road_candidates_version",1).put("road_candidates_complete",true)
                .put("road_candidates",org.json.JSONArray().put(row))
            return TnRoadMatcher.parse(response,0).roads.single().updatedAt
        }
        val utc=date("2026-11-13T00:00:00Z")
        assertNotNull(utc)
        assertEquals(utc,date("2026-11-13T07:00:00+07:00"))
        assertEquals(utc,date("2026-11-12T19:00:00-05:00"))
        assertEquals(utc,date("2026-11-13T00:00:00.123456Z"))
        assertNull(date("2026-02-30T00:00:00Z"))
        assertNull(date("2026-11-13T00:00:00Zgarbage"))
    }
}
