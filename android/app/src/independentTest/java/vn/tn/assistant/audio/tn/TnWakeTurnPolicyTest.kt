package vn.tn.assistant.audio.tn

import org.junit.Assert.assertEquals
import org.junit.Test

class TnWakeTurnPolicyTest {
    @Test
    fun processingRequiresAnActiveConsentedSessionAndCurrentMicrophonePermission() {
        assertEquals(true, TnWakeTurnPolicy.canProcess(sessionActive = true, microphonePermissionGranted = true))
        assertEquals(false, TnWakeTurnPolicy.canProcess(sessionActive = true, microphonePermissionGranted = false))
        assertEquals(false, TnWakeTurnPolicy.canProcess(sessionActive = false, microphonePermissionGranted = true))
        assertEquals(false, TnWakeTurnPolicy.canProcess(sessionActive = false, microphonePermissionGranted = false))
    }
}
