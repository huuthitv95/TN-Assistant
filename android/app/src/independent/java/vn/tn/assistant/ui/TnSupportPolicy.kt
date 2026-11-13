package vn.tn.assistant.ui

import java.net.URI
import java.util.Locale

/** Public support destinations never carry a TN session or accept local URI schemes. */
internal object TnSupportPolicy {
    data class Page(val key: String, val title: String, val qr: Boolean)
    val pages = listOf(
        Page("faq_url", "Vấn đề thường gặp", true),
        Page("contact_url", "Liên hệ hỗ trợ trực tiếp", true),
        Page("complaints_url", "Quy trình tiếp nhận và xử lý khiếu nại", false),
        Page("vulnerable_customers_url", "Chính sách cho khách hàng dễ tổn thương", false),
        Page("facebook_url", "Facebook TN Assistant", true),
        Page("recording_help_url", "Hướng dẫn sửa lỗi micro", true),
    )

    fun url(value: String?): String? {
        if (value.isNullOrBlank() || value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        if (host.length > 253 || !host.contains('.') || host.all { it.isDigit() || it == '.' } ||
            host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") ||
            host.split('.').any { !it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) return null
        return uri.toASCIIString()
    }

    fun allows(initial: String, candidate: String): Boolean {
        val base = url(initial)?.let(::URI) ?: return false
        val next = url(candidate)?.let(::URI) ?: return false
        return base.host.equals(next.host, true)
    }
}
