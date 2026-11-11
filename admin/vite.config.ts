import { defineConfig } from "vitest/config";
import { env } from "node:process";

const backendProxyTarget =
  env.TN_BACKEND_PROXY_TARGET?.trim() || "http://127.0.0.1:8094";

export default defineConfig({
  base: "/",
  server: {
    proxy: { "/v1": { target: backendProxyTarget, changeOrigin: true } },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./tests/setup.ts"],
    restoreMocks: true,
  },
});
