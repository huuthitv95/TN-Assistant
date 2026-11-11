---
version: alpha
name: Kiki Auto quản trị
description: Không gian vận hành thiết bị và trợ lý giọng nói với nhận diện Kiki gốc.
colors:
  navy: "#021f4e"
  primary: "#0866c6"
  cyan: "#49d6ef"
  background: "#f4f7fb"
  surface: "#ffffff"
  text: "#152f50"
  muted: "#586d85"
  border: "#dce5ef"
  danger: "#b42337"
  success: "#187248"
  warning: "#88591b"
  focus: "#087ac4"
typography:
  sans:
    fontFamily: "Barlow, Segoe UI, sans-serif"
  mono:
    fontFamily: "Consolas, monospace"
rounded:
  DEFAULT: "10px"
  sm: "6px"
  lg: "14px"
spacing:
  section-gap: "24px"
  page-max: "1600px"
components:
  button: { backgroundColor: "{colors.primary}", textColor: "{colors.surface}" }
  field: { textColor: "{colors.text}" }
  field-help: { textColor: "{colors.muted}" }
  table: { backgroundColor: "{colors.surface}", textColor: "{colors.text}" }
  dialog: { backgroundColor: "{colors.surface}", textColor: "{colors.text}" }
  notice: { textColor: "{colors.text}" }
  notice-error: { textColor: "{colors.danger}" }
  notice-success: { textColor: "{colors.success}" }
  notice-warning: { textColor: "{colors.warning}" }
  navigation: { backgroundColor: "{colors.navy}" }
  navigation-signal: { textColor: "{colors.cyan}" }
---

# Kiki Auto Design System

## Overview

### Creative North Star

Một trạm điều hành giọng nói trên xe: thanh điều hướng xanh navy bền vững, tín hiệu cyan chỉ dẫn, bảng dữ liệu sáng dễ đọc. Dấu nhận diện ki/ki thể hiện hai bước nghe và phản hồi; không dùng biểu đồ hoặc số liệu giả làm trang trí.

### Product context and register

Quản trị viên tại Việt Nam quản lý 1–10 thiết bị trên Windows và trình duyệt máy tính. Toàn bộ giao diện Việt ngữ, lịch Gregorian, giờ Asia/Ho_Chi_Minh. Đây là product/admin; không áp dụng layout trang quảng cáo. Màn hình điện thoại vẫn có thể xem và thao tác các bảng.

Nguồn nghiệp vụ: `../api/CONTRACT.md`, `../implementation-plan.md`. Dashboard mới kế thừa palette và font Barlow của APK, không tuyên bố dashboard tồn tại trong app gốc. Dấu ki/ki và sidebar navy là điểm biểu đạt duy nhất; bảng, biểu mẫu và trạng thái giữ công dụng rõ ràng.

### Token ownership/runtime mapping

Model B: `src/style.css :root` sở hữu runtime tokens. Frontmatter phản chiếu token cùng tên (`colors.primary` → `--primary`, v.v.); mọi shared primitive tiêu thụ CSS variables. Body dùng `--font-body`; dữ liệu kỹ thuật dùng `--font-mono`. Màu riêng cho hover và nền semantic được định nghĩa duy nhất trong stylesheet. Không copy màu vào screen TSX. `tests/design.test.ts` xác nhận token chính không lệch.

## Colors

Navy cho điều hướng và thương hiệu, primary cho thao tác chính, cyan trên navy cho dấu nhận diện. Surface trắng và nền xanh xám hỗ trợ bảng; text/muted giữ phân cấp. Danger/success/warning luôn kèm chữ. Focus rõ trên cả nền sáng và tối. Chỉ có theme sáng; forced colors dùng màu hệ thống.

## Typography

Barlow regular/semibold từ tài nguyên gốc, hỗ trợ tiếng Việt. Heading 34px/600 desktop và 28px mobile; body 16px/1.5; bảng 13px, nhãn 13px/600. Consolas cho mã và JSON. Không tải font từ máy chủ bên ngoài.

## Layout

Sidebar 244px desktop, content tối đa 1600px, gap 24px, bảng cuộn riêng tối đa 620px. Biểu mẫu dùng chiều cao tự nhiên. Tại 700px sidebar chuyển thành điều hướng mở/đóng, bảng cuộn ngang; không giấu cột hay thao tác. Metrics bốn cột chuyển hai cột. Loading/error/empty giữ chiều cao vùng dữ liệu tối thiểu 290px.

## Elevation & Depth

Card/bảng dùng border, không đổ bóng. Modal và toast có bóng để thể hiện lớp nổi; backdrop modal navy mờ. Topbar/heading không dùng blur.

## Shapes

Control 6–7px, panel 10px, dialog 14px. Badge nhỏ, avatar tròn. Chỉ dùng bo tròn cho phân nhóm thực sự.

## Components

Button có primary/neutral/danger, min-height 40px, icon bên trái, hover và focus-visible. Busy giữ nhãn và thêm spinner; disabled không tương tác. Input/textarea giữ label, lỗi và help slot. Native select chấp nhận OS popup; ngày nhập chuỗi ISO không có popup.

Navigation dùng liên kết, bảng semantic th/td, trạng thái có tên. Dialog dùng native `<dialog>.showModal()` để có focus trap và inert nền; Escape đi qua xác nhận dữ liệu chưa lưu. Toast dùng live region, không thay thế lỗi inline. Lucide icon 16–24px luôn kèm chữ cho thao tác chính. Spinner là tín hiệu công việc, tắt chuyển động theo reduced-motion; không thêm animation ambient.

Nội dung dùng động từ nhất quán: Tạo mới, Lưu thay đổi, Xóa bản ghi, Thử lại. Không gọi dữ liệu nhà cung cấp đã cấu hình là API đã hoạt động. Số và thời gian theo vi-VN, không có biểu đồ thay thế bảng thật.

## Do's and Don'ts

- Giữ hàng, nhãn và thao tác đọc được ngay trong dữ liệu dài.
- Giữ lỗi tại biểu mẫu và nhập liệu sau yêu cầu thất bại.
- Không gọi API riêng bằng khóa trích từ APK.
- Không hiển thị sandbox như giao dịch tiền thật.
