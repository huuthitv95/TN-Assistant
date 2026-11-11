package vn.tn.assistant.auth

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class ZaloNavigationPolicyTest {
    private val base = "https://assistant.tnfastvn.com"
    private val policy = ZaloNavigationPolicy(base, "tn-state")
    private fun auth(redirect: String = "$base/v1/auth/zalo/callback") =
        "https://oauth.zaloapp.com/v4/permission?app_id=123&state=tn-state&code_challenge=${"a".repeat(43)}&redirect_uri=" + URLEncoder.encode(redirect, "UTF-8")

    @Test fun acceptsBoundAuthorizationAndRealZaloNavigation() {
        assertTrue(policy.authorization(auth()))
        assertEquals(ZaloNavigationPolicy.Destination.ZALO, policy.destination("https://id.zalo.me/account/login?continue=a"))
        assertEquals(ZaloNavigationPolicy.Destination.ZALO, policy.destination("https://id.zalo.me/account/login#qr"))
        assertEquals(ZaloNavigationPolicy.Destination.ZALO, policy.destination("https://jr.zaloapp.com/"))
        assertEquals(ZaloNavigationPolicy.Destination.CALLBACK, policy.destination("$base/v1/auth/zalo/callback?state=tn-state&code=example"))
    }
    @Test fun rejectsChangedOrDuplicatedStateAndCrossOriginCallback() {
        assertFalse(policy.authorization(auth().replace("tn-state", "other")))
        assertFalse(policy.authorization(auth() + "&state=tn-state"))
        assertFalse(policy.authorization(auth("https://example.com/v1/auth/zalo/callback")))
        assertFalse(policy.authorization(auth("$base:444/v1/auth/zalo/callback")))
        assertEquals(ZaloNavigationPolicy.Destination.BLOCKED, policy.destination("$base/v1/auth/zalo/callback?state=other&code=example"))
        assertEquals(ZaloNavigationPolicy.Destination.BLOCKED, policy.destination("$base/v1/auth/zalo/callback?state=tn-state&state=other"))
    }
    @Test fun rejectsInsecureSpoofedAndNonWebNavigation() {
        for (url in listOf("http://id.zalo.me/account/login", "https://id.zalo.me.attacker.example/", "https://jr.zaloapp.com.attacker.example/", "https://user@id.zalo.me/", "https://id.zalo.me:444/", "javascript:alert(1)", "file:///data/local/file", "content://provider/file", "intent://login")) {
            assertEquals(url, ZaloNavigationPolicy.Destination.BLOCKED, policy.destination(url))
        }
        assertFalse(policy.authorization(auth().replace("oauth.zaloapp.com", "oauth.zaloapp.com.attacker.example")))
        assertFalse(policy.authorization(auth() + "&redirect_uri=invalid"))
        assertEquals(ZaloNavigationPolicy.Destination.BLOCKED, policy.destination("https://example.com/"))
    }
}
