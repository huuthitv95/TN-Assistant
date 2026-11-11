import type { AuthSession } from "./generated/api";
export type RecordValue = Record<string, unknown>;
// Admin sessions have no device; guest sessions carry the linked device.
export type Session = Omit<AuthSession, "device"> & {
  device: AuthSession["device"] | null;
};
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
let session: Session | null = null;
let sessionRevision = 0;
let refreshPromise: Promise<Session> | null = null;
export function setSession(value: Session | null) {
  session = value?.user.role === "admin" ? value : null;
  sessionRevision++;
  refreshPromise = null;
  window.dispatchEvent(new Event("tn-session"));
}
export function getSession() {
  return session;
}
async function raw(
  path: string,
  method: string,
  body: unknown,
  signal?: AbortSignal,
): Promise<Response> {
  const controller = new AbortController();
  const cancel = () => controller.abort(signal?.reason);
  signal?.addEventListener("abort", cancel, { once: true });
  if (signal?.aborted) cancel();
  let timeout = false;
  const timeoutMs = path === "/v1/admin/ai/providers/test" ? 40000 : 20000;
  const timer = window.setTimeout(() => {
    timeout = true;
    controller.abort();
  }, timeoutMs);
  try {
    return await fetch(path, {
      method,
      headers: {
        "Content-Type": "application/json",
        ...(session ? { Authorization: `Bearer ${session.access_token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    });
  } catch (error) {
    if (timeout)
      throw new ApiError(
        "TIMEOUT",
        method === "GET"
          ? "Máy chủ chưa phản hồi sau 20 giây. Thử tải lại."
          : "Chưa xác nhận được kết quả trên máy chủ. Kiểm tra lại danh sách trước khi thực hiện thêm lần nữa.",
        0,
      );
    throw error;
  } finally {
    window.clearTimeout(timer);
    signal?.removeEventListener("abort", cancel);
  }
}
export async function api<T = RecordValue>(
  path: string,
  method = "GET",
  body?: unknown,
  signal?: AbortSignal,
): Promise<T> {
  const revision = sessionRevision;
  const previous = session;
  const requireCurrentSession = () => {
    if (revision !== sessionRevision)
      throw new ApiError(
        "SESSION_CHANGED",
        "Phiên đăng nhập đã thay đổi. Vui lòng thử lại.",
        401,
      );
  };
  let response = await raw(path, method, body, signal);
  requireCurrentSession();
  if (response.status === 401 && previous && !path.startsWith("/v1/auth/")) {
    // Another request may already have rotated this token. Retry that session
    // rather than starting another refresh with a consumed refresh token.
    if (session?.access_token === previous.access_token) {
      if (!refreshPromise) {
        const pending: Promise<Session> = api<Session>(
          "/v1/auth/refresh",
          "POST",
          { refresh_token: previous.refresh_token },
        )
          .then((next) => {
            requireCurrentSession();
            if (
              next.user.role !== "admin" ||
              next.user.id !== previous.user.id
            ) {
              setSession(null);
              throw new ApiError(
                "FORBIDDEN",
                "Administrator role required",
                403,
              );
            }
            // Rotation stays in this login generation. An explicit login or
            // logout uses setSession and invalidates every older response.
            session = next;
            window.dispatchEvent(new Event("tn-session"));
            return next;
          })
          .catch((err) => {
            if (revision === sessionRevision) setSession(null);
            throw err;
          })
          .finally(() => {
            if (refreshPromise === pending) refreshPromise = null;
          });
        refreshPromise = pending;
      }
      await refreshPromise;
    }
    requireCurrentSession();
    response = await raw(path, method, body, signal);
    requireCurrentSession();
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  let data: unknown;
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    throw new ApiError(
      "INVALID_RESPONSE",
      "Máy chủ trả dữ liệu không hợp lệ.",
      response.status,
    );
  }
  requireCurrentSession();
  if (!response.ok) {
    const value = data as { error?: { code?: string; message?: string } };
    if (response.status === 401 && !path.startsWith("/v1/auth/"))
      setSession(null);
    if (
      response.status === 403 &&
      path.startsWith("/v1/admin/") &&
      value.error?.code === "FORBIDDEN"
    )
      setSession(null);
    throw new ApiError(
      value.error?.code ?? "HTTP_ERROR",
      value.error?.message ?? `Yêu cầu không thành công (${response.status}).`,
      response.status,
    );
  }
  return data as T;
}
export function message(error: unknown) {
  return error instanceof ApiError
    ? error.message
    : error instanceof Error && error.name === "AbortError"
      ? "Yêu cầu đã hủy."
      : "Không kết nối được máy chủ. Kiểm tra kết nối rồi thử lại.";
}
export function safeUrl(value: unknown) {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value, window.location.origin);
    return ["http:", "https:"].includes(url.protocol) ? url.href : null;
  } catch {
    return null;
  }
}
export const numberFormat = new Intl.NumberFormat("vi-VN");
export function dateFormat(value: unknown) {
  if (!value) return "—";
  const date = new Date(String(value));
  return Number.isNaN(date.getTime())
    ? String(value)
    : new Intl.DateTimeFormat("vi-VN", {
        dateStyle: "short",
        timeStyle: "short",
        timeZone: "Asia/Ho_Chi_Minh",
      }).format(date);
}
