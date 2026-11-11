package vn.tn.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/** A single speech playback's focus lease. The caller closes it on every terminal playback path. */
class TnSpeechAudioFocus internal constructor(
    private val platform: Platform,
    private val advancedPlayback: Boolean,
    private val onPermanentLoss: () -> Unit,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : AutoCloseable {
    constructor(context: Context, advancedPlayback: Boolean, onPermanentLoss: () -> Unit) : this(
        AndroidPlatform(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager),
        advancedPlayback,
        onPermanentLoss,
    )

    private var active = false
    private var closed = false
    private var permanentLossDelivered = false
    private var request: AudioFocusRequest? = null

    /** Null until abandonment is attempted; false also records an exception from the platform. */
    var lastAbandonSucceeded: Boolean? = null
        private set

    internal val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        val notify = synchronized(this) {
            if (change == AudioManager.AUDIOFOCUS_LOSS && active && !closed && !permanentLossDelivered) {
                permanentLossDelivered = true
                true
            } else false
        }
        // The source profile does not duck or stop for transient focus-loss callbacks.
        if (notify) onPermanentLoss()
    }

    @Synchronized
    fun request(): Boolean {
        if (closed) return false
        if (active) return true
        active = try {
            val result = if (usesModernFocusApi()) {
                val next = createRequest(advancedPlayback, focusChangeListener)
                request = next
                platform.request(next)
            } else {
                platform.requestLegacy(focusChangeListener, focusGain(advancedPlayback))
            }
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
            false
        }
        return active
    }

    /** Idempotent release, preserving the actual platform result rather than assuming success. */
    @Synchronized
    fun abandon(): Boolean {
        if (closed) return lastAbandonSucceeded ?: true
        closed = true
        if (!active) return true
        active = false
        val succeeded = try {
            val result = if (usesModernFocusApi()) {
                val held = request ?: return false.also { lastAbandonSucceeded = it }
                platform.abandon(held)
            } else {
                platform.abandonLegacy(focusChangeListener)
            }
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
            false
        }
        request = null
        lastAbandonSucceeded = succeeded
        return succeeded
    }

    override fun close() {
        abandon()
    }

    @ChecksSdkIntAtLeast(api = 27)
    private fun usesModernFocusApi() = sdkInt > Build.VERSION_CODES.O

    internal interface Platform {
        @RequiresApi(26)
        fun request(request: AudioFocusRequest): Int
        @RequiresApi(26)
        fun abandon(request: AudioFocusRequest): Int
        fun requestLegacy(listener: AudioManager.OnAudioFocusChangeListener, gain: Int): Int
        fun abandonLegacy(listener: AudioManager.OnAudioFocusChangeListener): Int
    }

    private class AndroidPlatform(private val manager: AudioManager) : Platform {
        @RequiresApi(26)
        override fun request(request: AudioFocusRequest) = manager.requestAudioFocus(request)
        @RequiresApi(26)
        override fun abandon(request: AudioFocusRequest) = manager.abandonAudioFocusRequest(request)

        @Suppress("DEPRECATION")
        override fun requestLegacy(listener: AudioManager.OnAudioFocusChangeListener, gain: Int) =
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gain)

        @Suppress("DEPRECATION")
        override fun abandonLegacy(listener: AudioManager.OnAudioFocusChangeListener) =
            manager.abandonAudioFocus(listener)
    }

    internal companion object {
        fun focusGain(advancedPlayback: Boolean): Int = if (advancedPlayback) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        }

        @Suppress("DEPRECATION")
        fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        @RequiresApi(26)
        fun createRequest(
            advancedPlayback: Boolean,
            listener: AudioManager.OnAudioFocusChangeListener,
        ): AudioFocusRequest = AudioFocusRequest.Builder(focusGain(advancedPlayback))
            .setAudioAttributes(audioAttributes())
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
    }
}
