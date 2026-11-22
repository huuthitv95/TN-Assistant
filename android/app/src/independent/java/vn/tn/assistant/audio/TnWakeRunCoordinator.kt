package vn.tn.assistant.audio

/**
 * Serializes microphone-service runs so cleanup from an old login cannot stop a
 * replacement run. A replacement is started only after the old worker releases
 * its recorder and capture lease.
 */
internal class TnWakeRunCoordinator {
    data class Run(val token: Long, val owner: Long)
    data class Request(val run: Run?, val alreadyActive: Boolean)

    private var nextToken = 0L
    private var current: Run? = null
    private var pendingOwner: Long? = null

    @Synchronized
    fun request(owner: Long): Request {
        val existing = current
        if (existing == null) {
            val run = Run(++nextToken, owner)
            current = run
            return Request(run, false)
        }
        // A run reservation is already enough to make a duplicate start
        // idempotent. Do not queue a replacement merely because the worker has
        // not set its active flag yet.
        if (existing.owner == owner) return Request(null, true)
        pendingOwner = owner
        return Request(null, false)
    }

    /** Completes an old worker and, if requested, reserves its replacement. */
    @Synchronized
    fun complete(run: Run): Run? {
        if (current !== run) return null
        current = null
        val owner = pendingOwner ?: return null
        pendingOwner = null
        return Run(++nextToken, owner).also { current = it }
    }

    @Synchronized
    fun invalidate(): Run? {
        val old = current
        current = null
        pendingOwner = null
        return old
    }

    @Synchronized
    fun isCurrent(run: Run): Boolean = current === run
}
