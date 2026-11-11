package vn.tn.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TnReadinessDisplayTest {
    @Test fun recentTranscriptIsShownAsVerified() {
        assertEquals("Đã xác minh gần đây", TnReadinessDisplay.providerLabel(
            "verified_recently", true, true))
    }

    @Test fun configuredProviderWithoutTranscriptIsNotReportedReady() {
        assertEquals("Đã cấu hình, chưa xác minh", TnReadinessDisplay.providerLabel(
            "configured_not_verified", false, true))
    }

    @Test fun staleVerificationRequestsAnotherSuccessfulUse() {
        assertEquals("Cần xác minh lại", TnReadinessDisplay.providerLabel(
            "verification_stale", false, true))
    }

    @Test fun absentConfigurationIsReportedExplicitly() {
        assertEquals("Chưa cấu hình", TnReadinessDisplay.providerLabel(
            "not_configured", false, false))
    }

    @Test fun malformedOrMissingReadinessNeverLooksSuccessful() {
        assertEquals("Đã cấu hình, chưa sẵn sàng", TnReadinessDisplay.providerLabel(null, false, true))
        assertEquals("Chưa cấu hình", TnReadinessDisplay.providerLabel(null, false, false))
    }
}
