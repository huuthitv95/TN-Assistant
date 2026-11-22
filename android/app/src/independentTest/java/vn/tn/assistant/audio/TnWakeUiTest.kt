package vn.tn.assistant.audio

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TnWakeUiTest {
    private class RecordingListener : TnWakeUi.Listener {
        var states = 0
        var transcripts = 0
        var launches = 0
        var confirmations = 0

        override fun state(message: String, listening: Boolean) {
            states++
        }

        override fun transcript(text: String) {
            transcripts++
        }

        override fun launch(intent: Intent): Boolean {
            launches++
            return true
        }

        override fun confirm(): Boolean {
            confirmations++
            return true
        }
    }

    @Test
    fun callbackFromBeforeDetachIsIgnored() {
        val listener = RecordingListener()
        TnWakeUi.attach(listener, owner = 11L)
        val token = TnWakeUi.currentToken()

        TnWakeUi.detach(listener)
        TnWakeUi.state("stale", false, owner = 11L, token = token)
        TnWakeUi.transcript("stale", owner = 11L, token = token)

        assertEquals(0, listener.states)
        assertEquals(0, listener.transcripts)
    }

    @Test
    fun oldTokenIsIgnoredAfterSameOwnerReattaches() {
        val oldListener = RecordingListener()
        val newListener = RecordingListener()
        TnWakeUi.attach(oldListener, owner = 11L)
        val oldToken = TnWakeUi.currentToken()

        TnWakeUi.attach(newListener, owner = 11L)
        val newToken = TnWakeUi.currentToken()
        assertFalse(oldToken == newToken)

        TnWakeUi.state("stale", false, owner = 11L, token = oldToken)
        TnWakeUi.state("current", true, owner = 11L, token = newToken)

        assertEquals(0, oldListener.states)
        assertEquals(1, newListener.states)
    }

    @Test
    fun ownerMismatchIsIgnoredEvenWithCurrentToken() {
        val listener = RecordingListener()
        TnWakeUi.attach(listener, owner = 11L)
        val token = TnWakeUi.currentToken()

        TnWakeUi.state("wrong owner", false, owner = 22L, token = token)
        TnWakeUi.transcript("wrong owner", owner = 22L, token = token)
        TnWakeUi.launch(Intent("test"), owner = 22L, token = token)
        TnWakeUi.confirm(owner = 22L, token = token)

        assertEquals(0, listener.states)
        assertEquals(0, listener.transcripts)
        assertEquals(0, listener.launches)
        assertEquals(0, listener.confirmations)
    }

    @Test
    fun currentOwnerAndTokenReachEveryCallback() {
        val listener = RecordingListener()
        TnWakeUi.attach(listener, owner = 11L)
        val token = TnWakeUi.currentToken()

        TnWakeUi.state("current", true, owner = 11L, token = token)
        TnWakeUi.transcript("current", owner = 11L, token = token)
        assertTrue(TnWakeUi.launch(Intent("test"), owner = 11L, token = token))
        assertTrue(TnWakeUi.confirm(owner = 11L, token = token))

        assertEquals(1, listener.states)
        assertEquals(1, listener.transcripts)
        assertEquals(1, listener.launches)
        assertEquals(1, listener.confirmations)
    }
}
