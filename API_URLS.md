# URL API đối chiếu — 02/10/2026

## Rebuild đang chạy

Base URL `http://127.0.0.1:8080`, qua `adb reverse tcp:8080 tcp:8080`. Runtime dùng preference `api_url` hoặc `BuildConfig.API_BASE_URL`. Đây là endpoint debug cục bộ.

| Việc | Method và URL | Kết quả lần này |
|---|---|---|
| Danh mục gói | GET `http://127.0.0.1:8080/v1/products` | App tải danh mục |
| Tạo đơn | POST `http://127.0.0.1:8080/v1/payments` | App tạo đơn sandbox |
| Settlement | POST `http://127.0.0.1:8080/v1/payments/{id}/sandbox` | `paid`; gọi lặp HTTP 200, không cấp trùng |
| Đọc đơn | GET `http://127.0.0.1:8080/v1/payments/{id}` | HTTP 200, `paid` |
| Quyền lợi | GET `http://127.0.0.1:8080/v1/me` | HTTP 200, một entitlement Premium |
| Mã kích hoạt | POST `http://127.0.0.1:8080/v1/activation/redeem` | Có trong router; chưa chạy ở lần này |
| Cài đặt | GET/PUT `http://127.0.0.1:8080/v1/settings` | GET đạt; không thay cấu hình để thử Premium |
| Hội thoại | POST `http://127.0.0.1:8080/v1/assistant/text` | HTTP 503, provider chưa cấu hình |
| ASR | POST `http://127.0.0.1:8080/v1/speech/decode` | Chưa chạy audio lần này |
| TTS | POST `http://127.0.0.1:8080/v1/tts` | HTTP 503, provider chưa cấu hình |
| Giao thông | POST `http://127.0.0.1:8080/v1/traffic/check` | HTTP 503, provider chưa cấu hình |

Nghiệp vụ dùng Bearer token của rebuild, chỉ sử dụng trong bộ nhớ khi kiểm tra. [Bằng chứng](reports/premium-flow/premium-check.json), [báo cáo](reports/premium-flow/REPORT.md).

## App gốc: cấu hình và annotation từ APK

Base mặc định chính: `https://api.kiki.zalo.ai/`. Đây là phân tích tĩnh; chưa quan sát request Premium runtime và chưa xác nhận remote config giữ nguyên base này. [Servers](reference/servers.json), [catalog](reference/API_CATALOG.md).

| Path trong code | Vai trò khai báo | Source dưới `work/kiki-overview/jadx/sources` |
|---|---|---|
| `api/payment/create-iap-request-page` | Tạo trang yêu cầu mua | `g1/k.java:13` |
| `api/payment/create-iap-request-url` | Tạo URL yêu cầu mua | `g1/k.java:18` |
| `api/payment/activate-code` | Gửi `status`, `session_id` | `g1/k.java:23` |
| `api/payment/get-user-recent-success-time` | Trạng thái mua gần đây | `g1/k.java:28` |
| `api/payment/get-transaction` | Đọc giao dịch theo `id` | `g1/k.java:33` |
| `api/payment/get-user-products` | Sản phẩm của tài khoản | `g1/k.java:38` |
| `/api/payment/v3/device/authorize` | Device authorization/code challenge | `AuthenticateAPI.java:27` |
| `/api/payment/v3/device/check-token` | Kiểm tra device token | `AuthenticateAPI.java:32` |
| `/api/payment/v3/device/oauth/token` | Đổi token thiết bị | `AuthenticateAPI.java:92` |
| `api/input-activation-key/` | Nhập khóa kích hoạt | `AuthenticateAPI.java:22` |

Interface xác thực nằm tại `ai/zalo/kiki/core/app/authen/service/AuthenticateAPI.java`. Việc ghép base/path mặc định chưa chứng minh URL đang được gọi thành công.

Host giọng nói trong cấu hình gốc: ASR WSS `gotech-socket.asr.zalo.ai`, HTTP failover `kiki-wss-failover-http.kiki.zalo.ai`, TTS `partner-speech.lab.zalo.ai`. Contract/xác thực provider cần cấu hình riêng; lần này chưa gửi request tới các host đó.
