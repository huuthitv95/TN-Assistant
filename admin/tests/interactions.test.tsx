import { render, screen, within, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, it, expect, vi } from "vitest";
import App from "../src/App";
import { getSession, setSession, api, type Session } from "../src/api";
const TEST_SESSION: Session = {
  device: null,
  access_token: "TEST_ACCESS",
  refresh_token: "TEST_REFRESH",
  expires_in: 3600,
  user: { id: "test-admin", name: "Quản trị thử nghiệm", role: "admin" },
};
const TEST_PRODUCT = {
  id: "TEST_PRODUCT",
  name: "Gói kiểm thử",
  description: "Fixture test only",
  price: 100,
  currency: "VND",
  duration_days: 30,
  features: ["voice"],
};
function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
beforeEach(() => {
  setSession(null);
  window.location.hash = "/overview";
  vi.unstubAllGlobals();
});
describe("Admin interaction with TEST API fixtures (not production data)", () => {
  it("guards editor cancel and retains unsaved draft", async () => {
    setSession(TEST_SESSION);
    window.location.hash = "/products";
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => json({ items: [] })),
    );
    render(<App />);
    await screen.findByText("Chưa có dữ liệu");
    await userEvent.click(screen.getByRole("button", { name: "Tạo mới" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.type(
      within(dialog).getByLabelText(/Tên gói/),
      "Bản nháp TEST",
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "Hủy" }));
    expect(
      within(dialog).getByText("Bỏ thay đổi chưa lưu?"),
    ).toBeInTheDocument();
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Tiếp tục chỉnh sửa" }),
    );
    expect(within(dialog).getByLabelText(/Tên gói/)).toHaveValue(
      "Bản nháp TEST",
    );
  });
  it("guards browser navigation away from configuration changes", async () => {
    setSession(TEST_SESSION);
    window.location.hash = "/config";
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => json({ welcome_enabled: true })),
    );
    render(<App />);
    const input = await screen.findByLabelText("Cấu hình ứng dụng (JSON)");
    await userEvent.clear(input);
    await userEvent.type(input, '{{"welcome_enabled":false}');
    window.location.hash = "/overview";
    const dialog = await screen.findByRole("dialog");
    expect(
      within(dialog).getByRole("heading", { name: "Bỏ thay đổi chưa lưu?" }),
    ).toBeInTheDocument();
    expect(window.location.hash).toBe("#/config");
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Tiếp tục chỉnh sửa" }),
    );
    expect(input).toHaveValue('{"welcome_enabled":false}');
  });
  it("presents read failure with retry and returns to login when refresh rejected", async () => {
    setSession(TEST_SESSION);
    window.location.hash = "/history";
    let gets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) => {
        if (path === "/v1/auth/refresh")
          return json(
            { error: { code: "EXPIRED", message: "Phiên đã hết hạn" } },
            401,
          );
        gets++;
        return gets === 1
          ? json(
              {
                error: {
                  code: "UNAVAILABLE",
                  message: "Lịch sử chưa truy cập được",
                },
              },
              503,
            )
          : json(
              { error: { code: "EXPIRED", message: "Phiên đã hết hạn" } },
              401,
            );
      }),
    );
    render(<App />);
    expect(
      await screen.findByText("Lịch sử chưa truy cập được"),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Thử lại" }));
    expect(
      await screen.findByRole("button", { name: /Đăng nhập quản trị/ }),
    ).toBeInTheDocument();
    expect(getSession()).toBeNull();
  });
  it("requires authentication and presents field validation", async () => {
    render(<App />);
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: /Đăng nhập quản trị/ }),
    );
    expect(screen.getAllByText("Vui lòng nhập thông tin này.")).toHaveLength(2);
    expect(document.getElementById("field-username")).toHaveFocus();
  });
  it("logs in only administrator and masks/reveals password", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string) =>
        path === "/v1/auth/login"
          ? json(TEST_SESSION)
          : json(
              path === "/v1/health"
                ? { providers: {} }
                : { users: 1, devices: 0, payments: 0, diagnostics: 0 },
            ),
      ),
    );
    render(<App />);
    await userEvent.type(screen.getByLabelText(/Tên đăng nhập/), "admin");
    await userEvent.type(screen.getByLabelText(/^Mật khẩu/), "test-password");
    expect(screen.getByLabelText(/^Mật khẩu/)).toHaveAttribute(
      "type",
      "password",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Hiện mật khẩu" }),
    );
    expect(screen.getByLabelText(/^Mật khẩu/)).toHaveAttribute("type", "text");
    await userEvent.click(
      screen.getByRole("button", { name: /Đăng nhập quản trị/ }),
    );
    expect(await screen.findByRole("navigation")).toBeInTheDocument();
    expect(getSession()?.user.id).toBe("test-admin");
    expect(localStorage.length).toBe(0);
  });
  it("preserves values after create failure, then refetches after successful save", async () => {
    setSession(TEST_SESSION);
    window.location.hash = "/products";
    let posts = 0;
    let gets = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string, options: RequestInit) => {
        if (options.method === "POST") {
          posts++;
          return posts === 1
            ? json(
                {
                  error: { code: "FAIL", message: "Kiểm thử máy chủ thất bại" },
                },
                500,
              )
            : json(TEST_PRODUCT);
        }
        gets++;
        return json({ items: posts > 1 ? [TEST_PRODUCT] : [] });
      }),
    );
    render(<App />);
    expect(await screen.findByText("Chưa có dữ liệu")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Tạo mới" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText(/^Mã/), "TEST_PRODUCT");
    await userEvent.type(
      within(dialog).getByLabelText(/Tên gói/),
      "Gói kiểm thử",
    );
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Lưu thay đổi" }),
    );
    expect(
      await within(dialog).findByText("Kiểm thử máy chủ thất bại"),
    ).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/Tên gói/)).toHaveValue(
      "Gói kiểm thử",
    );
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Lưu thay đổi" }),
    );
    expect(await screen.findByText("Gói kiểm thử")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(gets).toBeGreaterThan(1);
    expect(posts).toBe(2);
  });
  it("requires confirmation before delete and clamps local pagination", async () => {
    setSession(TEST_SESSION);
    window.location.hash = "/products";
    let removed = false;
    const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
      if (options.method === "DELETE") {
        removed = true;
        return new Response(null, { status: 204 });
      }
      return json({ items: removed ? [] : [TEST_PRODUCT] });
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<App />);
    await screen.findByText("Gói kiểm thử");
    await userEvent.click(
      screen.getByRole("button", { name: "Xóa Gói kiểm thử" }),
    );
    expect(
      fetchMock.mock.calls.some(([, options]) => options.method === "DELETE"),
    ).toBe(false);
    const dialog = screen.getByRole("dialog");
    expect(
      within(dialog).getByText(/không có chức năng hoàn tác/),
    ).toBeInTheDocument();
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Xóa bản ghi" }),
    );
    expect(await screen.findByText("Chưa có dữ liệu")).toBeInTheDocument();
    expect(removed).toBe(true);
  });
  it("clears search immediately and paginates bounded tables", async () => {
    setSession(TEST_SESSION);
    window.location.hash = "/products";
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        json({
          items: Array.from({ length: 25 }, (_, i) => ({
            ...TEST_PRODUCT,
            id: `TEST_${i}`,
            name: `Gói ${i}`,
          })),
        }),
      ),
    );
    render(<App />);
    await screen.findByText("Gói 0");
    expect(screen.queryByText("Gói 24")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Trang sau" }));
    expect(await screen.findByText("Gói 24")).toBeInTheDocument();
    await userEvent.type(screen.getByRole("searchbox"), "không tồn tại");
    await userEvent.keyboard("{Enter}");
    expect(
      await screen.findByText("Không tìm thấy kết quả"),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Xóa tìm kiếm" }));
    expect(await screen.findByText("Gói 0")).toBeInTheDocument();
    expect(screen.getByRole("searchbox")).toHaveFocus();
  });
  it("coalesces refresh and never persists refresh tokens", async () => {
    setSession(TEST_SESSION);
    let refreshes = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (path: string, options: RequestInit) => {
        if (path === "/v1/auth/refresh") {
          refreshes++;
          return json({ ...TEST_SESSION, access_token: "NEW_TEST_ACCESS" });
        }
        const headers = options.headers as Record<string, string>;
        return headers.Authorization === "Bearer NEW_TEST_ACCESS"
          ? json({ ok: true })
          : json({ error: { code: "EXPIRED", message: "expired" } }, 401);
      }),
    );
    await Promise.all([api("/v1/admin/users"), api("/v1/admin/products")]);
    expect(refreshes).toBe(1);
    expect(sessionStorage.length).toBe(0);
  });
});
