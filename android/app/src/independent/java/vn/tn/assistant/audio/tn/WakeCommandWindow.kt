package vn.tn.assistant.audio.tn

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/** Bounded PCM window. Audio leaves this buffer only after a real wake decision or its active conversation. */
internal class WakeCommandWindow {
    enum class Phase { ARMED, CAPTURING, FOLLOW_UP, PROCESSING }
    data class Utterance(val pcm: ByteArray, val requiresWakePrefix: Boolean)
    var phase = Phase.ARMED
        private set
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes = 0
    private val audio = ByteArrayOutputStream(MAX_BYTES)
    private var requiresWake = true
    private var deadline = 0L
    val waitingForWake get() = phase == Phase.ARMED

    fun accept(samples: ShortArray, count: Int, woke: Boolean, speech: Boolean, ended: Boolean, now: Long): Utterance? {
        require(count in 0..samples.size)
        if (phase == Phase.PROCESSING) return null
        if (phase == Phase.FOLLOW_UP && now >= deadline) reset()
        val bytes = ByteArray(count * 2)
        for (index in 0 until count) {
            bytes[index * 2] = samples[index].toByte()
            bytes[index * 2 + 1] = (samples[index].toInt() ushr 8).toByte()
        }
        if (phase == Phase.ARMED || phase == Phase.FOLLOW_UP) {
            preRoll.addLast(bytes); preRollBytes += bytes.size
            val limit = if (phase == Phase.ARMED) PRE_ROLL_BYTES else 9600
            while (preRollBytes > limit && preRoll.isNotEmpty()) preRollBytes -= preRoll.removeFirst().size
            val trigger = (phase == Phase.ARMED && woke) || (phase == Phase.FOLLOW_UP && speech)
            if (!trigger) {
                if (ended) { preRoll.clear(); preRollBytes = 0 }
                return null
            }
            requiresWake = phase == Phase.ARMED
            phase = Phase.CAPTURING
            deadline = now + MAX_CAPTURE_MS
            audio.reset()
            preRoll.forEach { audio.write(it) }
            preRoll.clear(); preRollBytes = 0
            return null
        }
        val remaining = MAX_BYTES - audio.size()
        audio.write(bytes, 0, minOf(bytes.size, remaining))
        if (!ended && now < deadline && audio.size() < MAX_BYTES) return null
        phase = Phase.PROCESSING
        return Utterance(audio.toByteArray(), requiresWake).also { audio.reset() }
    }

    fun followUp(now: Long, idleMillis: Int) {
        require(idleMillis in 5000..60000)
        audio.reset(); preRoll.clear(); preRollBytes = 0
        phase = Phase.FOLLOW_UP
        deadline = now + idleMillis
    }
    fun reset() {
        phase = Phase.ARMED; requiresWake = true; deadline = 0
        audio.reset(); preRoll.clear(); preRollBytes = 0
    }
    companion object {
        const val MAX_BYTES = 16000 * 2 * 15
        const val PRE_ROLL_BYTES = 16000 * 2 * 2
        private const val MAX_CAPTURE_MS = 13000L // Includes up to two seconds before the detector fires.
    }
}
