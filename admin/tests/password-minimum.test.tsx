import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import App from "../src/App";
import { setSession } from "../src/api";

beforeEach(() => {
  setSession({
    access_token: "FIXTURE_ACCESS",
    refresh_token: "FIXTURE_REFRESH",
    expires_in: 3600,
    user: { id: "fixture-admin", name: "Fixture admin", role: "admin" },
    device: null,
  });
  window.location.hash = "/users";
});

afterEach(() => {
  setSession(null);
  vi.unstubAllGlobals();
});

it.each([
  ["ASCII", "fixture-9", "fixture-10"],
  ["Unicode", "😀".repeat(9), "😀".repeat(10)],
])(
  "requires ten %s characters when changing a user password",
  async (_, short, accepted) => {
    const fixtureUser = {
      id: "fixture-user",
      username: "fixture-user",
      name: "Fixture user",
      role: "user",
      device_limit: 3,
    };
    const updates: Record<string, unknown>[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_path: string, options?: RequestInit) => {
        if (options?.method === "PUT") {
          const body = JSON.parse(String(options.body));
          updates.push(body);
          return Response.json({ ...fixtureUser, ...body });
        }
        return Response.json({ items: [fixtureUser] });
      }),
    );
    render(<App />);
    await userEvent.click(
      await screen.findByRole("button", { name: "Sửa Fixture user" }),
    );
    const dialog = screen.getByRole("dialog");
    const password = within(dialog).getByLabelText("Mật khẩu mới (tùy chọn)");
    expect(within(dialog).getByText(/Ít nhất 10 ký tự/)).toBeInTheDocument();
    fireEvent.change(password, { target: { value: short } });
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Lưu thay đổi" }),
    );
    expect(
      within(dialog).getByText("Mật khẩu mới cần ít nhất 10 ký tự."),
    ).toBeInTheDocument();
    expect(updates).toHaveLength(0);
    fireEvent.change(password, { target: { value: accepted } });
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Lưu thay đổi" }),
    );
    await waitFor(() => expect(updates).toHaveLength(1));
    expect(updates[0].password).toBe(accepted);
  },
);
