package vn.tn.assistant.auth

import org.junit.Assert.*
import org.junit.Test

class ZaloCompletionPolicyTest {
    @Test fun completionFailureDoesNotPretendAttemptExpiredOrUserDenied() {
        for (status in listOf(null, 400, 401, 403, 409, 429, 502, 503)) {
            val message = ZaloCompletionPolicy.failure(status, "PRIVATE_PROVIDER_TEXT")
            assertFalse(message.contains("hết hạn"))
            assertFalse(message.contains("Bạn đã từ chối"))
            assertFalse(message.contains("PRIVATE_PROVIDER_TEXT"))
            assertFalse(message.contains("đăng nhập thành công") && !message.contains("chưa xác nhận"))
        }
    }

    @Test fun distinguishesActionableAccountConflictFromServiceFailure() {
        assertTrue(ZaloCompletionPolicy.failure(503, "ZALO_REGION_RESTRICTED").contains("dùng tài khoản TN"))
        assertTrue(ZaloCompletionPolicy.failure(409, "DEVICE_LIMIT").contains("giới hạn thiết bị"))
        assertTrue(ZaloCompletionPolicy.failure(409, "OAUTH_SOURCE_SESSION_CHANGED").contains("đã thay đổi"))
        assertTrue(ZaloCompletionPolicy.failure(502, "ZALO_BAD_RESPONSE").contains("dịch vụ trên máy chủ TN"))
        assertTrue(ZaloCompletionPolicy.failure(null, null).contains("Mất kết nối"))
    }
}
