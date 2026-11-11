import { useEffect, useRef, useState, type FormEvent } from "react";
import { Activity, RefreshCw, Save } from "lucide-react";
import { api, ApiError, dateFormat, message } from "./api";
import { Busy, Modal, Notice, Spinner } from "./ui";

type RuntimeConfig = {
  revision: number;
  generation: number;
  runtime_mode: "independent";
  cost_policy: "free_only";
  legacy_fallback: false;
  asr_url: string;
  tts_url: string;
  tts_voice: string;
  max_devices: number;
  speech_queue_limit: number;
  driving_enabled: boolean;
  news_enabled: boolean;
};
type OperationsState = {
  runtime_mode: string;
  revision: number;
  generation: number;
  readiness: {
    ready: boolean;
    status: string;
    checks: Record<
      string,
      {
        healthy?: boolean;
        status?: string;
        provider?: string;
        speaker_id?: string;
        voice?: string;
        endpoint?: string;
        queue_depth?: number;
        queue_limit?: number;
      }
    >;
  };
  counts: Record<string, number>;
  capabilities: { items: { id: string; status: string; reason: string }[] };
};
const names: Record<string, string> = {
  runtime: "Chế độ vận hành",
  database: "Cơ sở dữ liệu",
  asr: "Nhận dạng giọng nói",
  tts: "Giọng đọc",
  tn_auth: "Tài khoản TN",
  commands: "Lệnh cơ bản",
  device_events: "Sự kiện thiết bị",
  action_results: "Kết quả thao tác",
  local_media: "Nội dung trên thiết bị",
  navigation: "Chỉ đường",
  contacts: "Danh bạ",
  weather: "Thời tiết",
  calculator: "Tính toán",
  traffic_violations: "Tra cứu vi phạm",
  lottery: "Xổ số",
  gold: "Giá vàng",
  exchange_rates: "Tỷ giá",
  external_video: "Video trực tuyến",
  zalo_calls: "Cuộc gọi Zalo",
  driving: "Cảnh báo giao thông",
  news: "Tin đọc Premium",
  users: "Tài khoản",
  devices: "Thiết bị",
  sessions: "Phiên còn hiệu lực",
};
const statusNames: Record<string, string> = {
  AVAILABLE: "Sẵn có",
  CONFIGURED: "Đã cấu hình, chờ nghiệm thu",
  PARTIAL: "Có dữ liệu, còn giới hạn",
  CLIENT_VALIDATION_REQUIRED: "Cần kiểm tra trên Android",
  BLOCKED: "Chưa có nguồn miễn phí",
  DISABLED: "Đang tắt",
  READY: "Đã sẵn sàng",
  NOT_READY: "Chưa sẵn sàng",
  ready: "Sẵn sàng",
  not_ready: "Chưa sẵn sàng",
  unavailable: "Không kết nối",
  unhealthy: "Chưa hoạt động",
  independent: "Độc lập",
  tn_with_original_tts: "TN + TTS app gốc",
  invalid_health: "Phản hồi kiểm tra không hợp lệ",
};
const reasonNames: Record<string, string> = {
  tn_owned_runtime: "Do TN xử lý",
  worker_readiness_required: "Cần nạp mô hình và kiểm tra dịch vụ",
  legal_free_api_required: "Cần API hợp pháp, có quyền sử dụng",
  disabled_by_admin: "Quản trị viên chưa bật",
  licensed_data_and_runtime_validation_required:
    "Cần kiểm tra dữ liệu, quyền sử dụng và kết quả thực tế",
  tn_command_router_supported_intents_only:
    "Chỉ các lệnh đã được TN hỗ trợ mới được thực thi",
  android_intent_not_yet_accepted:
    "Cần kiểm thử intent và nhận kết quả thật từ Android",
  android_mediasession_not_yet_accepted:
    "Cần kiểm tra MediaSession trên thiết bị Android",
  tn_contact_resolver_not_implemented:
    "Bộ phân giải danh bạ của TN chưa được triển khai",
  pinned_local_model_loaded: "Worker và model cục bộ đã sẵn sàng",
  enabled_but_licensed_data_or_runtime_acceptance_missing:
    "Đang bật nhưng thiếu dữ liệu được cấp phép hoặc nghiệm thu thực tế",
  met_norway_proxy_cache_ready_upstream_not_checked:
    "Proxy MET Norway có cache và attribution; chưa kiểm tra upstream trong phiên này",
  coverage_and_speed_unknowns_must_be_disclosed_per_request:
    "Cần công khai vùng phủ và tốc độ chưa biết theo từng yêu cầu",
  tn_licensed_editorial_audio_requires_device_acceptance:
    "Bản tin TN được cấp quyền; cần nghiệm thu phát trên thiết bị",
  original_tts_upstream_dependency: "Phụ thuộc dịch vụ TTS của app gốc",
};

