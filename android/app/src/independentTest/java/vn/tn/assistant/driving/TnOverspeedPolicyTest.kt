package vn.tn.assistant.driving

import org.junit.Assert.*
import org.junit.Test

class TnOverspeedPolicyTest {
    private val road=TnRoadMatcher.Road("a","group",TnRoadMatcher.Point(10.0,106.0),0.0,null,null,50.0,0,"road")
    private fun result(time:Long,kmh:Double=55.0)=TnRoadMatcher.Result("matched",road,
        TnRoadMatcher.Fix(10.0,106.0,5.0,0.0,kmh/3.6,time))
    @Test fun duplicateTimerAndNetworkResultsAreNotNewSamples() {
        val p=TnOverspeedPolicy()
        assertFalse(p.update(result(1000),"sound",0,1000).notify)
        repeat(10) {assertFalse(p.update(result(1000),"sound",0,2000).notify)}
        assertTrue(p.update(result(2000),"sound",0,2000).notify)
        assertFalse(p.update(result(2000),"sound",0,9000).notify)
    }
    @Test fun cooldownSurvivesUnknownDataAndRoadChanges() {
        val p=TnOverspeedPolicy()
        p.update(result(1000),"sound",0,1000)
        assertTrue(p.update(result(2000),"sound",0,2000).notify)
        p.update(TnRoadMatcher.Result("ambiguous"),"sound",0,2500)
        assertFalse(p.update(result(3000),"sound",0,3000).notify)
        assertFalse(p.update(result(4000).copy(road=road.copy(group="next")),"sound",0,4000).notify)
        assertFalse(p.update(result(7000),"sound",0,7000).notify)
        assertTrue(p.update(result(8000),"sound",0,8000).notify)
    }
    @Test fun unknownStaleStationaryAndSilentModesNeverEmitAudio() {
        for(mode in listOf("none","display","invalid")) {
            val p=TnOverspeedPolicy()
            assertFalse(p.update(result(1000),mode,0,1000).notify)
            assertFalse(p.update(result(2000),mode,0,2000).notify)
        }
        val p=TnOverspeedPolicy()
        p.update(result(1000),"sound",0,1000)
        assertFalse(p.update(result(2000).copy(state="source_stale"),"sound",0,2000).overThreshold)
        assertFalse(p.update(result(3000),"sound",0,3000).notify)
        assertFalse(p.update(result(4000,0.0),"sound",0,4000).overThreshold)
        assertFalse(p.update(result(5000),"sound",0,16000).notify)
    }
    @Test fun offsetsAndThresholdEqualityFollowSavedSettings() {
        for(offset in listOf(-5,0,5)) {
            val p=TnOverspeedPolicy()
            val threshold=50.0+offset
            assertFalse(p.update(result(1000,threshold-.1),"sound",offset,1000).overThreshold)
            assertFalse(p.update(result(2000,threshold),"sound",offset,2000).notify)
            assertTrue(p.update(result(3000,threshold),"sound",offset,3000).notify)
        }
    }
    @Test fun newLimitAndSessionResetRequireNewConfirmation() {
        val p=TnOverspeedPolicy()
        p.update(result(1000),"sound",0,1000)
        assertFalse(p.update(result(2000).copy(road=road.copy(speed=40.0)),"sound",0,2000).notify)
        p.reset()
        assertFalse(p.update(result(3000),"sound",0,3000).notify)
        assertTrue(p.update(result(4000),"sound",0,4000).notify)
    }
}
