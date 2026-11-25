package vn.tn.assistant.audio

/**
 * Serializes microphone-service runs so cleanup from an old login cannot stop a
 * replacement run. A replacement is started only after the old worker releases
 * its recorder and capture lease.
 */
internal class TnWakeRunCoordinator {
    data class Run(val token: Long, val owner: Long, val service: Long = owner)
    data class Request(val run: Run?, val alreadyActive: Boolean)

    private var nextToken = 0L
    private var current: Run? = null
    private var pending: Pair<Long, Long>? = null
    private data class TrackedReplacement(
        val run: Run,
        val originService: Long,
        val targetService: Long,
    )
    private var trackedReplacement: TrackedReplacement? = null
    private var started: Run? = null
    // Run is a data class for value-oriented test/debug output, but worker
    // ownership is lifecycle identity. Equality here would let a distinct
    // worker with the same fields unregister or finish the real worker.
    private val inFlightWorkers: MutableSet<Run> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Run, Boolean>())

    @Synchronized
    fun request(owner: Long, service: Long = owner): Request {
        val existing = current
        if (existing == null) {
            pending = null
            trackedReplacement = null
            started = null
            val run = Run(++nextToken, owner, service)
            current = run
            return Request(run, false)
        }
        // A lost main-thread callback can leave an exact replacement reserved
        // without a worker. Let its owner retry that reservation; an ordinary
        // initial duplicate remains idempotent and must not create a second run.
        val replacement = trackedReplacement
        if (existing.owner == owner && existing.service == service) {
            if (replacement?.run === existing && started !== existing) {
                return Request(existing, false)
            }
            return Request(null, true)
        }
        pending = owner to service
        return Request(null, false)
    }

    data class Completion(val replacement: Run?)

    /** Registers a worker at the first instruction of its started thread. */
    @Synchronized
    fun registerWorker(run: Run): Boolean = inFlightWorkers.add(run)

    @Synchronized
    fun unregisterWorker(run: Run) {
        inFlightWorkers.remove(run)
    }

    /** Completes an old worker and, if requested, reserves its replacement. */
    @Synchronized
    fun finish(run: Run): Completion? {
        if (!inFlightWorkers.remove(run)) return null
        if (current !== run) return null
        current = null
        started = null
        val replacement = pending ?: run {
            trackedReplacement = null
            return null
        }
        pending = null
        val replacementRun = Run(++nextToken, replacement.first, replacement.second)
        trackedReplacement = TrackedReplacement(replacementRun, run.service, replacement.second)
        return Completion(replacementRun.also { current = it })
    }

    @Synchronized
    fun complete(run: Run): Run? = finish(run)?.replacement

    @Synchronized
    fun invalidate(expected: Run? = null): Run? {
        val old = current
        if (expected != null && old !== expected) return null
        current = null
        pending = null
        trackedReplacement = null
        started = null
        return old
    }

    /**
     * Rejects a replacement that could not be started. If a newer request arrived
     * while it was waiting for the main-thread callback, reserve that request as
     * the next replacement instead of dropping it.
     */
    @Synchronized
    fun rejectReplacement(candidate: Run): Run? {
        if (current !== candidate) return null
        current = null
        trackedReplacement = null
        started = null
        val replacement = pending
        pending = null
        return replacement?.let { next ->
            val promoted = Run(++nextToken, next.first, next.second)
            trackedReplacement = TrackedReplacement(promoted, candidate.service, promoted.service)
            promoted.also { current = it }
        }
    }

    /** Atomically admits exactly one start on the service owning the reservation. */
    @Synchronized
    fun beginStart(candidate: Run, service: Long): Boolean {
        if (current !== candidate || candidate.service != service || started === candidate) return false
        started = candidate
        trackedReplacement = trackedReplacement?.takeUnless { it.run === candidate }
        return true
    }

    /** Resolves only the exact run that failed before its worker was published. */
    @Synchronized
    fun abortStart(candidate: Run): Run? = rejectReplacement(candidate)

    /** Marks a replacement as owned by its new service once its callback starts. */
    @Synchronized
    fun claimReplacement(candidate: Run): Boolean {
        if (current !== candidate || trackedReplacement?.run !== candidate) return false
        trackedReplacement = null
        return true
    }

    data class Abandonment(val abandoned: Boolean, val promoted: Run?)

    /**
     * Resolves one exact, still-unclaimed handoff on behalf of its target.
     * The origin is deliberately not authorized: destroying an old origin must
     * never discard a replacement that may still be claimed by the target.
     */
    @Synchronized
    fun abandonReplacement(candidate: Run, targetService: Long): Abandonment {
        val tracked = trackedReplacement ?: return Abandonment(false, null)
        if (current !== candidate || tracked.run !== candidate ||
            tracked.targetService != targetService) return Abandonment(false, null)

        val replacement = pending
        pending = null
        started = null
        if (replacement == null) {
            trackedReplacement = null
            current = null
            return Abandonment(true, null)
        }

        val promoted = Run(++nextToken, replacement.first, replacement.second)
        trackedReplacement = TrackedReplacement(
            run = promoted,
            originService = tracked.originService,
            targetService = promoted.service,
        )
        current = promoted
        return Abandonment(true, promoted)
    }

    /** Returns the exact unclaimed candidate owned by this target, if any. */
    @Synchronized
    fun currentReplacementFor(targetService: Long): Run? = trackedReplacement
        ?.takeIf { it.targetService == targetService && current === it.run }
        ?.run

    /**
     * Returns the same handoff when its origin is being destroyed. The origin
     * may dispatch it, but it is never allowed to abandon it.
     */
    @Synchronized
    fun currentReplacementFromOrigin(originService: Long): Run? = trackedReplacement
        ?.takeIf { it.originService == originService && current === it.run }
        ?.run

    /** Returns whether this exact run still owns a worker resource. */
    @Synchronized
    fun hasWorker(run: Run): Boolean = inFlightWorkers.contains(run)

    /** True only when no reservation and no worker still owns resources remain. */
    @Synchronized
    fun noWorkerRemains(): Boolean = current == null && inFlightWorkers.isEmpty()

    @Synchronized
    fun isCurrent(run: Run): Boolean = current === run
}
