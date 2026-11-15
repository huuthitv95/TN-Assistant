package vn.tn.assistant.driving

import org.junit.Assert.*
import org.junit.Test

class TnForegroundMapStateTest {
    private val state=TnForegroundMapState()
    private val maps="com.google.android.apps.maps"
    private fun event(type:Int,pkg:String=maps,activity:String="Main")=state.accept(TnForegroundMapState.Event(type,pkg,activity))
    @Test fun onlyResumedSupportedMapIsVisible() {
        assertFalse(state.isMap())
        event(1);assertTrue(state.isMap())
        event(1,"com.android.settings");assertFalse(state.isMap())
        event(1,"com.google.android.apps.maps.fake");assertFalse(state.isMap())
        for(pkg in TnForegroundMapState.PACKAGES) {event(1,pkg);assertTrue(state.isMap())}
    }
    @Test fun latePauseOfPreviousActivityCannotHideNewMapActivity() {
        event(1);event(1,activity="Navigation");event(2);event(23)
        assertTrue(state.isMap())
        event(2,activity="Navigation");assertFalse(state.isMap())
    }
    @Test fun screenLockAndDeviceRestartDiscardForegroundState() {
        for(type in listOf(16,17,26,27)) {event(1);event(type,"");assertFalse(state.isMap())}
        event(1);state.reset();assertFalse(state.isMap())
        event(2);assertFalse(state.isMap())
    }
    @Test fun unrelatedEventsNeverResurrectAnApp() {
        event(1);event(23);event(7);event(15);event(18)
        assertFalse(state.isMap())
    }
}
