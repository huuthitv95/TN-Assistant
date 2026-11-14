# Lời cảnh báo tốc độ TN

Hai file `tn_road_overspeed_south.mp3` và `tn_road_overspeed_north.mp3` được tạo từ câu TN tự viết: “Chú ý tốc độ. Bạn đã đạt ngưỡng cảnh báo.” Câu này phù hợp cả ba ngưỡng -5/0/+5 km/h; không khẳng định người dùng đã vượt giới hạn pháp lý khi chọn ngưỡng thấp hơn.

- Miền Nam mặc định: preset nữ **Thùy Dung**.
- Miền Bắc theo lựa chọn `agent_voice=north`: preset nữ **Ngọc Huyền**.
- Không sao chép audio của ứng dụng gốc và không clone giọng người dùng.
- Phát từ tài nguyên APK, không gửi GPS/text tới TTS và không dùng quota khi cảnh báo.
- Đây là lời cảnh báo TN; chưa có bằng chứng tương đương nguyên văn/âm sắc với app gốc.

Nguồn model và preset: [VieNeu-TTS-v3-Turbo](https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Turbo), Apache-2.0; SDK [VieNeu-TTS](https://github.com/pnnbao97/VieNeu-TTS). Model không được đóng gói vào APK. Phiên bản model, SDK, preset, thời lượng và SHA-256 của từng audio nằm trong `road-prompts.json`.

Tái tạo với Python 3.12, `vieneu==3.8.3`, `huggingface-hub==1.33.0`, `onnxruntime==1.30.0` và FFmpeg trong PATH:

```text
python deploy/prepare_speech.py --kind tts --root <model-directory-outside-repo>
python scripts/generate_road_prompts.py --model-root <model-directory-outside-repo>
```

Bước chuẩn bị tải model công khai. Bước tạo prompt chạy offline, kiểm tra model pin, preset và dạng sóng; chuẩn hoá peak 0,85 rồi mã hoá MP3 mono 96 kbps. Inference có tính ngẫu nhiên nên lần tạo lại có thể khác checksum; cần nghe và nghiệm thu lại audio trước phát hành. Bộ phát huỷ lời chưa chuẩn bị xong sau 2 giây, không xếp hàng, dừng khi dữ liệu mất hiệu lực/đổi chế độ/đổi giọng/đóng phiên và giải phóng audio focus.

Kiểm tra nội dung tự động trên hai MP3 hiện tại bằng Whisper-small (decode MP3 → PCM float32 mono 16 kHz qua FFmpeg) chưa khớp nguyên văn: bản Nam nhận “mà” thay “bạn”, bản Bắc nhận “cành báo” thay “cảnh báo”. Đây có thể là lỗi ASR hoặc phát âm; chưa có nghe nghiệm thu để phân biệt. Các test MediaPlayer/lifecycle đạt chỉ chứng minh phát/dừng tài nguyên, không chứng minh chất lượng giọng hoặc parity âm sắc.
