# Cấu hình hỗ trợ TN Assistant

Android đọc object `support` từ `GET /v1/config` khi mở **Trợ giúp → Hướng dẫn và liên hệ hỗ trợ**. Hướng dẫn trong app và email `support@tnfastvn.com` luôn có mặt; các mục dưới đây chỉ mở được khi có URL hợp lệ.

| Field | Nội dung | Hiển thị |
|---|---|---|
| `faq_url` | Vấn đề thường gặp | QR |
| `contact_url` | Liên hệ hỗ trợ trực tiếp | QR |
| `complaints_url` | Quy trình tiếp nhận và xử lý khiếu nại | WebView |
| `vulnerable_customers_url` | Chính sách cho khách hàng dễ tổn thương | WebView |
| `facebook_url` | Facebook TN Assistant | QR |
| `recording_help_url` | Hướng dẫn sửa lỗi micro | QR |

Chỉ nhận HTTPS, hostname dạng DNS, cổng mặc định hoặc 443, không user/password trong URL. Không nhận tên localhost/local/internal, IP literal, scheme tùy ý hoặc chuỗi quá 2048 ký tự. Kiểm tra này kiểm tra cú pháp URL, không xác minh địa chỉ DNS của máy chủ. URL chưa có hoặc không hợp lệ hiển thị **Chưa cấu hình**, kèm Đóng/Tải lại cấu hình. Nội dung và URL phải thuộc kênh hỗ trợ mà TN được phép sử dụng; không sao chép danh tính, cam kết hoặc QR của ứng dụng tham chiếu.

WebView chỉ điều hướng trong cùng hostname đã cấu hình. JavaScript, file/content access, cửa sổ mới và tải tệp đều bị tắt; không có JavaScript bridge hoặc header xác thực TN. Lỗi mạng, HTTP, TLS, chuyển hướng khác host và timeout 20 giây có trạng thái lỗi/Thử lại. Thử lại tạo browser mới; Đóng/Back hoặc tháo màn hình cài đặt hủy browser và callback cũ. Trang tài liệu nên là HTML tĩnh, sử dụng tài nguyên cùng host; PDF hoặc trang cần JavaScript chưa được hỗ trợ.

Quản trị đọc `GET /v1/admin/config`, bổ sung các field cần thiết vào `support` rồi `PUT /v1/admin/config` với **toàn bộ cấu hình đã giữ nguyên các field khác**. Không gửi riêng object `support`, vì PUT thay thế toàn bộ cấu hình công khai. Không đưa token/password vào tài liệu hoặc source. Không tự cấu hình URL mẫu khi chưa có nội dung thật.

`TnSupportPolicyTest` kiểm tra URL và giới hạn điều hướng. `TnSupportRuntimeTest` kiểm tra giải mã QR từ ảnh được render, trạng thái thiếu cấu hình, retry, cấu hình WebView và đóng/hủy callback trên emulator. Các URL fixture trong test không chứng minh kênh hỗ trợ production hoạt động. Sau khi có URL thật, nghiệm thu quét QR bằng thiết bị đọc mã, nội dung tài liệu, HTTP/redirect, retry và Back/Đóng riêng.

Hướng dẫn hardening WebView: [Android Developers](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion).

## Các trang TN đã triển khai

Năm trang tĩnh nằm trong `admin/public/support` và webroot `/www/wwwroot/tn.assistant/support`:

- FAQ: `https://assistant.tnfastvn.com/support/faq.html`
- Liên hệ: `https://assistant.tnfastvn.com/support/contact.html`
- Khiếu nại: `https://assistant.tnfastvn.com/support/complaints.html`
- Khách hàng dễ tổn thương: `https://assistant.tnfastvn.com/support/accessibility.html`
- Micro: `https://assistant.tnfastvn.com/support/recording.html`

Facebook chưa cấu hình. Nội dung không cam kết thời gian phản hồi hoặc hoàn tiền chưa được chủ dịch vụ xác nhận. Email là địa chỉ đã có trong source; nhận/gửi thư chưa được nghiệm thu.

`deploy/publish_support.py` kiểm tra manifest và HTTPS trước khi thêm năm URL, sao lưu riêng cấu hình trước/sau, giữ các field khác và không restart API. Khi thiếu bản ghi cấu hình, cần snapshot rõ ràng của `GET /v1/config`; không tự tạo defaults khác. Script chỉ hỗ trợ lần công bố đầu tiên, từ chối thư mục đã tồn tại.

Test live tùy chọn: `TnSupportRuntimeTest#configuredProductionDocumentLoadsWithoutJavascript` với instrumentation argument `support_live=true`. Test chỉ tải HTML, không gọi ASR/TTS; ảnh chụp nằm trong external files của Preview để kiểm tra rồi xóa. Hộp thoại tương thích native của hệ thống, nếu có, được ghi nhận riêng; test tài liệu không chứng minh độ chính xác wake/VAD.
