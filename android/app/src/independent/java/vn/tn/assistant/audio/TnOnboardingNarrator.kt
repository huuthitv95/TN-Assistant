package vn.tn.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Packaged TN guidance works before authentication and without microphone/network access. */
class TnOnboardingNarrator(private val context: Context) {
    private val policy = SetupNarrationPolicy()
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var generation = 0L
    private var cue: String? = null
    private var foreground = false
    private var closed = false
    var onState: ((String) -> Unit)? = null
    val muted get() = policy.muted

    fun show(next: String, visible: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        cue = next
        foreground = visible
        if (closed || !policy.enter(next, visible)) return
        stopPlayer()
        val id = generation
        val resource = context.resources.getIdentifier("tn_setup_$next", "raw", context.packageName)
        if (resource == 0 || !requestFocus(id)) { stopPlayer(); onState?.invoke("unavailable"); return }
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(attributes)
            context.resources.openRawResourceFd(resource).use { media.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            media.setOnPreparedListener {
                if (!closed && foreground && generation == id && player === it) {
                    it.start()
                    onState?.invoke("playing")
                }
            }
            media.setOnCompletionListener { if (generation == id && player === it) { stopPlayer(); onState?.invoke("completed") } }
            media.setOnErrorListener { p, _, _ -> if (generation == id && player === p) { stopPlayer(); onState?.invoke("unavailable") }; true }
            media.prepareAsync()
            main.postDelayed({ if (generation == id && player === media) { stopPlayer(); onState?.invoke("stopped") } }, 30000)
        } catch (_: Exception) { stopPlayer(); onState?.invoke("unavailable") }
    }

    fun interrupt() { stopPlayer(); onState?.invoke("stopped") }
    fun pause() { foreground = false; policy.leave(); interrupt() }
    fun replay() { val current = cue ?: return; policy.replay(); show(current, foreground) }
    fun setMuted(value: Boolean) {
        if (policy.muted == value) return
        policy.setMuted(value)
        if (value) interrupt() else replay()
    }
    fun close() { closed = true; pause(); main.removeCallbacksAndMessages(null); onState = null }

    private fun stopPlayer() {
        generation++
        main.removeCallbacksAndMessages(null)
        player?.let { player = null; try { it.release() } catch (_: Exception) {} }
        try {
            if (Build.VERSION.SDK_INT >= 26) focus?.let(manager::abandonAudioFocusRequest)
            else focusListener?.let { @Suppress("DEPRECATION") manager.abandonAudioFocus(it) }
        } catch (_: Exception) {}
        focus = null
        focusListener = null
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(id: Long): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            main.post { if (generation == id && change < 0) interrupt() }
        }
        focusListener = listener
        return try {
            if (Build.VERSION.SDK_INT >= 26) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(listener, main).build()
                focus = request
                manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) { false }
    }
}
