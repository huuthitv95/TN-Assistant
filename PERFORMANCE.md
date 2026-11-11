# Số đo thực tế trên máy bàn giao

Khởi động/memory là snapshot baseline 26100201 trước sửa LDPlayer; system/native là lần chạy C1.1 mới nhất. Không so hai môi trường mạng/cấu hình khác nhau để kết luận cải thiện. Chưa chạy soak hay đạt ngưỡng C1.4.

Backend Windows: 10 phiên mô phỏng, 210 HTTP requests; p50 6.71 ms, p95 14.73 ms, max 17.07 ms; toàn đợt 195.54 ms. Loopback, DB kiểm thử riêng; không gồm độ trễ nhà cung cấp.

LDPlayer Android 28, native bridge `libnb.so`: 5 cold launches `[435, 460, 470, 429, 440]` ms, median 440 ms. Snapshot idle PSS 58.22 MiB; đây không phải peak memory khi nhận dạng/TTS. Giá trị lấy từ `dumpsys meminfo` trong `reports/device-check.json`.

JNI ARM64 thật: native/model init 469.94 ms; 20 frame silent × 1024 samples (64 ms/frame): p50 4.67 ms, p95 15.90 ms, max 77.22 ms. Percentile nearest-rank, n=20. WAV chẩn đoán gốc 12719 samples, PCM16 mono 16 kHz, VAD frames 24; wake-word detection `false` không phải accuracy do WAV không có nhãn ground truth.

Max frame vượt 64 ms trong lần đo. Chưa có baseline original runtime, chạy dài hạn, giọng nói thật được gán nhãn hoặc đầu xe ARM64; không khẳng định nhanh hơn app gốc hay bảo đảm realtime trên xe. Không có số đo latency upstream vì chưa cấu hình API riêng.

Bằng chứng: `reports/system-check.json`, `reports/device-check.json`, `reports/native-audio-timing.json`, `reports/native-instrumentation.txt`. Không trộn fixture API với phép đo nhà cung cấp thật.
