package vn.tn.assistant.audio.tn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test fun aMicrophoneTapAcceptsCommandsWithoutAnAcousticWakeAndPreservesTheirWords() {
        assertEquals("mở bản đồ", WakePhrase.commandFromMicrophoneTap("  mở bản đồ  "))
        assertEquals("mở bản đồ", WakePhrase.commandFromMicrophoneTap("TN ơi, mở bản đồ"))
        assertEquals("mở bản đồ", WakePhrase.commandFromMicrophoneTap("Ti en ơi! mở bản đồ"))
        assertEquals("Tên của bạn là gì", WakePhrase.commandFromMicrophoneTap("Tên của bạn là gì"))
        assertEquals("nhắc TN ơi", WakePhrase.commandFromMicrophoneTap("nhắc TN ơi"))
        assertEquals("", WakePhrase.commandFromMicrophoneTap("TN ơi"))
        assertEquals("", WakePhrase.commandFromMicrophoneTap("   "))
        assertFalse("Explicit tap handling must not broaden the acoustic wake detector", WakePhrase.matches("mở bản đồ"))
        org.junit.Assert.assertNull(WakePhrase.commandAfterConfirmedWake("mở bản đồ"))
    }
    @org.junit.Test fun realAsrAbbreviationIsAcceptedOnlyAfterLocalConfirmation() {
        org.junit.Assert.assertEquals("mở bản đồ", WakePhrase.commandAfterConfirmedWake("N ơi mở bản đồ"))
        org.junit.Assert.assertFalse(WakePhrase.matches("N ơi mở bản đồ"))
        org.junit.Assert.assertEquals("mở bản đồ", WakePhrase.commandAfterConfirmedWake("Ti en ơi, mở bản đồ"))
        org.junit.Assert.assertNull(WakePhrase.commandAfterConfirmedWake("Tên của bạn là gì"))
        org.junit.Assert.assertNull(WakePhrase.commandAfterConfirmedWake("Tôi nhắc đến TN ơi"))
    }
    @Test
    fun recognizesVietnameseWakePhraseVariantsAtTheBeginning() {
        assertTrue(WakePhrase.matches("Ti en ơi"))
        assertTrue(WakePhrase.matches("Tê en ơi, mở bản đồ"))
        assertTrue(WakePhrase.matches("TN ơi"))
        assertTrue(WakePhrase.matches("Tê nờ ơi, mở bản đồ"))
        assertTrue(WakePhrase.matches("Tê nơ ơi"))
        assertTrue(WakePhrase.matches("Tê nở ơi"))
        assertFalse(WakePhrase.matches("bạn gọi TN ơi giúp tôi"))
    }

    @Test
    fun removesOnlyTheLeadingWakePhrase() {
        assertEquals("mở bản đồ", WakePhrase.strip("Ti en ơi, mở bản đồ"))
        assertEquals("bật điều hòa", WakePhrase.strip("tê en ơi! bật điều hòa"))
        assertEquals("nhắc TN ơi", WakePhrase.strip("nhắc TN ơi"))
    }
}
