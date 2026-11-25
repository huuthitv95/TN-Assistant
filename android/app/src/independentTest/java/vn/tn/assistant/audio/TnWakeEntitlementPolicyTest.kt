package vn.tn.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TnWakeEntitlementPolicyTest {
    private val expiry = TnWakeEntitlementPolicy.Grant(1_000L)

    private fun request(
        wake: Boolean = true,
        role: String = "user",
        entitlements: TnWakeEntitlementPolicy.Entitlements = TnWakeEntitlementPolicy.Entitlements.absent(),
        server: Long? = null,
        latency: Long = 0L,
    ) = TnWakeEntitlementPolicy.Request(wake, role, entitlements, server, latency)

    @Test fun onlyExactFeatureBooleanAuthorizes() {
        assertTrue(TnWakeEntitlementPolicy.evaluate(request()).authorized)
        assertFalse(TnWakeEntitlementPolicy.evaluate(request(wake = false)).authorized)
    }

    @Test fun absentAndEmptyEntitlementsUseFiveMinuteCompatibilityWindow() {
        val absent = TnWakeEntitlementPolicy.evaluate(request())
        val empty = TnWakeEntitlementPolicy.evaluate(request(
            entitlements = TnWakeEntitlementPolicy.Entitlements.present(emptyList()),
        ))
        assertEquals(300_000L, absent.recheckAfterMillis)
        assertEquals(300_000L, empty.recheckAfterMillis)
    }

    @Test fun malformedPresentEntitlementsFailClosed() {
        assertFalse(TnWakeEntitlementPolicy.evaluate(request(
            entitlements = TnWakeEntitlementPolicy.Entitlements.malformed(),
        )).authorized)
    }

    @Test fun nonAdminGrantRequiresServerAndEveryExpiry() {
        val grants = TnWakeEntitlementPolicy.Entitlements.present(listOf(expiry, TnWakeEntitlementPolicy.Grant(null)))
        assertFalse(TnWakeEntitlementPolicy.evaluate(request(entitlements = grants, server = 0L)).authorized)
        assertFalse(TnWakeEntitlementPolicy.evaluate(request(entitlements = grants)).authorized)
    }

    @Test fun earliestVisibleGrantBoundaryIsConservative() {
        val grants = TnWakeEntitlementPolicy.Entitlements.present(listOf(
            TnWakeEntitlementPolicy.Grant(1_000L),
            TnWakeEntitlementPolicy.Grant(400L),
        ))
        val result = TnWakeEntitlementPolicy.evaluate(request(entitlements = grants, server = 0L))
        assertTrue(result.authorized)
        assertEquals(400L, result.recheckAfterMillis)
    }

    @Test fun expiredGrantAndWrongFeatureAreDenied() {
        assertFalse(TnWakeEntitlementPolicy.evaluate(request(
            entitlements = TnWakeEntitlementPolicy.Entitlements.present(listOf(expiry)), server = 1_000L,
        )).authorized)
        assertFalse(TnWakeEntitlementPolicy.evaluate(request(wake = false, role = "admin")).authorized)
    }

    @Test fun adminBypassesGrantExpiryAndPlanServerTime() {
        val result = TnWakeEntitlementPolicy.evaluate(request(
            role = "admin",
            entitlements = TnWakeEntitlementPolicy.Entitlements.present(listOf(TnWakeEntitlementPolicy.Grant(0L))),
        ))
        assertTrue(result.authorized)
    }

    @Test fun verificationLatencyIsSubtractedAndZeroFails() {
        val result = TnWakeEntitlementPolicy.evaluate(request(latency = 299_999L))
        assertTrue(result.authorized)
        assertEquals(1L, result.recheckAfterMillis)
        assertFalse(TnWakeEntitlementPolicy.evaluate(request(latency = 300_000L)).authorized)
    }

    @Test fun deadlineSaturatesInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, TnWakeEntitlementPolicy.monotonicDeadline(Long.MAX_VALUE - 2L, 5L))
        assertEquals(10L, TnWakeEntitlementPolicy.monotonicDeadline(10L, 0L))
    }
}
