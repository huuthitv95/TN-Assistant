import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { NewsAudioAction } from "../src/ManagedActions";

beforeEach(() => vi.unstubAllGlobals());
const json = (body: unknown) =>
  new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
  });

it("twenty same-tick audio clicks create one request and refresh the record once ready", async () => {
  let finish: (r: Response) => void = () => undefined;
  const fetcher = vi.fn(
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve;
      }),
  );
  vi.stubGlobal("fetch", fetcher);
  const ready = vi.fn();
  render(<NewsAudioAction id="TEST_NEWS" onReady={ready} />);
  const button = screen.getByRole("button", { name: "Tạo audio" });
  for (let i = 0; i < 20; i++) fireEvent.click(button);
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(ready).not.toHaveBeenCalled();
  finish(
    json({
      id: "TEST_JOB",
      status: "ready",
      completed_chunks: 3,
      total_chunks: 3,
    }),
  );
  await waitFor(() => expect(ready).toHaveBeenCalledTimes(1));
  expect(await screen.findByRole("status")).toHaveTextContent(
    "Audio đã sẵn sàng 3/3",
  );
});

it("a response after navigating away cannot refresh another page", async () => {
  let finish: (r: Response) => void = () => undefined;
  vi.stubGlobal(
    "fetch",
    vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    ),
  );
  const ready = vi.fn();
  const view = render(<NewsAudioAction id="TEST_NEWS" onReady={ready} />);
  fireEvent.click(screen.getByRole("button", { name: "Tạo audio" }));
  view.unmount();
  finish(json({ id: "TEST_JOB", status: "ready" }));
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(ready).not.toHaveBeenCalled();
});
