package vn.tn.assistant.audio

import java.util.concurrent.atomic.AtomicBoolean

/** Cancellation stops work; only the owning capture's cleanup makes the microphone slot reusable. */
class TnRecordingSessionGate {
    private var owner: Capture? = null

    @Synchronized
    fun begin(): Capture? {
        if (owner != null) return null
        return Capture(this).also { owner = it }
    }

    @Synchronized
    fun cancel() { owner?.cancel() }

    val isActive: Boolean
        @Synchronized get() = owner?.isActive == true

    @Synchronized
    private fun release(capture: Capture) {
        if (owner === capture) owner = null
    }

    class Capture internal constructor(private val gate: TnRecordingSessionGate) : AutoCloseable {
        private val cancelled = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        val isActive: Boolean get() = !closed.get() && !cancelled.get()

        internal fun cancel() { cancelled.set(true) }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            cancelled.set(true)
            gate.release(this)
        }
    }

    companion object { val process = TnRecordingSessionGate() }
}
