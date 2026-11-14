package vn.tn.assistant.driving

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import vn.tn.assistant.audio.TnSpeechAudioFocus

/** Local sound mode only: no synthesis, no quota, no delayed alerts after close. */
internal class TnRoadSound(private val context:Context):AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private var focus:TnSpeechAudioFocus?=null
    private var tone:ToneGenerator?=null
    private var closed=false
    fun play():Boolean {
        if(closed || tone!=null) return false
        val lease=TnSpeechAudioFocus(context,true) {stop()}
        if(!lease.request()) {lease.close();return false}
        focus=lease
        return try {
            val player=ToneGenerator(AudioManager.STREAM_MUSIC,60)
            tone=player
            if(!player.startTone(ToneGenerator.TONE_PROP_BEEP,250)) {stop();return false}
            main.postDelayed({
                if(!closed && tone===player) {
                    try {if(!player.startTone(ToneGenerator.TONE_PROP_BEEP,250)) stop()}
                    catch(_:RuntimeException) {stop()}
                }
            },500)
            main.postDelayed({stop()},800)
            true
        } catch(_:RuntimeException) {stop();false}
    }
    fun stop() {
        main.removeCallbacksAndMessages(null)
        tone?.let {runCatching {it.stopTone()};runCatching {it.release()}};tone=null
        focus?.close();focus=null
    }
    override fun close() {closed=true;stop()}
}
