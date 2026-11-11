import {
  useEffect,
  useRef,
  useState,
  createContext,
  useContext,
  type ReactNode,
} from "react";
import { LoaderCircle, X, Eye, EyeOff } from "lucide-react";
const CloseContext = createContext<() => void>(() => undefined);
export function CancelButton({ busy = false }: { busy?: boolean }) {
  const close = useContext(CloseContext);
  return (
    <button type="button" className="button" disabled={busy} onClick={close}>
      Hủy
    </button>
  );
}
export function Spinner() {
  return <LoaderCircle className="spinner" aria-hidden="true" size={18} />;
}
export function Notice({
  error,
  onRetry,
}: {
  error: string;
  onRetry?: () => void;
}) {
  return (
    <div className="notice danger" role="alert">
      <span>{error}</span>
      {onRetry && (
        <button className="button small" onClick={onRetry}>
          Thử lại
        </button>
      )}
    </div>
  );
}
export function Busy() {
  return (
    <div className="state" role="status">
      <Spinner /> Đang tải dữ liệu…
    </div>
  );
}
export function Modal({
  title,
  children,
  onClose,
  dirty = false,
  busy = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  dirty?: boolean;
  busy?: boolean;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [discard, setDiscard] = useState(false);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.showModal();
    return () => {
      dialog.current?.close();
      previous?.focus();
    };
  }, []);
  function close() {
    if (busy) return;
    if (dirty) setDiscard(true);
    else onClose();
  }
  return (
    <dialog
      ref={dialog}
      aria-labelledby="modal-title"
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      className="modal"
    >
      <header>
        <h2 id="modal-title">{title}</h2>
        <button
          className="icon-button"
          disabled={busy}
          aria-label="Đóng cửa sổ"
          onClick={close}
        >
          <X size={20} />
        </button>
      </header>
      {discard ? (
        <div className="modal-body">
          <h3>Bỏ thay đổi chưa lưu?</h3>
          <p>Dữ liệu vừa nhập chưa được lưu trên máy chủ.</p>
          <div className="actions">
            <button
              autoFocus
              className="button"
              onClick={() => setDiscard(false)}
            >
              Tiếp tục chỉnh sửa
            </button>
            <button className="button danger-button" onClick={onClose}>
              Bỏ thay đổi
            </button>
          </div>
        </div>
      ) : (
        <CloseContext.Provider value={close}>{children}</CloseContext.Provider>
      )}
    </dialog>
  );
}
export interface FieldSpec {
  key: string;
  label: string;
  type?:
    | "number"
    | "text"
    | "textarea"
    | "boolean"
    | "select"
    | "json"
    | "password";
  required?: boolean;
  options?: string[];
  hint?: string;
  min?: number;
  max?: number;
  default?: unknown;
  autocomplete?: string;
}
export function FormField({
  field,
  value,
  onChange,
  error,
  disabled = false,
}: {
  field: FieldSpec;
  value: unknown;
  onChange: (v: unknown) => void;
  error?: string;
  disabled?: boolean;
}) {
  const [show, setShow] = useState(false);
  const id = `field-${field.key}`;
  const common = {
    id,
    "aria-invalid": !!error,
    "aria-describedby": `${id}-help`,
    disabled,
  };
  return (
    <div
      className={`field ${field.type === "textarea" || field.type === "json" ? "wide" : ""}`}
    >
      <label htmlFor={id}>
        {field.label}
        {field.required && <span aria-hidden="true"> *</span>}
      </label>
      {field.type === "boolean" ? (
        <label className="check">
          <input
            {...common}
            type="checkbox"
            checked={value === true}
            onChange={(e) => onChange(e.target.checked)}
          />
          <span>{value === true ? "Bật" : "Tắt"}</span>
        </label>
      ) : field.type === "select" ? (
        <select
          {...common}
          value={String(value ?? "")}
          onChange={(e) => onChange(e.target.value)}
        >
          <option value="">Chọn…</option>
          {field.options?.map((option) => (
            <option key={option} value={option}>
              {{
                paid: "Thành công",
                failed: "Thất bại",
                cancelled: "Đã hủy",
                user: "Người dùng",
                admin: "Quản trị viên",
                guest: "Khách",
                guide: "Hướng dẫn",
                welcome: "Lời chào",
                news: "Tin tức",
                webview: "Trang nội dung",
                consent: "Đồng ý điều khoản",
                "vn.tn.assistant": "TN Assistant",
                "vn.tn.assistant.preview": "TN Assistant Preview",
              }[option] ?? option}
            </option>
          ))}
        </select>
      ) : field.type === "textarea" || field.type === "json" ? (
        <textarea
          className="resize-none"
          {...common}
          rows={field.type === "json" ? 9 : 4}
          value={String(value ?? "")}
          onChange={(e) => onChange(e.target.value)}
          style={{ resize: "none" }}
        />
      ) : (
        <div className={field.type === "password" ? "secret-field" : ""}>
          <input
            {...common}
            type={
              field.type === "password"
                ? show
                  ? "text"
                  : "password"
                : field.type === "number"
                  ? "number"
                  : "text"
            }
            min={field.min}
            max={field.max}
            autoComplete={
              field.autocomplete ??
              (field.key === "username"
                ? "username"
                : field.type === "password"
                  ? "current-password"
                  : "off")
            }
            value={String(value ?? "")}
            onChange={(e) =>
              onChange(
                field.type === "number"
                  ? e.target.value === ""
                    ? ""
                    : Number(e.target.value)
                  : e.target.value,
              )
            }
          />
          {field.type === "password" && (
            <button
              type="button"
              className="icon-button"
              aria-label={show ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
              aria-pressed={show}
              onClick={() => setShow(!show)}
            >
              {show ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          )}
        </div>
      )}
      <span id={`${id}-help`} className={error ? "field-error" : "field-help"}>
        {error ?? field.hint ?? " "}
      </span>
    </div>
  );
}
export function validate(fields: FieldSpec[], values: Record<string, unknown>) {
  const errors: Record<string, string> = {};
  for (const field of fields) {
    const value = values[field.key];
    if (
      field.required &&
      (value === undefined || value === null || String(value).trim() === "")
    )
      errors[field.key] = "Vui lòng nhập thông tin này.";
    if (
      field.type === "number" &&
      value !== "" &&
      value !== undefined &&
      (typeof value !== "number" ||
        !Number.isFinite(value) ||
        (field.min !== undefined && value < field.min) ||
        (field.max !== undefined && value > field.max))
    )
      errors[field.key] =
        `Nhập số hợp lệ${field.min !== undefined ? ` từ ${field.min}` : ""}${field.max !== undefined ? ` đến ${field.max}` : ""}.`;
    if (field.type === "json")
      try {
        JSON.parse(String(value));
      } catch {
        errors[field.key] =
          "JSON không hợp lệ. Kiểm tra dấu ngoặc và dấu phẩy.";
      }
  }
  return errors;
}
