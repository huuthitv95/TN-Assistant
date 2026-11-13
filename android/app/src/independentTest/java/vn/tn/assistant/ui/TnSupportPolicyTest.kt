package vn.tn.assistant.ui

import org.junit.Assert.*
import org.junit.Test

class TnSupportPolicyTest {
    @Test fun acceptsPublicHttpsLinksIncludingVietnamesePaths() {
        assertEquals("https://assistant.tnfastvn.com/help?a=1#faq", TnSupportPolicy.url("https://assistant.tnfastvn.com/help?a=1#faq"))
        assertNotNull(TnSupportPolicy.url("https://assistant.tnfastvn.com/hỗ-trợ"))
        assertNotNull(TnSupportPolicy.url("https://assistant.tnfastvn.com:443/help"))
    }

    @Test fun rejectsCredentialsLocalSchemesMalformedHostsAndPorts() {
        listOf(null, "", " https://example.com", "https://example.com/\n",
            "http://example.com", "javascript:alert(1)", "file:///sdcard/report", "content://contacts",
            "intent://example.com", "https://u:p@example.com", "https://example.com@evil.test",
            "https://localhost", "https://box.local", "https://127.0.0.1", "https://[::1]",
            "https://a..com", "https://-a.com", "https://a.com.", "https://example.com:8135",
            "https://example.com\\@evil.test", "https://%65xample.com", "https://example.com/" + "x".repeat(2048))
            .forEach { assertNull("Must reject $it", TnSupportPolicy.url(it)) }
    }

    @Test fun redirectsStayWithinConfiguredHttpsHost() {
        val original = "https://assistant.tnfastvn.com/support"
        assertTrue(TnSupportPolicy.allows(original, "https://ASSISTANT.tnfastvn.com:443/policy"))
        for (url in listOf("https://assistant.tnfastvn.com.evil.test/policy", "https://evil.test",
            "http://assistant.tnfastvn.com/policy", "https://user@assistant.tnfastvn.com/policy", "data:text/html,test")) {
            assertFalse(url, TnSupportPolicy.allows(original, url))
        }
    }

    @Test fun everySupportDestinationHasAStablePresentation() {
        assertEquals(6, TnSupportPolicy.pages.map { it.key }.distinct().size)
        assertEquals(setOf("complaints_url", "vulnerable_customers_url"), TnSupportPolicy.pages.filterNot { it.qr }.map { it.key }.toSet())
    }
}
