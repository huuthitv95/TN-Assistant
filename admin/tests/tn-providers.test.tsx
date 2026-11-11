import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { AiProviders } from "../src/AiProviders";

const provider = {
  id: "fixture-local",
  kind: "custom",
  model: "fixture",
  endpoint: "http://127.0.0.1:9991",
  enabled: true,
  priority: 0,
  max_requests_per_day: 100,
  max_output_tokens: 1024,
  key_configured: true,
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
beforeEach(() => vi.unstubAllGlobals());

it("twenty save failures keep one retry and preserve the provider draft", async () => {
  let writes = 0,
    reads = 0;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url: string, options: RequestInit) => {
      if (options.method === "PUT") {
        writes++;
        return json(
          { error: { code: "TEST_DOWN", message: "Fixture unavailable" } },
          503,
        );
      }
      reads++;
      return json({ providers: [provider] });
    }),
  );
  render(<AiProviders onDirty={() => undefined} notify={() => undefined} />);
  const model = await screen.findByLabelText("Model 1");
  fireEvent.change(model, { target: { value: "draft-kept" } });
  fireEvent.click(screen.getByRole("button", { name: "Lưu cấu hình AI" }));
  for (let i = 1; i <= 20; i++) {
    await waitFor(() => expect(writes).toBe(i));
    expect(
      await screen.findAllByRole("button", { name: "Thử lại" }),
    ).toHaveLength(1);
    expect(
      screen.getAllByRole("button", { name: "Lưu cấu hình AI" }),
    ).toHaveLength(1);
    expect(model).toHaveValue("draft-kept");
    if (i < 20)
      fireEvent.click(screen.getByRole("button", { name: "Thử lại" }));
  }
  expect(reads).toBe(1);
});

it("same-tick repeated save clicks send one write and never render the saved secret", async () => {
  let writes = 0;
  let complete: (value: Response) => void = () => undefined;
  vi.stubGlobal(
    "fetch",
    vi.fn(async (_url: string, options: RequestInit) => {
      if (options.method === "PUT") {
        writes++;
        return new Promise<Response>((resolve) => {
          complete = resolve;
        });
      }
      return json({ providers: [provider] });
    }),
  );
  render(<AiProviders onDirty={() => undefined} notify={() => undefined} />);
  const key = await screen.findByLabelText("Khóa API 1");
  expect(key).toHaveValue("");
  fireEvent.change(key, { target: { value: "TEST_ONLY_NEW_KEY" } });
  const save = screen.getByRole("button", { name: "Lưu cấu hình AI" });
  for (let i = 0; i < 20; i++) fireEvent.click(save);
  expect(writes).toBe(1);
  complete(json({ providers: [provider] }));
  await waitFor(() => expect(key).toHaveValue(""));
  expect(screen.queryByText("TEST_ONLY_NEW_KEY")).not.toBeInTheDocument();
});
