import { beforeEach, expect, it, vi } from "vitest";
import { api, getSession, setSession, type Session } from "../src/api";

const administrator: Session = {
  access_token: "TEST_access",
  refresh_token: "TEST_refresh",
  expires_in: 900,
  user: { id: "TEST_admin", name: "Test administrator", role: "admin" },
  device: null,
};
beforeEach(() => {
  vi.unstubAllGlobals();
  setSession(null);
});
it("an admin API denial invalidates cached administrator UI authorization", async () => {
  setSession(administrator);
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            error: {
              code: "FORBIDDEN",
              message: "Administrator role required",
            },
          }),
          { status: 403 },
        ),
    ),
  );
  await expect(api("/v1/admin/ai/providers")).rejects.toThrow(
    "Administrator role required",
  );
  expect(getSession()).toBeNull();
});
it("a provider-specific failure retains admin login and the editable draft", async () => {
  setSession(administrator);
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            error: {
              code: "AI_UPSTREAM_REJECTED",
              message: "Provider rejected key",
            },
          }),
          { status: 403 },
        ),
    ),
  );
  await expect(
    api("/v1/admin/ai/providers/test", "POST", { id: "TEST_provider" }),
  ).rejects.toThrow("Provider rejected key");
  expect(getSession()).toBe(administrator);
});
it("refreshing into a Basic role cannot retain an admin session", () => {
  setSession(administrator);
  setSession({
    ...administrator,
    user: { ...administrator.user, role: "user" },
  });
  expect(getSession()).toBeNull();
});

function deferredResponse() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function response(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status });
}

it("a delayed refresh cannot undo logout or replay the protected request", async () => {
  setSession(administrator);
  const pending = deferredResponse();
  const fetch = vi.fn(async (path: string) =>
    path === "/v1/auth/refresh"
      ? pending.promise
      : response({ error: { code: "UNAUTHORIZED" } }, 401),
  );
  vi.stubGlobal("fetch", fetch);
  const request = api("/v1/admin/users");
  const rejected = expect(request).rejects.toMatchObject({
    code: "SESSION_CHANGED",
  });
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  setSession(null);
  pending.resolve(response({ ...administrator, access_token: "TEST_rotated" }));
  await rejected;
  expect(getSession()).toBeNull();
  expect(fetch).toHaveBeenCalledTimes(2);
});

it("a failed old refresh cannot log out a newly selected administrator", async () => {
  setSession(administrator);
  const pending = deferredResponse();
  const fetch = vi.fn(async (path: string) =>
    path === "/v1/auth/refresh"
      ? pending.promise
      : response({ error: { code: "UNAUTHORIZED" } }, 401),
  );
  vi.stubGlobal("fetch", fetch);
  const request = api("/v1/admin/users");
  const rejected = expect(request).rejects.toMatchObject({
    code: "SESSION_CHANGED",
  });
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));
  const next = {
    ...administrator,
    access_token: "TEST_other_access",
    user: { ...administrator.user, id: "TEST_other_admin" },
  };
  setSession(next);
  pending.resolve(response({ error: { code: "UNAUTHORIZED" } }, 401));
  await rejected;
  expect(getSession()).toBe(next);
  expect(fetch).toHaveBeenCalledTimes(2);
});

it("a delayed denial belongs to the old login and cannot invalidate the new one", async () => {
  setSession(administrator);
  const pending = deferredResponse();
  const fetch = vi.fn(() => pending.promise);
  vi.stubGlobal("fetch", fetch);
  const request = api("/v1/admin/users");
  const rejected = expect(request).rejects.toMatchObject({
    code: "SESSION_CHANGED",
  });
  const next = { ...administrator, access_token: "TEST_new_login" };
  setSession(next);
  pending.resolve(response({ error: { code: "FORBIDDEN" } }, 403));
  await rejected;
  expect(getSession()).toBe(next);
  expect(fetch).toHaveBeenCalledTimes(1);
});

it("concurrent expired requests share one refresh and retry only its rotated token", async () => {
  setSession(administrator);
  const pending = deferredResponse();
  const fetch = vi.fn(async (path: string, init: RequestInit) => {
    if (path === "/v1/auth/refresh") return pending.promise;
    const authorization = (init.headers as Record<string, string>)
      .Authorization;
    return authorization === "Bearer TEST_rotated"
      ? response({ items: [] })
      : response({ error: { code: "UNAUTHORIZED" } }, 401);
  });
  vi.stubGlobal("fetch", fetch);
  const first = api("/v1/admin/users");
  const second = api("/v1/admin/devices");
  await vi.waitFor(() =>
    expect(
      fetch.mock.calls.filter(([path]) => path === "/v1/auth/refresh"),
    ).toHaveLength(1),
  );
  pending.resolve(response({ ...administrator, access_token: "TEST_rotated" }));
  await expect(Promise.all([first, second])).resolves.toEqual([
    { items: [] },
    { items: [] },
  ]);
  expect(
    fetch.mock.calls.filter(([path]) => path === "/v1/auth/refresh"),
  ).toHaveLength(1);
  expect(getSession()?.access_token).toBe("TEST_rotated");
});
