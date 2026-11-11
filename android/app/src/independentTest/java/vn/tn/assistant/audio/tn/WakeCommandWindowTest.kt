package vn.tn.assistant.audio.tn

import org.junit.Assert.*
import org.junit.Test

class WakeCommandWindowTest {
    @Test fun idleAudioNeverProducesAnUploadAndOnlyKeepsBoundedPreRoll() {
        val window = WakeCommandWindow()
        val samples = ShortArray(512) { 7 }
        repeat(4000) { assertNull(window.accept(samples, samples.size, false, true, false, it * 32L)) }
        assertTrue(window.waitingForWake)
        window.accept(samples, samples.size, true, true, false, 130000)
        val result = requireNotNull(window.accept(samples, samples.size, false, false, true, 130032))
        assertTrue(result.requiresWakePrefix)
        assertTrue(result.pcm.size <= WakeCommandWindow.PRE_ROLL_BYTES + 1024)
    }

    @Test fun sameSentenceCommandIsRetainedAndTurnCanOnlyBeEmittedOnce() {
        val window = WakeCommandWindow()
        val lead = shortArrayOf(1234, -1234)
        val tail = shortArrayOf(3210, -3210)
        window.accept(lead, 2, false, true, false, 0)
        window.accept(lead, 2, true, true, false, 1)
        val utterance = requireNotNull(window.accept(tail, 2, false, false, true, 2))
        assertArrayEquals(byteArrayOf(-46, 4, 46, -5, -46, 4, 46, -5, -118, 12, 118, -13), utterance.pcm)
        assertNull(window.accept(tail, 2, true, true, true, 3))
        assertEquals(WakeCommandWindow.Phase.PROCESSING, window.phase)
    }

    @Test fun captureNeverExceedsFifteenSecondsEvenWithoutVadEndpoint() {
        val window = WakeCommandWindow()
        val frame = ShortArray(512) { 9 }
        window.accept(frame, 512, true, true, false, 0)
        var emitted: WakeCommandWindow.Utterance? = null
        for (index in 1..1000) {
            val next = window.accept(frame, 512, false, true, false, index.toLong())
            if (next != null) { emitted = next; break }
        }
        assertNotNull(emitted)
        assertEquals(WakeCommandWindow.MAX_BYTES, emitted!!.pcm.size)
    }

    @Test fun conversationTimeoutRearmsWakeBeforeAcceptingAnyMoreSpeech() {
        val window = WakeCommandWindow()
        window.followUp(0, 5000)
        assertNull(window.accept(ShortArray(512), 512, false, false, false, 2000))
        assertNull(window.accept(ShortArray(512), 512, false, true, false, 5001))
        assertTrue(window.waitingForWake)
    }

    @Test fun followUpRequiresActualSpeechAndDoesNotUploadSilence() {
        val window = WakeCommandWindow()
        window.followUp(0, 10000)
        repeat(100) { assertNull(window.accept(ShortArray(512), 512, false, false, false, it * 32L)) }
        window.accept(ShortArray(512) { 100 }, 512, false, true, false, 3300)
        val utterance = requireNotNull(window.accept(ShortArray(512), 512, false, false, true, 4000))
        assertFalse(utterance.requiresWakePrefix)
        assertTrue(utterance.pcm.size <= 10624)
    }

    @Test fun cancelDiscardsCapturedAudioAndARegularSentenceCannotResumeIt() {
        val window = WakeCommandWindow()
        window.accept(ShortArray(512) { 5 }, 512, true, true, false, 1)
        window.reset()
        assertNull(window.accept(ShortArray(512), 512, false, true, true, 2))
        assertTrue(window.waitingForWake)
    }
}
