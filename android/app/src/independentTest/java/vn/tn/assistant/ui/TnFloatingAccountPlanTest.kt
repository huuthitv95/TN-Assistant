package vn.tn.assistant.ui

import org.junit.Assert.*
import org.junit.Test

class TnFloatingAccountPlanTest {
    @Test fun remainingPartialDayRoundsUpAndExpiresAtTheExactServerInstant() {
        val plan = TnFloatingAccountPlan("premium", 86400001L, 0L, 0L)
        assertEquals(2, plan.days(0))
        assertEquals(1, plan.days(1))
        assertEquals(1, plan.days(86400000))
        assertEquals("premium", plan.effectiveKind(86400000))
        assertEquals("basic", plan.effectiveKind(86400001))
        assertEquals(0, plan.days(86400002))
    }
    @Test fun adminHasNoInventedExpiryAndMissingPremiumExpiryDoesNotGrantTheBadge() {
        assertEquals("admin", TnFloatingAccountPlan("admin",null,0,0).effectiveKind(999999))
        assertEquals("basic", TnFloatingAccountPlan("premium",null,0,0).effectiveKind(0))
    }
}
