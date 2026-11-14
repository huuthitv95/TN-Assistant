package vn.tn.assistant.driving

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import vn.tn.assistant.audio.TnSpeechAudioFocus

/** Packaged road prompts: no network, speech synthesis or playback queue during a trip. */
internal class TnRoadAlertAudio(private val context:Context,private val unavailable:()->Unit={}):AutoCloseable {
    private val main=Handler(Looper.getMainLooper())
    private val sound=TnRoadSound(context)
    private var media:MediaPlayer?=null
    private var focus:TnSpeechAudioFocus?=null
    private var selection=""
    private var generation=0L
    private var closed=false
    internal var playingVoice=false
        private set

    fun retain(overThreshold:Boolean,mode:String,accent:String) {
        val next="$mode|$accent"
        if(!overThreshold || selection!=next || mode !in setOf("sound","voice")) stop()
        selection=next
    }

    fun play(mode:String,accent:String):Boolean {
        if(closed) return false
        if(mode=="sound") return sound.play()
        if(mode!="voice" || accent !in setOf("south","north") || media!=null) return false
        val resource=context.resources.getIdentifier("tn_road_overspeed_$accent","raw",context.packageName)
        if(resource==0) return false
        val lease=TnSpeechAudioFocus(context,true) {stop()}
        if(!lease.request()) {lease.close();return false}
        focus=lease
        val revision=++generation
        val deadline=SystemClock.elapsedRealtime()+2000
        return try {
            val player=MediaPlayer();media=player
            player.setAudioAttributes(TnSpeechAudioFocus.audioAttributes())
            context.resources.openRawResourceFd(resource).use {player.setDataSource(it.fileDescriptor,it.startOffset,it.length)}
            player.setOnPreparedListener {
                if(!closed && generation==revision && media===it) {
                    if(SystemClock.elapsedRealtime()>deadline) {stop();unavailable()}
                    else try {it.start();playingVoice=true} catch(_:RuntimeException) {stop();unavailable()}
                }
            }
            player.setOnCompletionListener {if(generation==revision && media===it) stop()}
            player.setOnErrorListener {p,_,_->
                if(generation==revision && media===p) {stop();unavailable()};true
            }
            player.prepareAsync()
            main.postDelayed({if(generation==revision && !playingVoice) {stop();unavailable()}},2000)
            main.postDelayed({if(generation==revision) {stop();unavailable()}},10000)
            true
        } catch(_:Exception) {stop();false}
    }

    fun stop() {
        generation++;main.removeCallbacksAndMessages(null);playingVoice=false
        media?.let {runCatching {it.release()}};media=null
        focus?.close();focus=null;sound.stop()
    }
    override fun close() {closed=true;stop();sound.close()}
}
