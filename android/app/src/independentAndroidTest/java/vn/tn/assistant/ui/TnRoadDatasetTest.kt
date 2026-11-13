package vn.tn.assistant.ui

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import vn.tn.assistant.driving.TnRoadMatcher
import java.io.File

/** Opt-in replay of a real, locally validated API response; no provider calls. */
class TnRoadDatasetTest {
    @Test fun realManagedResponseMatchesTheReviewedDirectedRoad() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("roadDataset") == "hcm-pilot")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val input=JSONObject(File(context.getExternalFilesDir(null),"road-acceptance.json").readText())
        val query=input.getJSONObject("query")
        val response=input.getJSONObject("response")
        assertEquals(150.0,response.getDouble("radius_m"),0.0)
        val snapshot=TnRoadMatcher.parse(response,10000).copy(
            center=TnRoadMatcher.Point(query.getDouble("lat"),query.getDouble("lon")),radius=150.0)
        assertTrue(snapshot.complete)
        val fix=TnRoadMatcher.Fix(query.getDouble("lat"),query.getDouble("lon"),5.0,
            query.getDouble("bearing"),15.0,10000)
        val result=TnRoadMatcher.match(fix,snapshot,10000,System.currentTimeMillis())
        assertEquals("matched",result.state)
        assertEquals("osm-way-605133464-4-0-backward",result.road?.id)
        assertEquals(30.0,result.road!!.speed!!,0.0)
        assertEquals("location_unreliable",TnRoadMatcher.match(fix.copy(accuracy=100.0),snapshot,10000,System.currentTimeMillis()).state)
        assertEquals("data_unavailable",TnRoadMatcher.match(fix,snapshot.copy(complete=false),10000,System.currentTimeMillis()).state)
    }
}
