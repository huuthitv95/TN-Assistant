package vn.tn.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TnSpeechAudioFocusRuntimeTest {
    @Test fun bothProfilesUseTheSameSourceAttributesAndDifferentFocusGains() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val listener = AudioManager.OnAudioFocusChangeListener { }
        val normal = TnSpeechAudioFocus.createRequest(false, listener)
        val advanced = TnSpeechAudioFocus.createRequest(true, listener)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, normal.focusGain)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, advanced.focusGain)
        assertEquals(normal.audioAttributes, advanced.audioAttributes)
        // setLegacyStreamType(STREAM_MUSIC) intentionally runs after the content-type setter,
        // matching the source builder order; the framework resolves the final attributes.
        val source = AudioAttributes.Builder().setUsage(1).setContentType(1)
            .setLegacyStreamType(3).setFlags(1).build()
        assertEquals(source, normal.audioAttributes)
        assertEquals(AudioAttributes.USAGE_MEDIA, normal.audioAttributes.usage)
        assertEquals(AudioAttributes.FLAG_AUDIBILITY_ENFORCED,
            normal.audioAttributes.flags and AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
        assertFalse(normal.acceptsDelayedFocusGain())
    }

    @Test fun onlyPermanentLossNotifiesAndCloseAbandonsTheExactRequestOnce() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val platform = RecordingPlatform()
        val losses = AtomicInteger(0)
        val focus = TnSpeechAudioFocus(platform, true, { losses.incrementAndGet() })
        assertTrue(focus.request())
        assertNotNull(platform.requested)
        focus.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focus.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        focus.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, losses.get())
        focus.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        focus.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, losses.get())
        focus.close()
        focus.close()
        assertEquals(1, platform.abandonCalls)
        assertSame(platform.requested, platform.abandoned)
        assertEquals(true, focus.lastAbandonSucceeded)
        focus.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, losses.get())
        assertFalse(focus.request())
    }

    @Test fun deniedFocusDoesNotBecomePlaybackPermissionAndReleaseExceptionIsNotSuccess() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val denied = RecordingPlatform().apply { requestResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED }
        val deniedFocus = TnSpeechAudioFocus(denied, false, { })
        assertFalse(deniedFocus.request())
        deniedFocus.close()
        assertEquals(0, denied.abandonCalls)

        val failing = RecordingPlatform().apply { throwOnAbandon = true }
        val focus = TnSpeechAudioFocus(failing, false, { })
        assertTrue(focus.request())
        assertFalse(focus.abandon())
        assertEquals(false, focus.lastAbandonSucceeded)
        assertFalse(focus.abandon())
        assertEquals(1, failing.abandonCalls)
    }

    @Test fun android26AndOlderUseTheOriginalLegacyMusicFocusPath() {
        for (sdk in listOf(21, 26)) for (advanced in listOf(false, true)) {
            var legacyGain: Int? = null
            var held: AudioManager.OnAudioFocusChangeListener? = null
            var abandons = 0
            val platform = object : TnSpeechAudioFocus.Platform {
                override fun request(request: AudioFocusRequest): Int = error("Source uses legacy focus through API 26")
                override fun abandon(request: AudioFocusRequest): Int = error("No modern request to abandon")
                override fun requestLegacy(listener: AudioManager.OnAudioFocusChangeListener, gain: Int): Int {
                    held = listener
                    legacyGain = gain
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
                override fun abandonLegacy(listener: AudioManager.OnAudioFocusChangeListener): Int {
                    assertSame(held, listener)
                    abandons++
                    return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
            }
            val focus = TnSpeechAudioFocus(platform, advanced, {}, sdk)
            assertTrue(focus.request())
            assertEquals(if (advanced) 2 else 4, legacyGain)
            focus.close(); focus.close()
            assertEquals(1, abandons)
        }
    }

    /** Requires the app to be foreground on recent Android, as production focus acquisition does. */
    @Test fun androidAudioManagerAcceptsAndReleasesEachProfileRequest() {
        assumeTrue(Build.VERSION.SDK_INT >= 26)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = instrumentation.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (advanced in listOf(false, true)) {
            val focus = TnSpeechAudioFocus(instrumentation.targetContext, advanced, { })
            instrumentation.runOnMainSync {
                try {
                    assertTrue("Foreground Android must grant speech focus for profile advanced=$advanced", focus.request())
                    assertTrue("Android must accept abandoning the speech focus request", focus.abandon())
                    assertEquals(true, focus.lastAbandonSucceeded)
                } finally {
                    focus.close()
                }
            }
        }
        assertNotNull(manager)
    }

    private class RecordingPlatform : TnSpeechAudioFocus.Platform {
        var requested: AudioFocusRequest? = null
        var abandoned: AudioFocusRequest? = null
        var requestResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var abandonCalls = 0
        var throwOnAbandon = false

        override fun request(request: AudioFocusRequest): Int {
            requested = request
            return requestResult
        }
        override fun abandon(request: AudioFocusRequest): Int {
            abandonCalls++
            abandoned = request
            if (throwOnAbandon) throw IllegalStateException("Platform release failure")
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        override fun requestLegacy(listener: AudioManager.OnAudioFocusChangeListener, gain: Int): Int =
            error("Unexpected legacy request on this Android")
        override fun abandonLegacy(listener: AudioManager.OnAudioFocusChangeListener): Int =
            error("Unexpected legacy abandon on this Android")
    }
}
