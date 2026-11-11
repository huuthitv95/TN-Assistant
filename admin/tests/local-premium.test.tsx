import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import App from "../src/App";
import { setSession } from "../src/api";

beforeEach(() => {
  setSession({
    access_token: "TEST_ACCESS",
    refresh_token: "TEST_REFRESH",
    expires_in: 3600,
    user: { id: "test-admin", name: "Test admin", role: "admin" },
    device: null,
  });
  window.location.hash = "/users";
});
afterEach(() => {
  setSession(null);
  vi.unstubAllGlobals();
});

it("grants and revokes local rights for the selected user, preserving failures for retry", async () => {
  let enabled = false;
  let fail = true;
  const mutations: Array<{ path: string; method: string; body: unknown }> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (path: string, options?: RequestInit) => {
      const method = options?.method ?? "GET";
      if (path === "/v1/admin/users")
        return Response.json({
          items: [{ id: "TEST_USER", name: "Người dùng test", role: "user" }],
        });
      expect(path).toBe("/v1/admin/premium-local/TEST_USER");
      if (method !== "GET") {
        mutations.push({
          path,
          method,
          body: options?.body ? JSON.parse(String(options.body)) : null,
        });
        if (fail) {
          fail = false;
          return Response.json(
            { error: { code: "UNAVAILABLE", message: "Không lưu được quyền" } },
            { status: 503 },
          );
        }
        enabled = method === "PUT";
      }
      return Response.json({ enabled, local: true, upstream_granted: false });
    }),
  );
  render(<App />);
  await userEvent.click(
    await screen.findByRole("button", { name: "Premium local" }),
  );
  let dialog = await screen.findByRole("dialog");
  await within(dialog).findByText("Premium local đang tắt.");
  await userEvent.click(
    within(dialog).getByRole("button", { name: "Cấp Premium local" }),
  );
  await within(dialog).findByText("Không lưu được quyền");
  expect(within(dialog).getByRole("spinbutton")).toHaveValue(30);
  await userEvent.click(
    within(dialog).getByRole("button", { name: "Cấp Premium local" }),
  );
  await screen.findByText(/Đã cấp Premium local/);
  expect(mutations[1]).toMatchObject({
    method: "PUT",
    body: { duration_days: 30 },
  });
  await userEvent.click(screen.getByRole("button", { name: "Premium local" }));
  dialog = await screen.findByRole("dialog");
  await userEvent.click(
    await within(dialog).findByRole("button", { name: "Thu hồi local" }),
  );
  await screen.findByText("Đã thu hồi Premium local.");
  expect(mutations[2]).toMatchObject({ method: "DELETE", body: null });
});
