# Kế hoạch hoàn thiện TN Assistant

Phạm vi: Android độc lập, đối chiếu reference hiện có trong Kiki_Auto; Basic và Premium, không thanh toán thật. Chỉ nghiệm thu Android SDK emulator theo yêu cầu chủ ứng dụng. Không suy diễn độ tương thích đầu xe thật từ emulator, không quy đổi số test thành phần trăm hoàn thành.

## Đợt hỗ trợ .196

- Sáu mục hỗ trợ đọc cấu hình VPS: QR đúng URL, hai tài liệu WebView, thiếu cấu hình/lỗi/retry/đóng rõ ràng.
- Năm trang tĩnh TN: FAQ, liên hệ, khiếu nại, khách hàng dễ tổn thương và micro. Facebook chưa có kênh TN được xác nhận. Email trong source chưa được nghiệm thu nhận/gửi.
- Backend sửa trạng thái danh bạ thành có bộ xử lý Android nhưng cần quyền và xác nhận; không coi đó là nghiệm thu toàn bộ danh bạ.
- Hai smoke call thành công ngày 13/11/2026. Không tự gọi thêm provider để giữ readiness xanh; phân biệt hết hạn bằng chứng với dịch vụ lỗi.
- Bổ sung rebuild ONNX JNI x86_64 với PT_LOAD 16 KB; kiểm tra exports và nghiệm thu lại trên emulator trước phát hành.

## Các bước còn lại

### Dữ liệu đường và màn hình vị trí — đang triển khai

- Backend bổ sung danh sách đoạn đường có hướng, giữ cả giới hạn chưa biết và báo rõ kết quả bị cắt hoặc bản ghi hỏng. Hợp đồng cũ vẫn được giữ.
- Importer OSM xuất hai đầu đoạn đường và điểm giữa; không nối qua node bị thiếu, không suy đoán giới hạn có điều kiện. Dữ liệu điểm cũ cần nhập lại từ hình học nguồn, không tự dựng đoạn từ các điểm gần nhau.
- Android có màn hình xem giới hạn theo vị trí khi người dùng chủ động mở và cấp quyền chính xác. Bộ chọn đoạn từ chối vị trí cũ/sai số lớn, hướng không rõ, đường song song, dữ liệu cũ hoặc chưa biết tốc độ. Đóng màn hình hoặc rời activity sẽ dừng phiên.
- Backend `20261113-road-radius` đã triển khai sau test/vet và staging cô lập. Truy vấn managed hỗ trợ `radius_m` 100–2.500 m; Android dùng 150 m và loại cache khi vị trí ra ngoài vùng có đủ khoảng dự phòng. Health/readiness đạt khi hậu kiểm ngày 14/11/2026, không gọi thêm provider; tài khoản, cấu hình riêng, webroot và APK phát hành được giữ nguyên.
- VPS đã nhập 268.109 đoạn OSM trong bbox `106.65,10.72,106.75,10.84` tại TP.HCM: 24.955 đoạn có tốc độ rõ ràng, 243.154 đoạn chưa biết; không có camera/biển cấm trong lô này. Nguồn Geofabrik, mốc `2026-11-12T21:23:05Z`, ODbL 1.0. Bản nhập có checksum, backup và kiểm tra các bảng khác không thay đổi. Đây là dữ liệu vùng thử nghiệm, không phải độ phủ toàn quốc.
- Dữ liệu thật đã qua API backend cô lập (Basic 403, Premium 200, truy vấn 150 m trả đủ 154 ứng viên) và replay bộ đối chiếu Android trên emulator, nhận đúng đoạn `osm-way-605133464-4-0-backward` với giới hạn 30 km/h tại điểm kiểm tra. Đây chưa phải kiểm thử GPS hệ điều hành → API production → UI từ đầu đến cuối.
- Công tắc production `driving_enabled=false` được giữ nguyên. Android mới chỉ nằm trong source và bản Preview kiểm thử local; APK công khai vẫn .196. Kết quả: 63 unit test, build/lint Preview, 2 test vòng đời/UI, 1 test replay dữ liệu thật và 2 test GPS/quyền; importer có 8 test. Test GPS đi qua `LocationManager` thật trên emulator, hiển thị 30 km/h từ phản hồi replay, dừng cập nhật sau dismiss; thiếu quyền thì không lấy vị trí/gọi mạng. Quyền và mock-location appop được khôi phục sau test. Không quy đổi số test thành phần trăm parity.
- Bổ sung Âm báo cục bộ khi màn hình vị trí đang mở: dùng ngưỡng đã lưu (-5/0/+5 km/h), cần hai vị trí mới liên tiếp và thời gian nghỉ 5 giây; kết quả lặp từ timer/mạng không được tính thành mẫu mới. Dữ liệu không rõ hoặc dưới ngưỡng dừng âm báo; đóng màn hình giải phóng âm thanh và audio focus. Chế độ Chỉ hiển thị/Không cảnh báo không phát âm thanh. Không gọi TTS/provider.
- Reference `q0/c` cho thấy mặc định hai mẫu và 5 giây; phương thức quyết định trong `d2/g` chưa decompile đầy đủ. Quy tắc bằng ngưỡng, xử lý đổi đường và hai tiếng beep cách nhau 500 ms là hành vi TN đã chọn, chưa được xác nhận tương đương toàn bộ nhịp cảnh báo gốc.
- Kiểm tra bổ sung ngày 14/11/2026: 68 unit test đạt; lint (còn cảnh báo), build Preview/helper đạt; 4 test UI/vòng đời/âm thanh đạt trên SDK emulator API 37. Kiểm tra âm thanh xác nhận khởi phát, dừng, phát lại và không thể phát sau close; chưa đo tín hiệu âm thanh đầu ra. Giọng nói và cảnh báo chạy nền vẫn chưa triển khai trong luồng này.
- Còn phải nghiệm thu thu hồi quyền khi đang chạy và tuyến GPS giả lập với API production, mở rộng nguồn, hoàn thiện camera/biển báo/giọng nói và vòng đời cảnh báo nền trước khi bật và phát hành. Màn hình này chưa thay thế tính năng cảnh báo giao thông của app gốc.

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
