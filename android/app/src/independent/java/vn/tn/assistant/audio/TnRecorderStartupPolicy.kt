package vn.tn.assistant.audio

import java.util.concurrent.atomic.AtomicBoolean

/** First successful recorder start in a process; this policy never selects or changes AudioSource. */
class TnRecorderStartupPolicy {
    private val firstStartConsumed = AtomicBoolean(false)

    /** Call after startRecording succeeds, before reading or emitting any samples. */
    fun requiresWarmRestartAfterStart(advancedRecording: Boolean): Boolean =
        firstStartConsumed.compareAndSet(false, true) && advancedRecording

    /** Returns only the usable recorder; a warm-up recorder must never emit samples. */
    fun <T : Any> start(
        advancedRecording: Boolean,
        create: () -> T,
        begin: (T) -> Unit,
        discard: (T) -> Unit,
        active: () -> Boolean,
        settle: (Long) -> Unit = { Thread.sleep(it) },
    ): T {
        var owned: T? = null
        var returned = false
        try {
            if (!active()) throw InterruptedException("Recording cancelled")
            val first = create().also { owned = it }
            begin(first)
            if (requiresWarmRestartAfterStart(advancedRecording)) {
                owned = null
                discard(first)
                settle(WARM_RESTART_DELAY_MS)
                if (!active()) throw InterruptedException("Recording cancelled")
                val replacement = create().also { owned = it }
                begin(replacement)
            }
            if (!active()) throw InterruptedException("Recording cancelled")
            return requireNotNull(owned).also { returned = true }
        } finally {
            if (!returned) owned?.let(discard)
        }
    }

    companion object {
        /** Shared by foreground/wake recording so the initialization workaround runs at most once. */
        val process = TnRecorderStartupPolicy()
        const val RELEASE_SETTLE_DELAY_MS = 100L
        const val RETRY_DELAY_MS = 30L
        const val WARM_RESTART_DELAY_MS = RELEASE_SETTLE_DELAY_MS + RETRY_DELAY_MS
    }
}
