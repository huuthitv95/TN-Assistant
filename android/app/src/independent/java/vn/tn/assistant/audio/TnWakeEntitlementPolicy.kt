package vn.tn.assistant.audio

/**
 * Pure client-side wake authorization and revalidation policy.
 *
 * The server's exact premium_features.wake_word boolean is the authorization
 * decision. Entitlements only provide a conservative time at which that
 * decision must be fetched again; account-plan presentation fields do not
 * grant access.
 */
internal object TnWakeEntitlementPolicy {
    const val DEFAULT_RECHECK_MILLIS = 5L * 60L * 1000L

    data class Grant(val expiresAtMillis: Long?)

    /**
     * [present] distinguishes an absent entitlements field from a malformed
     * present field ([grants] == null). An empty list is valid compatibility
     * input and uses the bounded fallback interval.
     */
    data class Entitlements(val present: Boolean, val grants: List<Grant>?) {
        companion object {
            fun absent() = Entitlements(false, null)
            fun present(grants: List<Grant>) = Entitlements(true, grants)
            fun malformed() = Entitlements(true, null)
        }
    }

    data class Request(
        val wakeFeature: Boolean,
        val role: String?,
        val entitlements: Entitlements,
        val serverTimeMillis: Long?,
        val verificationElapsedMillis: Long,
    )

    data class Decision(
        val authorized: Boolean,
        val recheckAfterMillis: Long = 0L,
        val reason: Reason,
    ) {
        enum class Reason {
            AUTHORIZED,
            FEATURE_DENIED,
            ROLE_MISSING,
            MALFORMED_ENTITLEMENTS,
            SERVER_TIME_MISSING,
            EXPIRY_MISSING,
            GRANT_EXPIRED,
        }
    }

    fun evaluate(request: Request): Decision {
        if (!request.wakeFeature) return Decision(false, reason = Decision.Reason.FEATURE_DENIED)
        if (request.role.isNullOrBlank()) return Decision(false, reason = Decision.Reason.ROLE_MISSING)

        // Admin capability is authoritative in premium_features. It does not
        // depend on a grant expiry or the presentation-only account plan.
        if (request.role == "admin") {
            return authorized(request.verificationElapsedMillis)
        }

        val grants = when {
            !request.entitlements.present -> emptyList()
            request.entitlements.grants == null ->
                return Decision(false, reason = Decision.Reason.MALFORMED_ENTITLEMENTS)
            else -> request.entitlements.grants
        }

        // Missing/empty grants are retained for compatibility, but their
        // authorization must be refreshed frequently and never gets a long
        // device-clock lease.
        if (grants.isEmpty()) return authorized(request.verificationElapsedMillis)

        val server = request.serverTimeMillis
            ?: return Decision(false, reason = Decision.Reason.SERVER_TIME_MISSING)
        var boundary = DEFAULT_RECHECK_MILLIS
        for (grant in grants) {
            val expiry = grant.expiresAtMillis
                ?: return Decision(false, reason = Decision.Reason.EXPIRY_MISSING)
            if (expiry <= server) return Decision(false, reason = Decision.Reason.GRANT_EXPIRED)
            val remaining = positiveDifference(expiry, server)
            if (remaining < boundary) boundary = remaining
        }
        return authorized(request.verificationElapsedMillis, boundary)
    }

    /** Converts a verified interval into a monotonic deadline without overflow. */
    fun monotonicDeadline(nowElapsedMillis: Long, intervalMillis: Long): Long {
        if (intervalMillis <= 0L) return nowElapsedMillis
        return if (nowElapsedMillis > Long.MAX_VALUE - intervalMillis) Long.MAX_VALUE
        else nowElapsedMillis + intervalMillis
    }

    private fun authorized(latency: Long, interval: Long = DEFAULT_RECHECK_MILLIS): Decision {
        val elapsed = latency.coerceAtLeast(0L)
        val remaining = (interval - elapsed).coerceAtLeast(0L)
        if (remaining == 0L) return Decision(false, reason = Decision.Reason.GRANT_EXPIRED)
        return Decision(true, remaining, Decision.Reason.AUTHORIZED)
    }

    private fun positiveDifference(end: Long, start: Long): Long {
        // end > start is checked by the caller. A negative start can make a
        // subtraction overflow; saturating is safer than authorizing a short
        // or negative interval due to wrapped arithmetic.
        return if (start < 0L && end > Long.MAX_VALUE + start) Long.MAX_VALUE else end - start
    }
}
