package vn.tn.assistant.auth

/** Completion consumes a one-time attempt; its failure requires a fresh authorization. */
object ZaloCompletionPolicy {
    fun failure(httpStatus: Int?, errorCode: String?): String = when {
        errorCode == "ZALO_REGION_RESTRICTED" -> "Đăng nhập Zalo tạm chưa khả dụng. Vui lòng dùng tài khoản TN hoặc thử lại sau."
        errorCode == "DEVICE_LIMIT" -> "Tài khoản đã đạt giới hạn thiết bị. Hãy kiểm tra thiết bị trong tài khoản TN trước khi đăng nhập lại."
        errorCode in setOf("OAUTH_SOURCE_SESSION_CHANGED", "DEVICE_OWNERSHIP_CHANGED") ->
            "Tài khoản hoặc thiết bị đã thay đổi trong lúc đăng nhập. Vui lòng tạo phiên đăng nhập mới."
        httpStatus == 401 || httpStatus == 403 ->
            "Zalo chưa xác thực được tài khoản để đăng nhập TN. Vui lòng tạo phiên đăng nhập mới; nếu lỗi lặp lại, hãy kiểm tra cấu hình Zalo trên máy chủ TN."
        httpStatus == 429 -> "Dịch vụ đang giới hạn yêu cầu đăng nhập. Vui lòng chờ rồi tạo phiên đăng nhập mới."
        httpStatus != null && httpStatus >= 500 ->
            "Dịch vụ xác thực chưa hoàn tất đăng nhập. Vui lòng tạo phiên đăng nhập mới; nếu lỗi lặp lại, hãy kiểm tra dịch vụ trên máy chủ TN."
        httpStatus == null ->
            "Mất kết nối khi xác minh tài khoản. TN chưa xác nhận đăng nhập thành công. Vui lòng tạo phiên đăng nhập mới."
        else -> "Không hoàn tất được việc xác minh tài khoản. Vui lòng tạo phiên đăng nhập mới."
    }
}
