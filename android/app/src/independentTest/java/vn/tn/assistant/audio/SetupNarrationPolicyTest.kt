package vn.tn.assistant.audio

import org.junit.Assert.*
import org.junit.Test

class SetupNarrationPolicyTest {
    @Test fun redrawDoesNotRepeatButNewPageAndReturnDo() {
        val policy = SetupNarrationPolicy()
        assertFalse(policy.enter("start", false))
        assertTrue(policy.enter("start", true))
        assertFalse(policy.enter("start", true))
        assertTrue(policy.enter("permission", true))
        policy.leave()
        assertTrue(policy.enter("permission", true))
    }
    @Test fun muteAndReplayRespectForeground() {
        val policy = SetupNarrationPolicy()
        policy.setMuted(true)
        assertFalse(policy.enter("login", true))
        policy.setMuted(false)
        assertTrue(policy.enter("login", true))
        policy.replay()
        assertFalse(policy.enter("login", false))
        assertTrue(policy.enter("login", true))
    }
}
