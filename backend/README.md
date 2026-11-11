# TN Assistant backend

Backend Go/SQLite của TN Assistant. `TN_RUNTIME_MODE=independent` là chế độ duy nhất được chấp nhận khi chạy. NLU lệnh cơ bản xử lý cục bộ; AI, Zalo, MET Norway, ASR và TTS chỉ dùng khi cấu hình riêng đã được kiểm tra. Không có thanh toán thật: sản phẩm hiện ở sandbox.

Tài khoản có role `admin` trong SQLite được dùng mọi quyền TN Premium mà không cần mua gói. Quyền được kiểm tra lại từ role đã lưu trên server; hạ role sẽ thu hồi quyền này ngay. Tài khoản khác tiếp tục dùng entitlement còn hiệu lực. Quyền TN không cấp quyền trả phí ở dịch vụ bên ngoài và không bỏ qua kiểm tra khả năng hoạt động của provider.

## Chạy và kiểm tra

```powershell
cd backend
go mod download
go test ./...
go vet ./...
go build -trimpath -o tn-assistant.exe .
$env:TN_RUNTIME_MODE = 'independent'
$env:TN_ADDR = '127.0.0.1:8094'
$env:TN_DB = 'data/tn-assistant.db'
./tn-assistant.exe serve
```

Trong production, chạy binary bằng systemd. Cấu hình lấy từ biến môi trường, không tự đọc `.env`. Mẫu biến không bí mật nằm trong `deploy/tn-assistant.env.example`. Không ghi provider key, admin password hay Zalo secret vào source, APK hoặc log.

## Egress riêng cho Zalo

Backend chỉ dùng transport Gateway cho hai yêu cầu đổi mã OAuth và đọc hồ sơ Zalo. Khi `TN_ZALO_EGRESS_MODE=gateway`, backend kết nối mTLS tới địa chỉ `TN_ZALO_GATEWAY_ADDR`, gửi CONNECT tới `oauth.zaloapp.com:443` hoặc `graph.zalo.me:443`, rồi xác minh chứng thư HTTPS của Zalo bằng trust store hệ thống. CA riêng của Gateway chỉ xác minh lớp TLS bên ngoài. Đích khác, URL không phải HTTPS, cấu hình thiếu và lỗi Gateway đều bị từ chối; backend không tự đổi sang đường trực tiếp. Các dịch vụ ASR/TTS/Gemini tiếp tục dùng transport riêng của chúng.

Biến cấu hình nằm trong `deploy/tn-assistant.env.example`. Trên VPS, lưu CA Gateway, client certificate và client key dưới `/etc/tn-assistant/gateway`; tài khoản dịch vụ `tnassistant` cần quyền đọc các tệp đó, trong đó private key phải giới hạn ở `root:tnassistant` với mode `0640` hoặc chặt hơn. Không bật chế độ Gateway trước khi có host Việt Nam được cấp phép, chứng thư, DNS và kiểm chứng kết quả Zalo thật. Chế độ mặc định vẫn là `direct` cho đến khi chuyển production có kiểm soát.

## Dữ liệu và vận hành

SQLite mặc định ở `data/tn-assistant.db`; production dùng `/var/lib/tn-assistant/tn-assistant.db`. Database bật WAL, foreign keys và busy timeout. Không thay DB production bằng fixture hoặc bản backup cũ; dùng backup nhất quán trước migration và kiểm tra `PRAGMA integrity_check` sau phục hồi.

`reset-admin` chỉ cập nhật admin hiện có, đọc mật khẩu từ stdin, băm bcrypt cost 12 và thu hồi phiên trong cùng transaction:

```bash
systemctl stop tn-assistant
read -r -s -p 'New administrator password: ' TN_RESET_PASSWORD
printf '%s\n' "$TN_RESET_PASSWORD" | /srv/tn-assistant/current/tn-assistant reset-admin --db /var/lib/tn-assistant/tn-assistant.sqlite --username admin --password-stdin
unset TN_RESET_PASSWORD
systemctl start tn-assistant
```

Đăng ký tài khoản TN không cho client tự chọn role hoặc gói Premium. Quyền Premium chỉ được cấp bằng luồng admin/mã kích hoạt hiện có. Refresh token được xoay vòng; hành động thiết bị phải gắn với tài khoản và ID hành động đã phát hành.

## API và dịch vụ giọng nói

Hợp đồng OpenAPI ở `../api/openapi.json`; hợp đồng nghiệp vụ ở `../api/CONTRACT.md`. Health và readiness có ngữ nghĩa riêng: health xác nhận tiến trình, readiness báo tính năng thiếu cấu hình. Kiểm tra worker bằng inference thật; trạng thái service hoặc fixture không chứng minh độ chính xác.

Worker ASR/TTS chỉ nghe loopback tại 8091/8092. ASR nhận PCM16 mono 16 kHz, tối đa 15 giây. TTS dùng giọng TN đã cấu hình. Worker không được phơi cổng công khai; dùng các lockfile trong `deploy/` để dựng môi trường.

## Giới hạn hiện tại

Các luồng tự động chưa có nguồn/API hợp lệ được trả về là chưa sẵn sàng hoặc unsupported; không mở website thay thế, không giả kết quả. Dữ liệu giao thông chỉ được trả lời khi có bản OSM kèm nguồn và thời điểm cập nhật. Đăng nhập Zalo, Gemini, nhận dạng giọng nói, wake phrase và thao tác trên thiết bị cần được nghiệm thu riêng trước khi bật cho người dùng.

## Cloud speech providers

Deepgram integration is server-side only. Set `TN_ASR_PROVIDER=deepgram`, `TN_DEEPGRAM_API_KEY`, and `TN_DEEPGRAM_ENABLED=true` after disabling account auto-reload or confirming that no payment card is attached. The adapter uses Nova-3 Vietnamese (`language=vi`), preserves the existing `ready/partial/final` WebSocket contract, and caps streams at 15 seconds with two concurrent HTTP/streaming requests. It reserves a default 120 audio minutes per UTC month and a fixed lifetime ceiling of 1,000 minutes in SQLite; configure `TN_DEEPGRAM_MONTHLY_AUDIO_MINUTES` to change only the monthly cap. Deepgram's advertised new-account credits are one-time, not recurring. These are application limits, not account billing controls; other API consumers use the account's credit independently.

Deepgram Aura does not currently list Vietnamese among supported TTS languages. Optional Azure Speech TTS uses `TN_TTS_PROVIDER=azure`, `TN_AZURE_SPEECH_REGION`, and `TN_AZURE_SPEECH_KEY`; it pins `vi-VN-HoaiMyNeural` and caps usage at 450,000 characters per UTC month. Keep secrets only in the protected server environment file. With no cloud-provider selector, the current local worker remains the fallback; a selected but misconfigured cloud provider fails closed and does not silently switch providers.

Set `TN_TTS_PROVIDER=disabled` when removing local synthesis before a cloud voice has been configured. TTS then returns `TTS_NOT_CONFIGURED`, and readiness remains false. Local ASR/TTS services must be stopped and disabled before deleting their verified model directories. User data and generated published news audio live outside model directories and must be preserved.
