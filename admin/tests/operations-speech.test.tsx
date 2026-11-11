import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { Operations } from "../src/Operations";

const config = {
  revision: 1,
  generation: 1,
  runtime_mode: "independent",
  cost_policy: "free_only",
  legacy_fallback: false,
  asr_url: "http://127.0.0.1:8095",
  tts_url: "http://127.0.0.1:8096",
  tts_voice: "Thùy Dung",
  max_devices: 100,
  speech_queue_limit: 4,
  driving_enabled: false,
  news_enabled: false,
};

const json = (body: unknown) =>
  new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
  });

beforeEach(() => vi.unstubAllGlobals());

it("shows the selected original TTS dependency and preserves runtime configuration on save", async () => {
  let saved: { base_revision: number; config: typeof config } | undefined;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, options: RequestInit) => {
      if (options.method === "PUT") {
        saved = JSON.parse(options.body as string);
        return json({ ...saved!.config, revision: 2 });
      }
      if (url.includes("/runtime/config")) return json(config);
      return json({
        revision: 1,
        generation: 1,
        readiness: {
          ready: true,
          checks: {
            runtime: { status: "independent", healthy: true },
            asr: { provider: "deepgram", healthy: true },
            tts: {
              provider: "original_tts",
              speaker_id: "5",
              endpoint: "https://partner-speech.lab.zalo.ai/tts/stream",
              healthy: true,
            },
          },
        },
        counts: {},
        capabilities: { items: [] },
      });
    }),
  );
  render(<Operations onDirty={() => undefined} notify={() => undefined} />);

  expect(await screen.findByLabelText("Dịch vụ ASR")).toHaveValue(
    "Deepgram · https://api.deepgram.com/v1/listen",
  );
  expect(screen.getByLabelText("Dịch vụ TTS")).toHaveValue(
    "Original TTS · https://partner-speech.lab.zalo.ai/tts/stream",
  );
  expect(screen.getByLabelText("Giọng đọc đang dùng")).toHaveValue(
    "Original TTS · speaker 5",
  );
  expect(screen.queryByDisplayValue("Thùy Dung")).not.toBeInTheDocument();
  expect(screen.getByText("TN + TTS app gốc")).toBeInTheDocument();
  expect(screen.queryByText("Độc lập")).not.toBeInTheDocument();
  expect(
    screen.getByText(/phụ thuộc vào dịch vụ và thông tin xác thực/),
  ).toBeInTheDocument();

  fireEvent.change(screen.getByLabelText("Số thiết bị tối đa"), {
    target: { value: "99" },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Lưu cấu hình vận hành" }),
  );
  await waitFor(() => expect(saved).toBeDefined());
  expect(saved).toEqual({
    base_revision: 1,
    config: { ...config, max_devices: 99 },
  });
});
