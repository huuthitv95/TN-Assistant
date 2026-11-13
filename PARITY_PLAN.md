# Kế hoạch hoàn thiện TN Assistant

Phạm vi: Android độc lập, đối chiếu reference hiện có trong Kiki_Auto; Basic và Premium, không thanh toán thật. Chỉ nghiệm thu Android SDK emulator theo yêu cầu chủ ứng dụng. Không suy diễn độ tương thích đầu xe thật từ emulator, không quy đổi số test thành phần trăm hoàn thành.

## Đợt hỗ trợ .196

- Sáu mục hỗ trợ đọc cấu hình VPS: QR đúng URL, hai tài liệu WebView, thiếu cấu hình/lỗi/retry/đóng rõ ràng.
- Năm trang tĩnh TN: FAQ, liên hệ, khiếu nại, khách hàng dễ tổn thương và micro. Facebook chưa có kênh TN được xác nhận. Email trong source chưa được nghiệm thu nhận/gửi.
- Backend sửa trạng thái danh bạ thành có bộ xử lý Android nhưng cần quyền và xác nhận; không coi đó là nghiệm thu toàn bộ danh bạ.
- Hai smoke call thành công ngày 13/11/2026. Không tự gọi thêm provider để giữ readiness xanh; phân biệt hết hạn bằng chứng với dịch vụ lỗi.
- Bổ sung rebuild ONNX JNI x86_64 với PT_LOAD 16 KB; kiểm tra exports và nghiệm thu lại trên emulator trước phát hành.

## Các bước còn lại

| Hạng mục | Điều kiện nghiệm thu |
|---|---|
| Cảnh báo giao thông | Nối vị trí và dữ liệu đường vào Android; xử lý quyền, mất GPS, dữ liệu cũ, chống phát lặp; kiểm tra tuyến giả lập Basic/Premium |
| Chào khi khởi động | Đối chiếu receiver/service gốc và giới hạn Android; opt-in, phiên đăng nhập, chống lặp; test reboot/process death/logout |
| Zalo | Chẩn đoán -501 từ phản hồi thực; triển khai Gateway khi có hạ tầng được cấp quyền; nghiệm thu QR, hủy, hết hạn, retry và đăng nhập thật. Hiện chưa có máy chủ egress Việt Nam |
| Lệnh/media/maps/danh bạ | Ma trận lệnh từ reference: thành công, thiếu app, nhiều kết quả, từ chối quyền, xác nhận/hủy; kiểm tra tác động thật trên emulator |
| Wake/UI/Premium | Âm thanh dương/âm và tiếng ồn; ngừng micro khi logout/mất quyền; các trạng thái UI, entitlement hết hạn, khôi phục process, offline |
| Phát hành | Signer/package/version, tải trọn APK và checksum, catalog đúng kênh, nâng cấp giữ dữ liệu, rollback và quan sát theo release |

## Cổng hoàn thành

Mỗi chức năng cần đối chiếu hành vi gốc và bằng chứng chạy tương ứng. Unit test, fixture, cấu hình provider hoặc HTTP 200 đơn lẻ không thay thế nghiệm thu toàn bộ luồng. Chỉ gọi hoàn thành phạm vi emulator khi không còn mục thiếu/chưa nghiệm thu trong ma trận. Phụ thuộc bên ngoài phải hiển thị trạng thái thật; không coi proxy công cộng là máy chủ mTLS do TN quản lý.
