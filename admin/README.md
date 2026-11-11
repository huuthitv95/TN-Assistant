# Kiki Auto quản trị

Dashboard React/TypeScript dùng dữ liệu API thật, được Go phục vụ tại `/`. Không có dữ liệu mẫu trong giao diện production. Các fixture `TEST_*` chỉ xuất hiện trong test.

Preview .63: trong **Người dùng → Premium local**, chọn số ngày 1–365 để cấp/gia hạn hoặc thu hồi quyền thử nghiệm trên backend rebuilt. Một tài khoản có một quyền local, hết hạn tự tắt; thu hồi không xóa quyền sản phẩm khác. API yêu cầu vai trò admin và ghi lịch sử người thực hiện. `local_premium.upstream_granted=false` luôn được giữ: quyền local không thay quyền của nhà cung cấp gốc. VM dùng `http://127.0.0.1:8088`; tài khoản `admin`, mật khẩu nằm trong `.runtime/virtual-machine63/admin-password.txt`.

```powershell
npm ci
npm run typecheck
npm test
npm run build
```

`npm run dev` chạy Vite; `/v1` mặc định proxy tới backend `127.0.0.1:8094` theo `vite.config.ts`. Có thể ghi đè đích proxy bằng biến môi trường `TN_BACKEND_PROXY_TARGET`. Đăng nhập bằng tài khoản quản trị cấu hình ở backend. Dashboard không lưu password hoặc token vào localStorage/sessionStorage; tải lại trình duyệt phải đăng nhập lại. Credential nhà cung cấp được đặt qua ENV/file backend, màn hình chỉ dùng tham chiếu và cấu hình đã loại bỏ khóa.

Menu quản lý người dùng, thiết bị, sản phẩm, mã kích hoạt, thanh toán sandbox, nội dung, lịch sử, chẩn đoán và bản cập nhật. Các bảng phân trang cục bộ 20 bản ghi do API hiện trả trọn `{items}`; không mô tả đó là server pagination. Tệp release APK cần đặt trong thư mục downloads backend trước khi đăng ký URL và SHA-256.

Các thao tác nguy hiểm có xác nhận; lỗi giữ tại vùng nhập liệu để sửa/thử lại. Mã kích hoạt được thu hồi bằng cách đặt lượt còn lại về 0, không xóa lịch sử. Thanh toán dùng sandbox, không thu tiền. Nhãn “Đã cấu hình” nhà cung cấp chỉ nói cấu hình có mặt, không cam kết API đã được xác minh.

`src/generated/api.ts` do script root sinh từ OpenAPI; không chỉnh thủ công. `DESIGN.md` và `UX-CONTRACT.md` mô tả token, canonical primitives và contract hành vi. Font Barlow lấy từ APK tham chiếu; không cần kết nối mạng để tải font.
