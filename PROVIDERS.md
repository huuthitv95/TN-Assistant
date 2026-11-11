# Hợp đồng nhà cung cấp

Thư mục `kiki-overview` đã được kiểm kê toàn bộ và quét nội dung các file text đọc được. Có mã client, cấu hình APK và các tham chiếu xác thực; chưa nhận diện file cấu hình riêng cho tài khoản/API mới. Xem `reports/provider-content-discovery.json`; báo cáo không chứa giá trị khóa.

`backend/providers.example.json` giữ endpoint trống để tránh gọi dịch vụ cũ bằng credential nhúng. Điền endpoint/account đã được cấp trong file riêng, dùng `*_env` tham chiếu biến môi trường. `configured` chỉ cho biết cấu hình tối thiểu có mặt, không chứng minh nhà cung cấp chấp nhận app/certificate mới.

| Adapter legacy | Hợp đồng suy ra từ client gốc | Điểm cần xác nhận với API riêng |
|---|---|---|
| ASR không streaming | Multipart `file`, header `token`, trường uid/sampleRate/channels/ext/contentType/dataSignature/clientType/clientVersion/lat/long/layout. dataSignature=MD5 byte audio, layout=`ignored` | URL, token, UID, client type/version, định dạng được chấp nhận |
| ASR streaming | PCM16LE mono 16kHz; query timestamp mili giây, user_id/lbk-client-id/client_app_type/version/lat/long/token; Authorization=SHA256(timestamp+SHA256(uid+secret)) | URL WSS, secret mới, event schema, quy tắc kết thúc và timeout |
| NLU | Form `event` là văn bản nhận dạng; Authorization=`Token <token> UID <uid>`; device_token riêng, asr/nlu_type/micro_source/session_key/session_state/asr_request_id/device_id/car_model_code/locations/lat/lon | Các option/session và credential riêng; `code=0` là thành công; giữ mảng directives theo thứ tự |
| TTS | Form input/apikey/encodeType/speakerId/appSrc/isAndroidBox; sig=MD5(các `key=value` sắp tên key, nối không dấu phân cách, bỏ sig, cộng secret). data.url/retry/timeoutMilSecs/requestId | API key/secret mới, speaker và loại audio/HLS |

Các thuật toán chữ ký trên mô tả client gốc, không phải tài liệu chính thức nhà cung cấp. Mã nguồn tham chiếu: `kiki_streaming/e.java`, `n7/b.java`, `w3/c.java`, `uc/q.java`, `n6/b.java` trong workspace JADX. Đối chiếu smali khi JADX có nhánh không giải mã được. Không thay chữ ký legacy bằng thuật toán khác rồi tuyên bố tương thích.

Provider có timeout và giới hạn body/audio; lỗi upstream được tách khỏi trạng thái thiếu cấu hình. Kiểm thử Go dùng HTTP/WebSocket giả lập trong tiến trình kiểm thử để kiểm tra wire format, signature và cancellation. Những kiểm thử này không được ghi là kiểm thử giọng nói thật. Khi có API riêng, chạy kiểm thử transcript/directive/TTS với audio thật, đo độ trễ upstream riêng và kiểm tra quyền sử dụng package/certificate mới.

Zalo OAuth: đăng ký app/package/certificate bằng tài khoản riêng. Không dùng Zalo AppID/secret nhúng cũ để giả đăng nhập. Liên kết bằng mã/QR do quản trị xác nhận chỉ là nghiệp vụ local, không phải xác nhận danh tính Zalo.

Sau khi cấu hình provider thật và lấy access token của phiên app riêng, có thể chạy kiểm thử HTTP với bản ghi âm thật:

```powershell
$env:TN_TEST_ACCESS_TOKEN = '<access token của phiên kiểm thử riêng>'
python scripts/live_voice_check.py --audio C:\private\recording.wav --expected-text '<transcript đã biết>'
```

WAV phải PCM16 mono 16 kHz. Script yêu cầu ASR/NLU/TTS configured, dùng kết quả thật, không thực thi directive Android và ghi báo cáo riêng trong `.runtime/live-voice-report.json` (loại khỏi ZIP). Kiểm thử này không xác nhận streaming, Android playback hoặc các thao tác trên xe. Script chưa chạy với nhà cung cấp riêng trong lần bàn giao này vì chưa có cấu hình phù hợp.
