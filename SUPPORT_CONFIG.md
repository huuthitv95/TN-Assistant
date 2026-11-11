# Đích hỗ trợ của backend rebuild

Preview .13 đọc `GET /v1/config`, object `support`. Hiện máy chủ local chưa có bốn URL; app hiển thị trạng thái chưa có nội dung. Chưa nghiệm thu quét QR hay tải tài liệu thành công.

| Field | Màn hình | Yêu cầu |
|---|---|---|
| `faq_url` | Vấn đề thường gặp | HTTPS, host hợp lệ, không username/password trong URL |
| `contact_url` | Liên hệ hỗ trợ trực tiếp | HTTPS cho kênh hỗ trợ thực của hệ thống mới |
| `complaints_url` | Quy trình tiếp nhận và xử lý khiếu nại | HTTPS cho tài liệu thực áp dụng cho dịch vụ mới |
| `vulnerable_customers_url` | Chính sách cho khách hàng dễ tổn thương | HTTPS cho tài liệu thực áp dụng cho dịch vụ mới |
| `contact_description` | Mô tả liên hệ | Tùy chọn, văn bản thuần, phản ánh cam kết hỗ trợ thực |

Hai URL đầu tạo QR tại client; hai URL sau tải bằng WebView. Client không gửi token API của rebuild trong header WebView, không có bridge JavaScript và không cho truy cập file/content URI. Khả năng tải lỗi HTTP, redirect, retry, điều hướng và thiết bị Android khác còn cần nghiệm thu. Unit test dùng domain fixture chỉ kiểm tra encoder, không phải URL đang cấu hình.

Quản trị: dùng phiên admin của **backend riêng** đọc `GET /v1/admin/config`, bổ sung object `support` vào cấu hình hiện có rồi gửi `PUT /v1/admin/config`. PUT thay cả object cấu hình; cần giữ các field hiện có thay vì gửi riêng bốn URL và làm mất cấu hình khác. Không lưu token/password admin vào tài liệu hay command log.

Sau khi có đích thật: kiểm tra URL/QR bằng tài khoản thử, tải chính sách thành công, loading/thiếu URL/lỗi mạng/HTTP 4xx–5xx/redirect/retry, Back/X đóng trang và callback sau khi đóng không mở lại tài liệu. Ghi PNG/XML/HTTP status bằng hệ thống riêng và đối chiếu với luồng app gốc; không coi việc sinh QR là bằng chứng hỗ trợ thành công.

QR của app gốc giữ trong bằng chứng local riêng, không giải mã/xuất payload hoặc đưa vào tài nguyên rebuild. Không mặc nhiên áp dụng chính sách, danh tính hay cam kết hỗ trợ của bên khác cho backend mới. [Báo cáo .13](reports/support-content-ui/REPORT.md).

Revision .17 adds optional `support.facebook_url` (HTTPS with a valid host) for the diagnostic footer Facebook QR dialog. Live own URL is absent; missing content is explicit. This fifth destination is separate from the four .13 support pages above. Full config PUT preservation rule still applies; generating a fixture QR is not real support acceptance. [Native review](reports/diagnostics-native-ui/REPORT.md).

Revision .20 adds optional `support.recording_help_url` for **Hướng dẫn sửa lỗi** from the -2010 microphone warning. HTTPS/valid host/no credentials only. Client reads GET /v1/config; no live config was changed and current URL is absent. Missing configuration is explicit; final native warning/QR success remains unverified. Preserve the full configuration when PUT.