type SpeechCheck = OperationsState["readiness"]["checks"][string] | undefined;

function speechService(check: SpeechCheck, fallback: string) {
  switch (check?.provider) {
    case "deepgram":
      return "Deepgram · https://api.deepgram.com/v1/listen";
    case "azure":
      return "Azure Speech · dịch vụ đám mây";
    case "original_tts":
      return `Original TTS · ${check.endpoint ?? "https://partner-speech.lab.zalo.ai/tts/stream"}`;
    case "disabled":
      return "Chưa bật";
    default:
      return fallback;
  }
}

function speechVoice(check: SpeechCheck, fallback: string) {
  switch (check?.provider) {
    case "original_tts":
      return `Original TTS · speaker ${check.speaker_id ?? "chưa xác định"}`;
    case "azure":
      return check.voice ?? "vi-VN-HoaiMyNeural";
    case "disabled":
      return "Chưa bật";
    default:
      return fallback;
  }
}

export function Operations({
  onDirty,
  notify,
}: {
  onDirty: (dirty: boolean) => void;
  notify: (text: string) => void;
}) {
  const [config, setConfig] = useState<RuntimeConfig>();
  const [operations, setOperations] = useState<OperationsState>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState("");
  const [operationsError, setOperationsError] = useState("");
  const [dirty, setDirty] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [discard, setDiscard] = useState(false);
  const [reload, setReload] = useState(0);
  const [checkedAt, setCheckedAt] = useState("");
  const lock = useRef(false);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    Promise.all([
      api<RuntimeConfig>(
        "/v1/admin/runtime/config",
        "GET",
        undefined,
        controller.signal,
      ),
      api<OperationsState>(
        "/v1/admin/operations",
        "GET",
        undefined,
        controller.signal,
      ),
    ])
      .then(([value, state]) => {
        if (controller.signal.aborted) return;
        setConfig(value);
        setOperations(state);
        setCheckedAt(new Date().toISOString());
        setDirty(false);
        setConflict(false);
        onDirty(false);
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
  }, [reload]);
  function change(patch: Partial<RuntimeConfig>) {
    if (!config) return;
    setConfig({ ...config, ...patch });
    setDirty(true);
    onDirty(true);
  }
  async function refreshOperations() {
    if (checking) return;
    setChecking(true);
    setOperationsError("");
    try {
      const value = await api<OperationsState>("/v1/admin/operations");
      if (alive.current) {
        setOperations(value);
        setCheckedAt(new Date().toISOString());
      }
    } catch (e) {
      if (alive.current) setOperationsError(message(e));
    } finally {
      if (alive.current) setChecking(false);
    }
  }
  async function save(event: FormEvent) {
    event.preventDefault();
    if (!config || lock.current) return;
    if (
      !Number.isInteger(config.max_devices) ||
      config.max_devices < 1 ||
      config.max_devices > 100 ||
      !Number.isInteger(config.speech_queue_limit) ||
      config.speech_queue_limit !== 4 ||
      config.tts_voice !== "Thùy Dung"
    ) {
      setError(
        "Giới hạn thiết bị là 1–100; giọng đọc và hàng đợi được ghim theo cấu hình đã nghiệm thu.",
      );
      return;
    }
    lock.current = true;
    setSaving(true);
    setError("");
    try {
      const value = await api<RuntimeConfig>(
        "/v1/admin/runtime/config",
        "PUT",
        { base_revision: config.revision, config },
      );
      if (alive.current) {
        setConfig(value);
        setDirty(false);
        onDirty(false);
        setConflict(false);
        notify("Đã lưu cấu hình vận hành.");
      }
    } catch (e) {
      if (alive.current) {
        setConflict(
          e instanceof ApiError && e.code === "RUNTIME_REVISION_CONFLICT",
        );
        setError(message(e));
      }
    } finally {
      lock.current = false;
      if (alive.current) setSaving(false);
    }
  }
  if (loading) return <Busy />;
  if (!config)
    return (
      <Notice error={error} onRetry={() => setReload((value) => value + 1)} />
    );
  return (
    <>
      <section className="panel">
        <div className="section-toolbar">
          <h2>
            <Activity size={20} /> Vận hành trợ lý
          </h2>
          <button
            className="button"
            disabled={checking}
            onClick={() => void refreshOperations()}
          >
            {checking ? <Spinner /> : <RefreshCw size={16} />}Kiểm tra dịch vụ
          </button>
        </div>
        <p>
          {operations?.readiness.checks.tts?.provider === "original_tts"
            ? "TTS sử dụng dịch vụ của app gốc, phụ thuộc vào dịch vụ và thông tin xác thực hiện có."
            : "Dịch vụ giọng nói và trạng thái hoạt động được hiển thị bên dưới. Hạn mức phụ thuộc cấu hình nhà cung cấp."}
        </p>
        <p className="muted">
          Cấu hình đang chạy {operations?.revision ?? config.revision} · thế hệ{" "}
          {operations?.generation ?? config.generation} · kiểm tra{" "}
          {dateFormat(checkedAt)}
        </p>
        {operationsError && (
          <Notice
            error={operationsError}
            onRetry={() => void refreshOperations()}
          />
        )}
        {operations && (
          <>
            <p
              role="status"
              className={`notice ${operations.readiness.ready ? "info" : "warning"}`}
            >
              {operations.readiness.ready
                ? "Dịch vụ giọng nói sẵn sàng."
                : "Dịch vụ giọng nói chưa sẵn sàng. Kiểm tra các thành phần bên dưới."}
            </p>
            <div className="providers">
              {Object.entries(operations.readiness.checks).map(
                ([key, check]) => (
                  <div className="provider-row" key={key}>
                    <strong>{names[key] ?? key}</strong>
                    {check.provider && (
                      <small>
                        {key === "tts"
                          ? speechVoice(check, check.provider)
                          : speechService(check, check.provider)}
                      </small>
                    )}
                    <span
                      className={`badge ${check.healthy ? "success" : "warning"}`}
                    >
                      {statusNames[
                        key === "runtime" &&
                        operations.readiness.checks.tts?.provider ===
                          "original_tts"
                          ? "tn_with_original_tts"
                          : (check.status ?? "")
                      ] ?? (check.healthy ? "Hoạt động" : "Chưa sẵn sàng")}
                    </span>
                    {typeof check.queue_depth === "number" && (
                      <small>
                        Hàng đợi {check.queue_depth}/{check.queue_limit ?? "—"}
                      </small>
                    )}
                  </div>
                ),
              )}
            </div>
            <p>
              {Object.entries(operations.counts)
                .map(([key, value]) => `${names[key] ?? key}: ${value}`)
                .join(" · ")}
            </p>
          </>
        )}
        <a className="text-link" href="#/ai-providers">
          Kiểm tra cấu hình AI và hạn mức dự án
        </a>
      </section>
      <section className="panel">
        <h2>Cấu hình vận hành</h2>
        <p className="muted">
          Lưu cả cấu hình trong một giao dịch. Thay đổi không tự gọi AI hoặc tải
          mô hình.
        </p>
        <form noValidate onSubmit={save}>
          <fieldset disabled={saving} className="provider-card">
            <legend>Bản cấu hình {config.revision}</legend>
            <div className="form-grid">
              <label>
                Dịch vụ ASR
                <input
                  value={speechService(
                    operations?.readiness.checks.asr,
                    config.asr_url,
                  )}
                  readOnly
                />
              </label>
              <label>
                Dịch vụ TTS
                <input
                  value={speechService(
                    operations?.readiness.checks.tts,
                    config.tts_url,
                  )}
                  readOnly
                />
              </label>
              <label>
                Giọng đọc đang dùng
                <input
                  value={speechVoice(
                    operations?.readiness.checks.tts,
                    config.tts_voice,
                  )}
                  readOnly
                />
              </label>
              <label>
                Số thiết bị tối đa
                <input
                  type="number"
                  min={1}
                  max={100}
                  value={config.max_devices}
                  onChange={(e) =>
                    change({ max_devices: Number(e.target.value) })
                  }
                />
              </label>
              <label>
                Giới hạn hàng đợi giọng nói
                <input
                  type="number"
                  min={4}
                  max={4}
                  value={config.speech_queue_limit}
                  readOnly
                />
              </label>
            </div>
            <label>
              <input
                type="checkbox"
                checked={config.driving_enabled}
                onChange={(e) => change({ driving_enabled: e.target.checked })}
              />{" "}
              Bật dữ liệu cảnh báo giao thông tự quản
            </label>
            <label>
              <input
                type="checkbox"
                checked={config.news_enabled}
                onChange={(e) => change({ news_enabled: e.target.checked })}
              />{" "}
              Bật tin đọc Premium tự quản
            </label>
          </fieldset>
          {error && <Notice error={error} />}
          {conflict && (
            <p className="notice">
              Cấu hình máy chủ đã đổi. Bản nháp vẫn còn; nạp lại trước khi lưu
              tiếp.
            </p>
          )}
          <div className="actions">
            <button
              className="button primary"
              disabled={saving || !dirty || conflict}
            >
              {saving ? <Spinner /> : <Save size={16} />}Lưu cấu hình vận hành
            </button>
            <button
              type="button"
              className="button"
              disabled={saving}
              onClick={() =>
                dirty ? setDiscard(true) : setReload((value) => value + 1)
              }
            >
              Nạp lại cấu hình
            </button>
            <span className="muted">
              {dirty ? "Có thay đổi chưa lưu" : "Đã đồng bộ máy chủ"}
            </span>
          </div>
        </form>
      </section>
      {operations && (
        <section className="panel">
          <h2>Tính năng và nguồn dữ liệu</h2>
          <p className="muted">
            Đã cấu hình cần được kiểm tra bằng dữ liệu thật và thao tác trên
            thiết bị.
          </p>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Tính năng</th>
                  <th>Trạng thái</th>
                  <th>Điều kiện</th>
                </tr>
              </thead>
              <tbody>
                {operations.capabilities.items.map((item) => (
                  <tr key={item.id}>
                    <td>{names[item.id] ?? item.id}</td>
                    <td>{statusNames[item.status] ?? item.status}</td>
                    <td>{reasonNames[item.reason] ?? item.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      {discard && (
        <Modal
          title="Nạp lại và bỏ bản nháp?"
          onClose={() => setDiscard(false)}
        >
          <div className="modal-body">
            <p>Thay đổi chưa lưu sẽ được thay bằng cấu hình máy chủ.</p>
            <div className="actions">
              <button
                className="button"
                autoFocus
                onClick={() => setDiscard(false)}
              >
                Giữ bản nháp
              </button>
              <button
                className="button danger-button"
                onClick={() => {
                  setDiscard(false);
                  setReload((value) => value + 1);
                }}
              >
                Bỏ bản nháp và nạp lại
              </button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}
