package vn.tn.assistant.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks nested, idempotently releasable pauses held while TN owns the microphone.
 *
 * A returned read is admitted through [withProcessing]. If output requests a
 * pause after admission, that lease stays pending until the processing section
 * ends. This avoids blocking the UI thread while still making pause activation
 * a boundary: no lease becomes active in the middle of wake/VAD/endpoint or
 * utterance processing.
 */
internal class TnWakeOutputPauseLeases {
    private enum class LeaseState { PENDING, ACTIVE, CLOSED }

    private val monitor = Any()
    private val pendingLeases = LinkedHashSet<Lease>()
    private var activeLeases = 0
    private var processing = false

    val activeCount: Int
        get() = synchronized(monitor) { activeLeases }

    fun acquire(): AutoCloseable = synchronized(monitor) {
        Lease().also { lease ->
            if (processing) {
                lease.state = LeaseState.PENDING
                pendingLeases += lease
            } else {
                lease.state = LeaseState.ACTIVE
                activeLeases++
            }
        }
    }

    /** Runs one returned-read section, or rejects it when a pause is active/pending. */
    fun withProcessing(block: () -> Unit): Boolean {
        synchronized(monitor) {
            if (processing || pendingLeases.isNotEmpty() ||
                TnWakeReadPausePolicy.discardReturnedBlock(activeLeases)
            ) return false
            processing = true
        }
        return try {
            block()
            true
        } finally {
            synchronized(monitor) {
                processing = false
                pendingLeases.forEach {
                    it.state = LeaseState.ACTIVE
                    activeLeases++
                }
                pendingLeases.clear()
            }
        }
    }

    private inner class Lease : AutoCloseable {
        var state = LeaseState.CLOSED

        override fun close() {
            synchronized(monitor) {
                when (state) {
                    LeaseState.PENDING -> pendingLeases.remove(this)
                    LeaseState.ACTIVE -> activeLeases--
                    LeaseState.CLOSED -> return
                }
                state = LeaseState.CLOSED
            }
        }
    }
}

/** Pure lease-count policy used while the lease monitor is held. */
internal object TnWakeReadPausePolicy {
    fun discardReturnedBlock(activePauseCount: Int): Boolean = activePauseCount > 0
}
