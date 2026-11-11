# Hợp đồng giao diện đăng nhập

| Khả năng | Owner | Hành vi |
|---|---|---|
| Mở đăng nhập | `MainActivity.login`, `LoginEntryView` | Từ Quyền → Liên kết Zalo hoặc Tài khoản → Đăng nhập Zalo. Fullscreen, back về đúng màn nguồn |
| Tiếp tục | `startZaloLogin` | Một request `/v1/auth/zalo/start` mỗi lần; CTA disabled trong khi chờ; không mở WebView nếu response đã cũ sau Back/đóng app |
| Chưa bật Zalo | `loginError` | Thông báo ngắn trên cùng màn, bật “Thử cách khác”; không lộ exception/provider configuration trong copy người dùng |
| Trợ giúp | `LoginEntryView.helpButton` | Bấm ? mở/đóng lựa chọn khác ở góc phải; có content description và target 48dp |
| Liên kết QR | `DeviceLinkView`, `MainActivity.link` | QR và code thực từ backend; hướng dẫn liên kết qua quản trị. Có loading/error/retry/kiểm tra; không giả đăng nhập Zalo |
| Kiểm tra trạng thái | GET `/v1/link/{code}` | Pending/expired/completed lấy từ backend. Hết hạn làm mờ QR và cho tạo mã mới; không xác nhận thành công khi chỉ tạo QR |
| Quay lại | `MainActivity.onBackPressed` | QR/WebView về màn mở đăng nhập; màn mở đăng nhập về nguồn. Back WebView đi trong history trước khi thoát |
| WebView | `loginWeb`, `allowedWebUrl` | Header/banner theo gốc; loading và retry main-frame. Giữ HTTPS, localhost chỉ debug; chặn file/content access và navigation scheme khác |
| Preview | Gradle build type `preview` | Package suffix riêng, cùng namespace/JNI và cùng source. Không cài đè package gốc |

Provider Zalo của backend hiện trả lỗi chưa cấu hình/chưa có hợp đồng. UI revision này không triển khai OAuth hoặc bổ sung xác thực mới. Thành công OAuth phải nghiệm thu riêng khi có dịch vụ hợp lệ.

Kiểm chứng: build Debug/Preview với Java 21; JVM regressions đang có; điều hướng/CTA/errors/QR thực trên LDPlayer; ảnh ở 1600×900, 1280×720, 1024×600; hoàn nguyên kích thước/density sau xem. Ảnh minh họa và log kiểm chứng lưu `../reports/login-ui/`.
# Bổ sung revision .04

Home idle không hiển thị form kỹ thuật; giữ gợi ý, hướng dẫn, micro và cài đặt. Nhấn giữ micro mới mở nhập lệnh tùy chọn. Waveform chỉ chạy khi có trạng thái phiên giọng nói thật; rời Activity dừng phiên đang nghe. Hướng dẫn có tám nhóm và footer vẫn điều hướng được.

Cài đặt: bảy nhóm anchor, sidebar cuộn độc lập, chọn ứng dụng bằng drawer radio. ĐÓNG/Back drawer trở lại màn nguồn. Header Back của trang chức năng mở từ Settings trở lại Settings. Hủy không lưu lịch sử giữ nguyên giá trị cũ; chỉ Xác nhận cập nhật. Lưu lại cùng địa chỉ máy chủ giữ phiên hiện tại.

Tiện ích nổi: kiểm tra thông báo rồi overlay, cỡ 60/72/90dp, bốn độ đậm, lưu vị trí và chặn ngoài màn hình. Hủy tắt giữ bật; xác nhận tắt bỏ cửa sổ. Overlay ẩn trong Activity Kiki, hiện trên launcher và còn sau Activity đóng. Chạm gọi trợ lý qua consent/quyền micro; service bong bóng không ghi âm. Chưa có tự khởi động sau reboot hoặc toàn bộ adapter nội dung như app gốc.

