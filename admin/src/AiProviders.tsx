import { useEffect, useRef, useState } from "react";
import { Bot, Plus, Save, Trash2, PlugZap } from "lucide-react";
import { api, ApiError, dateFormat, message } from "./api";
import { Busy, Notice, Spinner } from "./ui";

type Provider = {
  id: string;
  kind: string;
  model: string;
  endpoint: string;
  enabled: boolean;
  priority: number;
  max_requests_per_day: number;
  max_output_tokens: number;
  api_key?: string;
  key_configured?: boolean;
  project_alias?: string;
  quota_group?: string;
  free_tier_verified?: boolean;
};
type Probe = {
  status: string;
  checked_at?: string;
  latency_ms?: number;
  code?: string;
};
type ProviderState = {
  quota_group: string;
  requests_reserved?: number;
  local_daily_limit?: number;
  reset_timezone?: string;
  cooldown_until?: string;
  probe?: Probe;
};
type ProviderResponse = {
  providers: Provider[];
  revision?: number;
  independent?: boolean;
  states?: Record<string, ProviderState>;
};
const probeLabel: Record<string, string> = {
  healthy: "Đã kiểm tra tạo văn bản",
  checking: "Đang kiểm tra",
  failed: "Kiểm tra thất bại",
  blocked: "Chưa đủ điều kiện",
  quota_exhausted: "Hết hạn mức dự án",
  unknown: "Chưa xác nhận kết quả",
};
export function AiProviders({
  onDirty,
  notify,
}: {
  onDirty: (b: boolean) => void;
  notify: (s: string) => void;
}) {
  const [rows, setRows] = useState<Provider[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [revision, setRevision] = useState(0);
  const [independent, setIndependent] = useState(false);
  const [states, setStates] = useState<Record<string, ProviderState>>({});
  const [conflict, setConflict] = useState(false);
  const lock = useRef(false);
  const retryAction = useRef<"load" | "save" | "test">("load");
  const retryProvider = useRef("");
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    const controller = new AbortController();
    retryAction.current = "load";
    setLoading(true);
    setError("");
    api<ProviderResponse>(
      "/v1/admin/ai/providers",
      "GET",
      undefined,
      controller.signal,
    )
      .then((r) => {
        if (!controller.signal.aborted) {
          setRows(r.providers.map((p) => ({ ...p, api_key: "" })));
          setRevision(r.revision ?? 0);
          setIndependent(r.independent ?? false);
          setStates(r.states ?? {});
          setConflict(false);
          onDirty(false);
        }
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(message(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => {
      alive.current = false;
      controller.abort();
    };
  }, [refresh]);
  function change(index: number, patch: Partial<Provider>) {
    setRows(rows.map((r, i) => (i === index ? { ...r, ...patch } : r)));
    onDirty(true);
  }
  async function save() {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError("");
    try {
      const providers = rows.map(({ key_configured: _, api_key, ...p }) => ({
        ...p,
        ...(api_key ? { api_key } : {}),
      }));
      const result = await api<ProviderResponse>(
        "/v1/admin/ai/providers",
        "PUT",
        { providers, base_revision: revision },
      );
      if (alive.current) {
        setRows(result.providers.map((p) => ({ ...p, api_key: "" })));
        setRevision(result.revision ?? revision);
        setStates(result.states ?? {});
        setConflict(false);
        onDirty(false);
        notify("Đã lưu cấu hình AI trên máy chủ.");
      }
    } catch (e) {
      if (alive.current) {
        retryAction.current = "save";
        setConflict(e instanceof ApiError && e.code === "CONFIG_CONFLICT");
        setError(message(e));
      }
    } finally {
      lock.current = false;
      if (alive.current) setBusy(false);
    }
  }
  async function test(id: string) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError("");
    try {
      const probe = await api<Probe>("/v1/admin/ai/providers/test", "POST", {
        id,
      });
      if (alive.current) {
        setStates((previous) => ({
          ...previous,
          [id]: {
            ...previous[id],
            quota_group: previous[id]?.quota_group ?? "",
            probe,
          },
        }));
        notify("Provider đã phản hồi kiểm tra tạo văn bản.");
      }
    } catch (e) {
      if (alive.current) {
        retryAction.current = "test";
        retryProvider.current = id;
        setError(message(e));
        setStates((previous) => ({
          ...previous,
          [id]: {
            ...previous[id],
            quota_group: previous[id]?.quota_group ?? "",
            probe: {
              status:
                e instanceof ApiError && e.status === 429
                  ? "quota_exhausted"
                  : e instanceof ApiError && e.code === "TIMEOUT"
                    ? "unknown"
                    : "failed",
              checked_at: new Date().toISOString(),
              code: e instanceof ApiError ? e.code : "CONNECTION_FAILED",
            },
          },
        }));
      }
    } finally {
      lock.current = false;
      if (alive.current) setBusy(false);
    }
  }
  return (
    <section className="panel">
      <div className="panel-heading">
        <h2>
          <Bot size={20} /> AI Premium
        </h2>
      </div>
      <p>
        Basic dùng bộ lệnh cơ bản. Premium có hỏi đáp/dự phòng và chế độ điều
        phối nâng cao. Khóa chỉ lưu trên máy chủ; để trống để giữ khóa của cùng
        cấu hình. Khi đổi loại provider, tên cấu hình hoặc endpoint, nhập lại
        khóa tương ứng. Lưu cấu hình không gọi API. Kiểm tra tạo văn bản có tính
        hạn mức.
      </p>
      {independent && (
        <p className="notice info">
          Chỉ dùng Gemini 3.8 Flash tại endpoint chính thức. Chủ dự án phải xác
          nhận Free Tier và chưa bật thanh toán. Hết hạn mức sẽ dừng; không đổi
          khóa để vượt hạn mức.
        </p>
      )}
      <p className="muted">
        Bản cấu hình {revision}. Khóa cùng dự án chia sẻ hạn mức; để trống dự án
        dùng nhóm chưa xác định.
      </p>
      {loading ? (
        <Busy />
      ) : (
        <>
          {error && (
            <Notice
              error={error}
              onRetry={
                conflict
                  ? undefined
                  : () => {
                      if (retryAction.current === "save") void save();
                      else if (retryAction.current === "test")
                        void test(retryProvider.current);
                      else setRefresh((x) => x + 1);
                    }
              }
            />
          )}
          {conflict && (
            <div className="notice">
              <p>
                Bản nháp được giữ lại. Cấu hình máy chủ đã đổi; kiểm tra và sao
                chép thay đổi cần giữ trước khi nạp lại.
              </p>
              <button
                className="button"
                onClick={() => setRefresh((value) => value + 1)}
              >
                Nạp bản máy chủ và bỏ bản nháp
              </button>
            </div>
          )}
          {rows.length === 0 && !error && (
            <p className="notice">
              Chưa có cấu hình AI. Thêm Gemini và nhập khóa của dự án Free Tier
              để bắt đầu.
            </p>
          )}
          {rows.map((p, i) => (
            <fieldset key={i} disabled={busy} className="provider-card">
              <legend>
                {p.kind || "Provider"} · {i + 1}
              </legend>
              <div className="form-grid">
                <label>
                  Tên cấu hình
                  <input
                    aria-label={`Tên cấu hình ${i + 1}`}
                    value={p.id}
                    onChange={(e) => change(i, { id: e.target.value })}
                  />
                </label>
                <label>
                  Provider
                  <select
                    aria-label={`Provider ${i + 1}`}
                    value={p.kind}
                    onChange={(e) =>
                      change(i, {
                        kind: e.target.value,
                        endpoint: "",
                        model: e.target.value === "perplexity" ? "fast" : "",
                        api_key: "",
                        key_configured: false,
                      })
                    }
                  >
                    <option value="gemini">Gemini</option>
                    <option value="claude" disabled={independent}>
                      Claude
                    </option>
                    <option value="openai" disabled={independent}>
                      ChatGPT / OpenAI
                    </option>
                    <option value="perplexity" disabled={independent}>
                      Perplexity
                    </option>
                    <option value="custom" disabled={independent}>
                      Custom
                    </option>
                  </select>
                </label>
                <label>
                  Model / preset
                  <input
                    aria-label={`Model ${i + 1}`}
                    value={p.model}
                    onChange={(e) => change(i, { model: e.target.value })}
                  />
                </label>
                <label>
                  Endpoint
                  <input
                    aria-label={`Endpoint ${i + 1}`}
                    placeholder="HTTPS hoặc máy chủ local"
                    value={p.endpoint}
                    onChange={(e) => change(i, { endpoint: e.target.value })}
                  />
                </label>
                <label>
                  Khóa API mới
                  <input
                    aria-label={`Khóa API ${i + 1}`}
                    type="password"
                    autoComplete="new-password"
                    placeholder={
                      p.key_configured
                        ? "Đã có khóa · để trống để giữ"
                        : "Chưa cấu hình khóa"
                    }
                    value={p.api_key || ""}
                    onChange={(e) =>
                      change(i, {
                        api_key: e.target.value,
                        free_tier_verified: false,
                      })
                    }
                  />
                </label>
                {p.kind === "gemini" && (
                  <label>
                    Dự án Gemini (bí danh)
                    <input
                      aria-label={`Dự án ${i + 1}`}
                      value={p.project_alias ?? ""}
                      onChange={(e) =>
                        change(i, {
                          project_alias: e.target.value,
                          quota_group: e.target.value || "unknown-shared",
                          free_tier_verified: false,
                        })
                      }
                    />
                    <small>
                      Mọi khóa của cùng dự án phải dùng cùng bí danh.
                    </small>
                  </label>
                )}
                <label>
                  Thứ tự ưu tiên
                  <input
                    aria-label={`Ưu tiên ${i + 1}`}
                    type="number"
                    min={0}
                    value={p.priority}
                    onChange={(e) =>
                      change(i, { priority: Number(e.target.value) })
                    }
                  />
                </label>
                <label>
                  Yêu cầu mỗi ngày
                  <input
                    aria-label={`Hạn mức ${i + 1}`}
                    type="number"
                    min={1}
                    value={p.max_requests_per_day}
                    onChange={(e) =>
                      change(i, {
                        max_requests_per_day: Number(e.target.value),
                      })
                    }
                  />
                </label>
                <label>
                  Token trả lời tối đa
                  <input
                    aria-label={`Token ${i + 1}`}
                    type="number"
                    min={64}
                    value={p.max_output_tokens}
                    onChange={(e) =>
                      change(i, { max_output_tokens: Number(e.target.value) })
                    }
                  />
                </label>
              </div>
              {p.kind === "gemini" && (
                <label>
                  <input
                    type="checkbox"
                    checked={p.free_tier_verified ?? false}
                    disabled={!p.project_alias?.trim()}
                    onChange={(e) =>
                      change(i, { free_tier_verified: e.target.checked })
                    }
                  />{" "}
                  Tôi đã xác minh dự án đang ở Free Tier và chưa bật thanh toán.
                </label>
              )}
              <p className="muted">
                {!p.enabled
                  ? "Đang tắt"
                  : independent && !p.free_tier_verified
                    ? "Chờ xác minh Free Tier; chưa gọi AI"
                    : !p.key_configured
                      ? "Chưa lưu khóa"
                      : "Đã lưu cấu hình"}
                {states[p.id]?.probe
                  ? ` · ${probeLabel[states[p.id].probe!.status] ?? "Chưa kiểm tra"} · ${dateFormat(states[p.id].probe!.checked_at)}`
                  : " · Chưa kiểm tra tạo văn bản"}
              </p>
              {states[p.id] && (
                <p className="muted">
                  Nhóm {states[p.id].quota_group}:{" "}
                  {states[p.id].requests_reserved ?? 0}/
                  {states[p.id].local_daily_limit ?? "—"} yêu cầu dự trữ · đặt
                  lại theo {states[p.id].reset_timezone ?? "UTC"}
                  {states[p.id].cooldown_until
                    ? ` · chờ đến ${dateFormat(states[p.id].cooldown_until)}`
                    : ""}
                </p>
              )}
              <label>
                <input
                  type="checkbox"
                  checked={p.enabled}
                  onChange={(e) => change(i, { enabled: e.target.checked })}
                />{" "}
                Bật provider
              </label>
              <div className="actions">
                <button
                  type="button"
                  className="button"
                  onClick={() => test(p.id)}
                  disabled={
                    independent &&
                    (!p.free_tier_verified || !p.key_configured || !p.enabled)
                  }
                >
                  <PlugZap size={18} /> Kiểm tra bản đã lưu
                </button>
                <button
                  type="button"
                  className="button danger-button"
                  onClick={() => {
                    setRows(rows.filter((_, n) => n !== i));
                    onDirty(true);
                  }}
                >
                  <Trash2 size={18} /> Xóa khỏi cấu hình
                </button>
              </div>
            </fieldset>
          ))}
          <div className="actions">
            <button
              type="button"
              className="button"
              disabled={busy}
              onClick={() => {
                setRows([
                  ...rows,
                  {
                    id: `provider-${Date.now()}`,
                    kind: independent ? "gemini" : "custom",
                    model: independent ? "gemini-3.8-flash" : "",
                    endpoint: independent
                      ? "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
                      : "",
                    enabled: false,
                    priority: rows.length,
                    max_requests_per_day: 100,
                    max_output_tokens: 1024,
                    api_key: "",
                    project_alias: "",
                    quota_group: "unknown-shared",
                    free_tier_verified: false,
                  },
                ]);
                onDirty(true);
              }}
            >
              <Plus size={18} /> Thêm provider
            </button>
            <button
              type="button"
              className="button primary"
              disabled={busy}
              onClick={save}
            >
              {busy ? <Spinner /> : <Save size={18} />} Lưu cấu hình AI
            </button>
          </div>
        </>
      )}
    </section>
  );
}
