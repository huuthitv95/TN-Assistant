package vn.tn.assistant.auth

import java.net.URI
import java.net.URLDecoder

/** The browser receives only the public OAuth URL, never a TN access token or client proof. */
class ZaloNavigationPolicy(apiBase: String, private val state: String) {
    private val base = URI(apiBase)
    // jr.zaloapp.com is a redirect used by the live Zalo login page.
    private val upstreamHosts = setOf("oauth.zaloapp.com", "id.zalo.me", "jr.zaloapp.com")
    enum class Destination { ZALO, CALLBACK, BLOCKED }

    private fun secure(uri: URI) = uri.scheme == "https" && uri.host != null &&
        uri.rawUserInfo == null && uri.port in listOf(-1, 443)

    private fun query(uri: URI): Map<String, List<String>> = uri.rawQuery.orEmpty().split('&')
        .filter { it.isNotEmpty() }.map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }.groupBy({ it.first }, { it.second })

    private fun callback(uri: URI) = secure(uri) && uri.rawFragment == null && uri.host == base.host &&
        uri.port.let { if (it == -1) 443 else it } == base.port.let { if (it == -1) 443 else it } &&
        uri.rawPath == "/v1/auth/zalo/callback" && query(uri)["state"] == listOf(state)

    fun authorization(address: String): Boolean = runCatching {
        val uri = URI(address)
        val args = query(uri)
        val redirect = args["redirect_uri"]?.singleOrNull()?.let(::URI) ?: return false
        secure(uri) && uri.rawFragment == null && uri.host == "oauth.zaloapp.com" && uri.rawPath == "/v4/permission" &&
            args["state"] == listOf(state) && args["app_id"]?.singleOrNull()?.matches(Regex("[0-9]+")) == true &&
            args["code_challenge"]?.singleOrNull()?.matches(Regex("[A-Za-z0-9_-]{43}")) == true &&
            secure(redirect) && redirect.rawFragment == null && redirect.host == base.host && redirect.port in listOf(base.port, -1, 443) &&
            redirect.rawPath == "/v1/auth/zalo/callback" && redirect.rawQuery == null
    }.getOrDefault(false)

    fun destination(address: String): Destination = runCatching {
        val uri = URI(address)
        when {
            !secure(uri) -> Destination.BLOCKED
            callback(uri) -> Destination.CALLBACK
            uri.host in upstreamHosts -> Destination.ZALO
            else -> Destination.BLOCKED
        }
    }.getOrDefault(Destination.BLOCKED)
}
