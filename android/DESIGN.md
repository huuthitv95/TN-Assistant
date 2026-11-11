# Giao diện Android Kiki rebuild

Revision `.04` bổ sung `AssistantSurface`/`SettingsSurface` và `FloatingBubbleService` theo [audit sau đăng nhập](../POST_LOGIN_APP_AUDIT.md), [audit bong bóng](../FLOATING_BUBBLE_AUDIT.md). Home giữ trong suốt và cụm điều khiển đáy; hướng dẫn tám nhóm cuộn riêng với footer vẫn chạm được. Settings dùng bảy anchor, sidebar cuộn độc lập, nội dung liên tục, subdetail và drawer phải 42% chiều rộng. Tiêu đề drawer tự tăng chiều cao; viewport dưới 750dp thu chữ điều hướng để giữ đủ nhãn.

Bong bóng 60/72/90dp, bốn độ đậm, kéo tự do/clamp và xác nhận tắt. Foreground service giữ cửa sổ khi Activity kết thúc; ẩn khi Activity Kiki đang dùng để không che điều khiển. Quyền thông báo và overlay là điều kiện bật; service không đọc nội dung thông báo hay thu âm. Chạm bong bóng mở MainActivity, nơi consent/micro và VoiceService thật vẫn quyết định phiên nghe. [Đối chiếu revision](../reports/post-audit-ui/index.html).

Chuẩn hình ảnh cho phần đăng nhập là app gốc `26.09.01.01` đã audit trên LDPlayer. [Tài liệu đối chiếu](../ORIGINAL_APP_AUDIT.md) và ảnh `../../original-audit-20261002/evidence/13-overlay-return.png` là bằng chứng chính. Không tự thay palette hoặc stack theo một thiết kế web mới.

Phạm vi revision `26.10.02.03-rebuilt`: màn mở đăng nhập, trợ giúp/phương án liên kết, bố cục QR và khung WebView đăng nhập. Các bước onboarding/quyền khác giữ hành vi hiện tại và chưa được coi là đã phục hồi đầy đủ app gốc.

Native Android framework Views, landscape cho màn hình xe. Barlow Regular cho mô tả/CTA, Semibold cho tiêu đề. Nền `background_activity` dùng artwork gốc, accent cyan `#2DD3E0`, secondary `#B2DDEB`; status bar login `#000817`. Minh họa Kiki–Zalo và ánh sáng/đường chia là tài nguyên gốc được chép nguyên byte.

`ui/LoginViews.kt` sở hữu `LoginEntryView` và `DeviceLinkView`; `MainActivity` điều hướng và gọi API. Không dùng sidebar tài khoản bên trong màn mở đăng nhập. Layout đặt nội dung theo vùng của ảnh gốc, co font trong viewport thấp, giữ vùng bấm ít nhất 48dp. Khi có lỗi, ưu tiên thông báo và retry hơn chấm tiến trình.

Màn QR giữ hai cột có step label; code/QR đến từ `/v1/link/start`. Hướng dẫn ghi rõ liên kết qua quản trị vì backend này chưa có QR OAuth Zalo. Không tự thêm mã sáu chữ số hoặc trạng thái thành công để giống ảnh gốc.

App `ai.zalo.kiki.car.preview` dùng cùng source/resources để xem trên LDPlayer trong lúc package app gốc đang cài. Chỉ bản debug/preview có shortcut `preview_screen=login`; release không dùng shortcut này.
