package vn.tn.assistant.ui

internal object TnReadinessDisplay {
    fun providerLabel(status: String?, healthy: Boolean, configured: Boolean): String {
        if (healthy) return "Đã xác minh gần đây"
        return when (status) {
            "configured_not_verified" -> "Đã cấu hình, chưa xác minh"
            "verification_stale" -> "Cần xác minh lại"
            "not_configured" -> "Chưa cấu hình"
            "unavailable" -> "Không truy cập được"
            else -> if (configured) "Đã cấu hình, chưa sẵn sàng" else "Chưa cấu hình"
        }
    }
}
