import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  Activity,
  Users,
  Car,
  SlidersHorizontal,
  Radio,
  Ticket,
  Wallet,
  FileText,
  History,
  Stethoscope,
  Download,
  LogOut,
  Menu,
  Search,
  X,
  RefreshCw,
  Plus,
  Pencil,
  Trash2,
  ChevronLeft,
  ChevronRight,
  ArrowUpRight,
  ShieldCheck,
  CheckCircle2,
  Headphones,
  Link as LinkIcon,
  Bot,
  MapPinned,
  Newspaper,
  MessageSquare,
  Save,
} from "lucide-react";
import {
  api,
  getSession,
  setSession,
  message,
  dateFormat,
  numberFormat,
  safeUrl,
  type RecordValue,
  type Session,
} from "./api";
import {
  Busy,
  Notice,
  Modal,
  FormField,
  Spinner,
  CancelButton,
  validate,
  type FieldSpec,
} from "./ui";
import { schemas, type EntitySchema } from "./schemas";
import { LocalPremium } from "./LocalPremium";
import { AiProviders } from "./AiProviders";
import { Operations } from "./Operations";
import { NewsAudioAction, FeedbackAction, RoadImport } from "./ManagedActions";

const navigation = [
  { key: "overview", title: "Tổng quan", icon: Activity },
  { key: "operations", title: "Vận hành độc lập", icon: Activity },
  { key: "users", title: "Người dùng", icon: Users },
  { key: "devices", title: "Thiết bị", icon: Car },
  { key: "config", title: "Cấu hình ứng dụng", icon: SlidersHorizontal },
  { key: "providers", title: "Dịch vụ giọng nói", icon: Radio },
  { key: "ai-providers", title: "AI Premium", icon: Bot },
  { key: "managed-roads", title: "Đường, biển và camera", icon: MapPinned },
  { key: "managed-news", title: "Tin đọc Premium", icon: Newspaper },
  {
    key: "managed-feedback",
    title: "Phản hồi giao thông",
    icon: MessageSquare,
  },
  { key: "managed/config", title: "Dịch vụ tự quản", icon: SlidersHorizontal },
  { key: "products", title: "Gói Premium", icon: ShieldCheck },
  { key: "activations", title: "Mã kích hoạt", icon: Ticket },
  { key: "payments", title: "Thanh toán sandbox", icon: Wallet },
  { key: "content", title: "Nội dung", icon: FileText },
  { key: "history", title: "Lịch sử", icon: History },
  { key: "diagnostics", title: "Chẩn đoán", icon: Stethoscope },
  { key: "releases", title: "Bản cập nhật", icon: Download },
];
function currentRoute() {
  const [key, query] = window.location.hash.replace(/^#\/?/, "").split("?");
  return { key: key || "overview", params: new URLSearchParams(query) };
}
function useRemote<T>(path: string, refresh = 0) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    setBusy(true);
    setError("");
    api<T>(path, "GET", undefined, controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) setData(value);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(message(e));
      })
      .finally(() => {
        if (!controller.signal.aborted) setBusy(false);
      });
    return () => controller.abort();
  }, [path, refresh]);
  return { data, error, busy };
}
export default function App() {
  const [session, updateSession] = useState(getSession());
  const [route, setRoute] = useState(currentRoute());
  const [menu, setMenu] = useState(false);
  const [toast, setToast] = useState("");
  const [logoutError, setLogoutError] = useState("");
  const [logout, setLogout] = useState(false);
  const [logoutBusy, setLogoutBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [pendingRoute, setPendingRoute] = useState("");
  const dirtyRef = useRef(dirty);
  const routeRef = useRef(route.key);
  const acceptedHash = useRef(window.location.hash);
  dirtyRef.current = dirty;
  routeRef.current = route.key;
  useEffect(() => {
    function sync() {
      const next = getSession();
      if (!next) {
        setDirty(false);
        setPendingRoute("");
        setLogout(false);
        setMenu(false);
      }
      updateSession(next);
    }
    function hash() {
      if (dirtyRef.current && currentRoute().key !== routeRef.current) {
        setPendingRoute(window.location.hash.slice(1));
        window.history.replaceState(
          null,
          "",
          acceptedHash.current || "#/overview",
        );
        return;
      }
      acceptedHash.current = window.location.hash;
      setRoute(currentRoute());
      setMenu(false);
    }
    window.addEventListener("tn-session", sync);
    window.addEventListener("hashchange", hash);
    return () => {
      window.removeEventListener("tn-session", sync);
      window.removeEventListener("hashchange", hash);
    };
  }, []);
  useEffect(() => {
    document.title = `${navigation.find((item) => item.key === route.key)?.title ?? "Không tìm thấy"} · TN Auto`;
  }, [route.key]);
  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(""), 5000);
    return () => window.clearTimeout(timer);
  }, [toast]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  function navigate(key: string) {
    if (dirty) setPendingRoute(`/${key}`);
    else window.location.hash = `/${key}`;
  }
  if (!session) return <Login onLogin={(value) => setSession(value)} />;
  const title = navigation.find((item) => item.key === route.key)?.title;
  return (
    <div className="app-shell">
      <a
        href="#main"
        className="skip-link"
        onClick={(e) => {
          e.preventDefault();
          document.getElementById("main")?.focus();
        }}
      >
        Đến nội dung chính
      </a>
      <aside className={`sidebar ${menu ? "expanded" : ""}`}>
        <a
          className="brand"
          href="#/overview"
          onClick={(e) => {
            e.preventDefault();
            navigate("overview");
          }}
          aria-label="TN Auto, tổng quan"
        >
          <span className="brand-mark">
            T<span>N</span>
          </span>
          <span className="brand-subtitle">AUTO / QUẢN TRỊ</span>
        </a>
        <div className="nav-heading">Không gian quản trị</div>
        <nav aria-label="Điều hướng chính">
          {navigation.map(({ key, title, icon: Icon }) => (
            <a
              key={key}
              href={`#/${key}`}
              aria-current={route.key === key ? "page" : undefined}
              onClick={(e) => {
                e.preventDefault();
                navigate(key);
              }}
            >
              <Icon size={18} />
              <span>{title}</span>
              {route.key === key && <span className="nav-current" />}
            </a>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div className="environment">
            <span />
            Máy chủ của bạn
          </div>
          <p>Thanh toán trong môi trường sandbox.</p>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <button
            className="icon-button mobile-menu"
            aria-label={menu ? "Ẩn điều hướng" : "Mở điều hướng"}
            aria-expanded={menu}
            onClick={() => setMenu(!menu)}
          >
            <Menu />
          </button>
          <div className="breadcrumb">
            TN Auto <span>/</span> <strong>{title ?? "Không tìm thấy"}</strong>
          </div>
          <div className="user-block">
            <span className="avatar">
              {session.user.name.slice(0, 1).toUpperCase()}
            </span>
            <span>
              <strong>{session.user.name}</strong>
              <small>Quản trị viên</small>
            </span>
            <button
              className="icon-button"
              aria-label="Đăng xuất"
              onClick={() => {
                setLogoutError("");
                setLogout(true);
              }}
            >
              <LogOut size={18} />
            </button>
          </div>
        </header>
        <main id="main" tabIndex={-1}>
          <div className="page-heading">
            <div>
              <span className="eyebrow">VẬN HÀNH TN AUTO</span>
              <h1>{title ?? "Không tìm thấy trang"}</h1>
              <p>
                {schemas[route.key]?.description ??
                  (route.key === "overview"
                    ? "Theo dõi hệ thống, kết nối và thiết bị từ một nơi."
                    : route.key === "providers"
                      ? "Kết nối ASR, hội thoại và giọng đọc bằng cấu hình riêng."
                      : "Cấu hình dùng chung cho các ứng dụng kết nối.")}
              </p>
            </div>
            <span className="sandbox-pill">
              <ShieldCheck size={15} /> Sandbox
            </span>
          </div>
          {route.key === "overview" ? (
            <Overview notify={setToast} />
          ) : route.key === "operations" ? (
            <Operations notify={setToast} onDirty={setDirty} />
          ) : route.key === "ai-providers" ? (
            <AiProviders notify={setToast} onDirty={setDirty} />
          ) : route.key === "config" ||
            route.key === "providers" ||
            route.key === "managed/config" ? (
            <Configuration
              key={route.key}
              kind={route.key}
              notify={setToast}
              onDirty={setDirty}
            />
          ) : schemas[route.key] ? (
            <EntityPage
              key={route.key}
              schema={schemas[route.key]}
              query={route.params.get("q") ?? ""}
              page={Number(route.params.get("page") ?? 1)}
              notify={setToast}
              onDirty={setDirty}
            />
          ) : (
            <Notice error="Đường dẫn không tồn tại. Chọn mục khác trong điều hướng." />
          )}
        </main>
        <footer className="page-footer">
          TN Auto · Backend độc lập <span>Giờ Việt Nam (UTC+7)</span>
        </footer>
      </div>
      <div className="toast" role="status" aria-live="polite">
        {toast && (
          <>
            <CheckCircle2 size={18} />
            {toast}
            <button
              className="icon-button"
              aria-label="Đóng thông báo"
              onClick={() => setToast("")}
            >
              <X size={16} />
            </button>
          </>
        )}
      </div>
      {pendingRoute && (
        <Modal
          title="Bỏ thay đổi chưa lưu?"
          onClose={() => setPendingRoute("")}
        >
          <div className="modal-body">
            <p>Thay đổi chưa được lưu trên máy chủ.</p>
            <div className="actions">
              <button
                autoFocus
                className="button"
                onClick={() => setPendingRoute("")}
              >
                Tiếp tục chỉnh sửa
              </button>
              <button
                className="button danger-button"
                onClick={() => {
                  dirtyRef.current = false;
                  setDirty(false);
                  window.location.hash = pendingRoute;
                  setPendingRoute("");
                }}
              >
                Bỏ thay đổi
              </button>
            </div>
          </div>
        </Modal>
      )}
      {logout && (
        <Modal
          title="Đăng xuất khỏi quản trị?"
          onClose={() => setLogout(false)}
          busy={logoutBusy}
        >
          <div className="modal-body">
            <p>
              {dirty
                ? "Bạn có thay đổi chưa lưu. Đăng xuất sẽ bỏ các thay đổi này."
                : "Phiên đăng nhập hiện tại sẽ được thu hồi."}
            </p>
            {logoutError && <Notice error={logoutError} />}
            <div className="actions">
              <button
                autoFocus
                className="button"
                disabled={logoutBusy}
                onClick={() => setLogout(false)}
              >
                Hủy
              </button>
              <button
                className="button primary"
                disabled={logoutBusy}
                onClick={async () => {
                  setLogoutBusy(true);
                  try {
                    await api("/v1/auth/logout", "POST", {
                      refresh_token: session.refresh_token,
                    });
                    setSession(null);
                    setLogout(false);
                    setDirty(false);
                  } catch (e) {
                    setLogoutError(message(e));
                  } finally {
                    setLogoutBusy(false);
                  }
                }}
              >
                {logoutBusy && <Spinner />}Đăng xuất
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}

function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const [values, setValues] = useState<RecordValue>({
    username: "",
    password: "",
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const busyLock = useRef(false);
  const fields: FieldSpec[] = [
    { key: "username", label: "Tên đăng nhập", required: true },
    { key: "password", label: "Mật khẩu", type: "password", required: true },
  ];
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busyLock.current) return;
    const next = validate(fields, values);
    setErrors(next);
    if (Object.keys(next).length) {
      document.getElementById(`field-${Object.keys(next)[0]}`)?.focus();
      return;
    }
    busyLock.current = true;
    setBusy(true);
    setError("");
    try {
      const result = await api<Session>("/v1/auth/login", "POST", values);
      if (result.user.role !== "admin") {
        await api("/v1/auth/logout", "POST", {
          refresh_token: result.refresh_token,
        });
        throw new Error("NO_ADMIN");
      }
      onLogin(result);
    } catch (e) {
      setError(
        e instanceof Error && e.message === "NO_ADMIN"
          ? "Tài khoản này không có quyền quản trị."
          : message(e),
      );
    } finally {
      busyLock.current = false;
      setBusy(false);
    }
  }
  return (
    <div className="login-screen">
      <section className="login-intro">
        <span className="brand-mark">
          T<span>N</span>
        </span>
        <span className="eyebrow">TN AUTO / VẬN HÀNH</span>
        <h1>
          Mọi kết nối.
          <br />
          Một không gian.
        </h1>
        <p>
          Quản lý thiết bị, dịch vụ giọng nói và nội dung cho trợ lý trên xe của
          bạn.
        </p>
        <div className="login-signal">
          <Headphones size={30} />
          <div>
            <strong>Thiết bị → Trợ lý → Giọng nói</strong>
            <span>Backend độc lập · Dữ liệu của bạn</span>
          </div>
        </div>
      </section>
      <section className="login-panel">
        <form noValidate onSubmit={submit}>
          <span className="eyebrow">QUẢN TRỊ VIÊN</span>
          <h2>Đăng nhập</h2>
          <p className="muted">Sử dụng tài khoản đã cấu hình trên máy chủ.</p>
          {fields.map((field) => (
            <FormField
              key={field.key}
              field={field}
              value={values[field.key]}
              error={errors[field.key]}
              onChange={(value) => setValues({ ...values, [field.key]: value })}
            />
          ))}
          {error && <Notice error={error} />}
          <button className="button primary login-button" disabled={busy}>
            {busy && <Spinner />}Đăng nhập quản trị
            <ArrowUpRight size={18} />
          </button>
          <p className="login-note">
            Phiên chỉ lưu trong bộ nhớ trình duyệt. Tải lại trang sẽ yêu cầu
            đăng nhập lại.
          </p>
        </form>
      </section>
    </div>
  );
}

function Overview({ notify }: { notify: (s: string) => void }) {
  const [refresh, setRefresh] = useState(0);
  const overview = useRemote<RecordValue>("/v1/admin/overview", refresh);
  const health = useRemote<RecordValue>("/v1/health", refresh);
  const [linkCode, setLinkCode] = useState("");
  const [userId, setUserId] = useState("");
  const [linkBusy, setLinkBusy] = useState(false);
  const [linkError, setLinkError] = useState("");
  const providerList = (health.data?.providers ?? {}) as Record<
    string,
    { configured: boolean }
  >;
  async function complete(e: FormEvent) {
    e.preventDefault();
    if (!linkCode.trim() || !userId.trim()) {
      setLinkError("Nhập mã liên kết và mã người dùng.");
      return;
    }
    setLinkBusy(true);
    setLinkError("");
    try {
      await api(
        `/v1/admin/link/${encodeURIComponent(linkCode.trim())}/complete`,
        "POST",
        { user_id: userId.trim() },
      );
      notify("Đã hoàn tất liên kết từ quản trị.");
      setLinkCode("");
      setUserId("");
    } catch (e) {
      setLinkError(message(e));
    } finally {
      setLinkBusy(false);
    }
  }
  return (
    <>
      <div className="section-toolbar">
        <h2>Tình trạng hệ thống</h2>
        <button className="button" onClick={() => setRefresh(refresh + 1)}>
          <RefreshCw size={16} />
          Làm mới
        </button>
      </div>
      {overview.busy ? (
        <Busy />
      ) : overview.error ? (
        <Notice
          error={overview.error}
          onRetry={() => setRefresh(refresh + 1)}
        />
      ) : (
        <div className="metrics">
          {[
            ["users", "Người dùng", Users],
            ["devices", "Thiết bị", Car],
            ["payments", "Giao dịch sandbox", Wallet],
            ["entitlements", "Quyền sử dụng", ShieldCheck],
          ].map(([key, label, Icon]) => {
            const value = overview.data?.[key as string];
            const DisplayIcon = Icon as typeof Users;
            return (
              <article className="metric" key={key as string}>
                <div>
                  <span>{label as string}</span>
                  <DisplayIcon size={20} />
                </div>
                <strong>
                  {typeof value === "number" ? numberFormat.format(value) : "—"}
                </strong>
                <small>Dữ liệu máy chủ</small>
              </article>
            );
          })}
        </div>
      )}
      <div className="overview-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <span className="eyebrow">KẾT NỐI NHÀ CUNG CẤP</span>
              <h2>Dịch vụ giọng nói</h2>
            </div>
            <Radio size={24} />
          </div>
          {health.busy ? (
            <Busy />
          ) : health.error ? (
            <Notice
              error={health.error}
              onRetry={() => setRefresh(refresh + 1)}
            />
          ) : (
            <div className="providers">
              {[
                ["asr", "Nhận dạng giọng nói", "Âm thanh → văn bản"],
                ["nlu", "Hội thoại & AI", "Văn bản → lệnh"],
                ["tts", "Giọng đọc", "Văn bản → âm thanh"],
                ["zalo", "Liên kết Zalo", "Tài khoản nhà cung cấp"],
              ].map(([key, name, description]) => (
                <div className="provider-row" key={key}>
                  <span
                    className={`connection-dot ${providerList[key]?.configured ? "connected" : ""}`}
                  />
                  <div>
                    <strong>{name}</strong>
                    <small>{description}</small>
                  </div>
                  <span
                    className={`badge ${providerList[key]?.configured ? "success" : "warning"}`}
                  >
                    {providerList[key]?.configured
                      ? "Đã cấu hình"
                      : "Chưa cấu hình"}
                  </span>
                </div>
              ))}
            </div>
          )}
          <p className="panel-note">
            Đã cấu hình chỉ xác nhận cấu hình tồn tại, chưa xác nhận kết nối API
            thành công.
          </p>
          <a className="text-link" href="#/providers">
            Quản lý dịch vụ
            <ArrowUpRight size={16} />
          </a>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <div>
              <span className="eyebrow">LIÊN KẾT THIẾT BỊ</span>
              <h2>Hoàn tất mã liên kết</h2>
            </div>
            <LinkIcon size={24} />
          </div>
          <p className="muted">
            Gắn mã hiển thị trên ứng dụng với tài khoản đã có. Liên kết này được
            ghi nguồn quản trị.
          </p>
          <form noValidate onSubmit={complete}>
            <FormField
              field={{ key: "link-code", label: "Mã liên kết", required: true }}
              value={linkCode}
              onChange={(v) => setLinkCode(String(v))}
            />
            <FormField
              field={{
                key: "link-user",
                label: "Mã người dùng",
                required: true,
              }}
              value={userId}
              onChange={(v) => setUserId(String(v))}
            />
            {linkError && <Notice error={linkError} />}
            <button className="button primary" disabled={linkBusy}>
              {linkBusy && <Spinner />}Hoàn tất liên kết
            </button>
          </form>
        </section>
      </div>
      <div className="notice info">
        <ShieldCheck size={20} />
        <span>
          Thanh toán sandbox không thu tiền. Chức năng giọng nói cần cấu hình
          API riêng trước khi sử dụng.
        </span>
      </div>
    </>
  );
}

function EntityPage({
  schema,
  query,
  page,
  notify,
  onDirty,
}: {
  schema: EntitySchema;
  query: string;
  page: number;
  notify: (s: string) => void;
  onDirty: (b: boolean) => void;
}) {
  const [refresh, setRefresh] = useState(0);
  const remote = useRemote<{ items: RecordValue[] }>(
    `/v1/admin/${schema.route}`,
    refresh,
  );
  const [search, setSearch] = useState(query);
  const composing = useRef(false);
  const searchRef = useRef<HTMLInputElement>(null);
  const [editor, setEditor] = useState<RecordValue | null>();
  const [remove, setRemove] = useState<RecordValue>();
  const [deleteConfirmation, setDeleteConfirmation] = useState("");
  const [removeBusy, setRemoveBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const [payment, setPayment] = useState<{
    row: RecordValue;
    status: string;
  }>();
  const [detail, setDetail] = useState<RecordValue>();
  const [localPremium, setLocalPremium] = useState<RecordValue>();
  const rows = remote.data?.items ?? [];
  function stateQuery(q: string, p: number) {
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (p > 1) params.set("page", String(p));
    window.location.hash = `/${currentRoute().key}${params.size ? "?" + params : ""}`;
  }
  useEffect(() => {
    setSearch(query);
  }, [query]);
  useEffect(() => {
    if (composing.current) return;
    const timer = window.setTimeout(() => {
      if (search !== query) stateQuery(search, 1);
    }, 300);
    return () => window.clearTimeout(timer);
  }, [search, query]);
  const filtered = rows.filter((row) =>
    JSON.stringify(row)
      .toLocaleLowerCase("vi")
      .includes(query.toLocaleLowerCase("vi")),
  );
  const pages = Math.max(1, Math.ceil(filtered.length / 20));
  const currentPage = Math.max(
    1,
    Math.min(Number.isFinite(page) ? page : 1, pages),
  );
  const visible = filtered.slice((currentPage - 1) * 20, currentPage * 20);
  const keyField = schema.key ?? "id";
  const removeLock = useRef(false);
  async function deleteRow() {
    if (!remove || removeLock.current) return;
    removeLock.current = true;
    setRemoveBusy(true);
    setActionError("");
    try {
      await api(
        `/v1/admin/${schema.route}/${encodeURIComponent(String(remove[keyField]))}`,
        "DELETE",
      );
      setRemove(undefined);
      setRefresh(refresh + 1);
      notify(
        schema.action === "issue"
          ? "Đã thu hồi mã kích hoạt."
          : "Đã xóa bản ghi.",
      );
    } catch (e) {
      setActionError(message(e));
    } finally {
      removeLock.current = false;
      setRemoveBusy(false);
    }
  }
  async function settle() {
    if (!payment || removeLock.current) return;
    removeLock.current = true;
    setRemoveBusy(true);
    setActionError("");
    try {
      await api(
        `/v1/payments/${encodeURIComponent(String(payment.row.id))}/sandbox`,
        "POST",
        { status: payment.status },
      );
      setPayment(undefined);
      setRefresh(refresh + 1);
      notify("Đã cập nhật giao dịch sandbox.");
    } catch (e) {
      setActionError(message(e));
    } finally {
      removeLock.current = false;
      setRemoveBusy(false);
    }
  }
  return (
    <>
      {schema.route === "managed/roads" && (
        <RoadImport onDone={() => setRefresh((x) => x + 1)} />
      )}
      <div className="section-toolbar">
        <div className="search-field">
          <Search size={18} />
          <input
            ref={searchRef}
            aria-label={`Tìm ${schema.title.toLowerCase()}`}
            type="search"
            placeholder="Tìm trong danh sách…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            onCompositionStart={() => {
              composing.current = true;
            }}
            onCompositionEnd={(e) => {
              composing.current = false;
              setSearch(e.currentTarget.value);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.nativeEvent.isComposing) {
                stateQuery(search, 1);
              }
            }}
          />
          {search && (
            <button
              className="icon-button"
              aria-label="Xóa tìm kiếm"
              onClick={() => {
                setSearch("");
                stateQuery("", 1);
                searchRef.current?.focus();
              }}
            >
              <X size={16} />
            </button>
          )}
        </div>
        <div className="actions">
          <button className="button" onClick={() => setRefresh(refresh + 1)}>
            <RefreshCw size={16} />
            Làm mới
          </button>
          {schema.fields && (
            <button className="button primary" onClick={() => setEditor(null)}>
              <Plus size={17} />
              {schema.action === "issue" ? "Phát hành mã" : "Tạo mới"}
            </button>
          )}
        </div>
      </div>
      <section className="table-panel">
        {remote.busy ? (
          <Busy />
        ) : remote.error ? (
          <Notice
            error={remote.error}
            onRetry={() => setRefresh(refresh + 1)}
          />
        ) : (
          <>
            <div className="table-scroll">
              <table>
                <thead>
                  <tr>
                    {schema.columns.map(([key, label]) => (
                      <th key={key} scope="col">
                        {label}
                      </th>
                    ))}
                    <th scope="col">Thao tác</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((row, index) => (
                    <tr key={String(row[keyField] ?? index)}>
                      {schema.columns.map(([key]) => (
                        <td key={key}>
                          <Cell
                            value={
                              schema.route === "managed/news" &&
                              key === "audio_status"
                                ? row.audio_id
                                  ? "Sẵn sàng"
                                  : "Chưa tạo"
                                : row[key]
                            }
                            column={key}
                          />
                        </td>
                      ))}
                      <td>
                        <div className="row-actions">
                          {schema.route === "managed/news" && (
                            <NewsAudioAction
                              id={String(row.id)}
                              onReady={() => setRefresh((x) => x + 1)}
                            />
                          )}
                          {schema.route === "managed/feedback" && (
                            <FeedbackAction
                              id={String(row.id)}
                              onDone={() => setRefresh((x) => x + 1)}
                            />
                          )}
                          {schema.route === "users" && (
                            <button
                              className="button small"
                              onClick={() => setLocalPremium(row)}
                            >
                              Premium local
                            </button>
                          )}
                          <button
                            className="button small"
                            onClick={() => setDetail(row)}
                          >
                            Chi tiết
                          </button>
                          {schema.fields && schema.action !== "issue" && (
                            <button
                              className="icon-button"
                              aria-label={`Sửa ${String(row.name ?? row.title ?? row.id)}`}
                              onClick={() => setEditor(row)}
                            >
                              <Pencil size={16} />
                            </button>
                          )}
                          {schema.canDelete && (
                            <button
                              className="icon-button destructive"
                              aria-label={`Xóa ${String(row.name ?? row.title ?? row[keyField])}`}
                              onClick={() => {
                                setActionError("");
                                setDeleteConfirmation("");
                                setRemove(row);
                              }}
                            >
                              <Trash2 size={16} />
                            </button>
                          )}
                          {schema.action === "payment" &&
                            row.status === "pending" && (
                              <button
                                className="button small"
                                onClick={() => {
                                  setActionError("");
                                  setPayment({ row, status: "paid" });
                                }}
                              >
                                Xử lý sandbox
                              </button>
                            )}
                        </div>
                      </td>
                    </tr>
                  ))}
                  {!visible.length && (
                    <tr>
                      <td colSpan={schema.columns.length + 1}>
                        <div className="state">
                          <FileText size={24} />
                          <strong>
                            {query
                              ? "Không tìm thấy kết quả"
                              : "Chưa có dữ liệu"}
                          </strong>
                          <span>
                            {query
                              ? "Thử từ khóa khác hoặc xóa tìm kiếm."
                              : schema.fields
                                ? "Tạo bản ghi đầu tiên để bắt đầu."
                                : "Dữ liệu sẽ xuất hiện khi thiết bị gửi thông tin."}
                          </span>
                        </div>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
            <div className="pagination">
              <span>
                {filtered.length
                  ? `${(currentPage - 1) * 20 + 1}–${Math.min(currentPage * 20, filtered.length)} / ${filtered.length} bản ghi`
                  : "0 bản ghi"}
                {query ? " · đã lọc" : ""}
              </span>
              <div className="actions">
                <button
                  className="icon-button"
                  aria-label="Trang trước"
                  disabled={currentPage === 1}
                  onClick={() => stateQuery(query, currentPage - 1)}
                >
                  <ChevronLeft size={18} />
                </button>
                <span>
                  Trang {currentPage}/{pages}
                </span>
                <button
                  className="icon-button"
                  aria-label="Trang sau"
                  disabled={currentPage === pages}
                  onClick={() => stateQuery(query, currentPage + 1)}
                >
                  <ChevronRight size={18} />
                </button>
              </div>
            </div>
          </>
        )}
      </section>
      {localPremium && (
        <LocalPremium
          user={localPremium}
          onClose={() => setLocalPremium(undefined)}
          onSaved={(text) => {
            setLocalPremium(undefined);
            setRefresh(refresh + 1);
            notify(text);
          }}
        />
      )}
      {editor !== undefined && (
        <Editor
          schema={schema}
          initial={editor}
          onDirty={onDirty}
          onClose={() => {
            setEditor(undefined);
            onDirty(false);
          }}
          onSaved={() => {
            setEditor(undefined);
            onDirty(false);
            setRefresh(refresh + 1);
            notify(
              schema.action === "issue"
                ? "Đã phát hành mã kích hoạt."
                : "Đã lưu thay đổi.",
            );
          }}
        />
      )}
      {remove && (
        <Modal
          title={
            schema.action === "issue" ? "Thu hồi mã kích hoạt?" : "Xóa bản ghi?"
          }
          busy={removeBusy}
          onClose={() => setRemove(undefined)}
        >
          <div className="modal-body">
            <p>
              {schema.action === "issue" ? "Thu hồi" : "Xóa"}{" "}
              <strong>
                {String(remove.name ?? remove.title ?? remove[keyField])}
              </strong>{" "}
              {schema.action === "issue"
                ? " bằng cách đặt số lượt còn lại về 0. Lịch sử sử dụng mã được giữ lại."
                : ` khỏi ${schema.title.toLowerCase()}. Thao tác này không có chức năng hoàn tác.`}
            </p>
            {schema.route === "users" && (
              <>
                <div className="notice warning">
                  Xóa tài khoản sẽ xóa các phiên đăng nhập, liên kết thiết bị,
                  quyền sử dụng, giao dịch sandbox và lịch sử của tài khoản này.
                </div>
                <FormField
                  field={{
                    key: "delete-confirmation",
                    label: "Nhập mã tài khoản để xác nhận",
                    hint: String(remove.id),
                  }}
                  value={deleteConfirmation}
                  onChange={(v) => setDeleteConfirmation(String(v))}
                />
              </>
            )}
            {actionError && <Notice error={actionError} />}
            <div className="actions">
              <button
                autoFocus
                className="button"
                disabled={removeBusy}
                onClick={() => setRemove(undefined)}
              >
                Hủy
              </button>
              <button
                className="button danger-button"
                disabled={
                  removeBusy ||
                  (schema.route === "users" &&
                    deleteConfirmation !== String(remove.id))
                }
                onClick={deleteRow}
              >
                {removeBusy && <Spinner />}
                {schema.action === "issue" ? "Thu hồi mã" : "Xóa bản ghi"}
              </button>
            </div>
          </div>
        </Modal>
      )}
      {payment && (
        <Modal
          title="Xử lý thanh toán sandbox"
          busy={removeBusy}
          onClose={() => setPayment(undefined)}
        >
          <div className="modal-body">
            <div className="notice warning">
              Không thu tiền thật. Chọn thành công sẽ cấp quyền sử dụng theo
              giao dịch.
            </div>
            <p>
              Giao dịch: <code>{String(payment.row.id)}</code>
            </p>
            <FormField
              field={{
                key: "payment-state",
                label: "Kết quả mô phỏng",
                type: "select",
                options: ["paid", "failed", "cancelled"],
              }}
              value={payment.status}
              onChange={(v) => setPayment({ ...payment, status: String(v) })}
            />
            {actionError && <Notice error={actionError} />}
            <div className="actions">
              <button
                autoFocus
                className="button"
                disabled={removeBusy}
                onClick={() => setPayment(undefined)}
              >
                Hủy
              </button>
              <button
                className="button primary"
                disabled={removeBusy}
                onClick={settle}
              >
                {removeBusy && <Spinner />}Xác nhận sandbox
              </button>
            </div>
          </div>
        </Modal>
      )}
      {detail && (
        <Modal title="Chi tiết bản ghi" onClose={() => setDetail(undefined)}>
          <div className="modal-body">
            <dl className="record-detail">
              {Object.entries(detail).map(([key, value]) => (
                <div key={key}>
                  <dt>
                    {schema.columns.find(([column]) => column === key)?.[1] ??
                      key}
                  </dt>
                  <dd>
                    <Cell value={value} column={key} />
                  </dd>
                </div>
              ))}
            </dl>
            {safeUrl(detail.checkout_url) && (
              <a
                className="button"
                href={safeUrl(detail.checkout_url)!}
                target="_blank"
                rel="noopener noreferrer"
              >
                Mở checkout sandbox
                <ArrowUpRight size={16} />
              </a>
            )}
          </div>
        </Modal>
      )}
    </>
  );
}
function Cell({ value, column }: { value: unknown; column: string }) {
  if (value === null || value === undefined || value === "")
    return <span className="muted">—</span>;
  if (column.endsWith("_at") || column === "last_seen")
    return <>{dateFormat(value)}</>;
  if (typeof value === "boolean")
    return (
      <span className={`badge ${value ? "success" : "neutral"}`}>
        {value ? "Có" : "Không"}
      </span>
    );
  if (column === "status") {
    const statuses: Record<string, string> = {
      paid: "Thành công",
      pending: "Đang chờ",
      failed: "Thất bại",
      cancelled: "Đã hủy",
      linked: "Đã liên kết",
    };
    return (
      <span
        className={`badge ${value === "paid" ? "success" : value === "pending" ? "warning" : "neutral"}`}
      >
        {statuses[String(value)] ?? String(value)}
      </span>
    );
  }
  if (column === "role")
    return (
      <>
        {(
          {
            admin: "Quản trị viên",
            user: "Người dùng",
            guest: "Khách",
          } as Record<string, string>
        )[String(value)] ?? String(value)}
      </>
    );
  if (typeof value === "number") return <>{numberFormat.format(value)}</>;
  if (typeof value === "object")
    return <code className="value-json">{JSON.stringify(value)}</code>;
  return (
    <span
      title={String(value)}
      className={
        ["id", "code", "user_id", "device_id", "sha256"].includes(column)
          ? "mono"
          : ""
      }
    >
      {String(value)}
    </span>
  );
}

function Editor({
  schema,
  initial,
  onClose,
  onSaved,
  onDirty,
}: {
  schema: EntitySchema;
  initial: RecordValue | null;
  onClose: () => void;
  onSaved: () => void;
  onDirty: (b: boolean) => void;
}) {
  const fields = schema.fields!;
  const defaults = Object.fromEntries(
    fields.map((field) => [
      field.key,
      field.type === "json"
        ? JSON.stringify(
            initial?.[field.key] ?? JSON.parse(String(field.default ?? "[]")),
            null,
            2,
          )
        : (initial?.[field.key] ??
          field.default ??
          (field.type === "boolean" ? false : "")),
    ]),
  );
  const [values, setValues] = useState<RecordValue>(defaults);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const busyLock = useRef(false);
  const [dirty, setDirty] = useState(false);
  const [permissionConfirm, setPermissionConfirm] = useState(false);
  async function save(e?: FormEvent) {
    e?.preventDefault();
    if (busyLock.current) return;
    const next = validate(fields, values);
    if (
      schema.route === "users" &&
      values.password &&
      Array.from(String(values.password)).length < 10
    )
      next.password = "Mật khẩu mới cần ít nhất 10 ký tự.";
    if (
      schema.route === "users" &&
      values.username &&
      !values.password &&
      (!initial || values.username !== initial.username)
    )
      next.password = "Nhập mật khẩu mới khi đặt tên đăng nhập.";
    if (values.url && !safeUrl(values.url))
      next.url = "Dùng đường dẫn HTTP/HTTPS hợp lệ.";
    if (values.sha256 && !/^[a-f\d]{64}$/i.test(String(values.sha256)))
      next.sha256 = "Nhập SHA-256 gồm 64 ký tự hex.";
    if (
      values.expires_at &&
      Number.isNaN(Date.parse(String(values.expires_at)))
    )
      next.expires_at = "Nhập thời gian ISO 8601 hợp lệ.";
    if (fields.some((f) => f.key === "features"))
      try {
        if (!Array.isArray(JSON.parse(String(values.features))))
          next.features = "Nhập một mảng JSON.";
      } catch {
        /* field validator handles syntax */
      }
    setErrors(next);
    if (Object.keys(next).length) {
      document.getElementById(`field-${Object.keys(next)[0]}`)?.focus();
      return;
    }
    if (
      schema.route === "users" &&
      values.role !== initial?.role &&
      !permissionConfirm
    ) {
      setPermissionConfirm(true);
      return;
    }
    busyLock.current = true;
    setBusy(true);
    setError("");
    const payload = { ...values };
    if (schema.route === "managed/roads" && payload.max_speed === "")
      payload.max_speed = null;
    if (schema.route === "users" && !payload.password) delete payload.password;
    for (const field of fields) {
      if (field.type === "json")
        payload[field.key] = JSON.parse(String(values[field.key]));
      if (field.key === "expires_at" && !payload[field.key])
        delete payload[field.key];
    }
    try {
      await api(
        `/v1/admin/${schema.route}${initial ? "/" + encodeURIComponent(String(initial.id)) : ""}`,
        initial ? "PUT" : "POST",
        payload,
      );
      onSaved();
    } catch (e) {
      setError(message(e));
      setPermissionConfirm(false);
    } finally {
      busyLock.current = false;
      setBusy(false);
    }
  }
  return (
    <Modal
      title={
        schema.action === "issue"
          ? "Phát hành mã kích hoạt"
          : `${initial ? "Sửa" : "Tạo"} ${schema.title.toLowerCase()}`
      }
      onClose={onClose}
      dirty={dirty}
      busy={busy}
    >
      <form noValidate onSubmit={save} className="modal-body">
        {permissionConfirm ? (
          <>
            <div className="notice warning">
              Bạn đang đặt vai trò tài khoản thành{" "}
              <strong>{String(values.role)}</strong>. Vai trò quản trị có quyền
              thay đổi dữ liệu hệ thống.
            </div>
            <div className="actions">
              <button
                type="button"
                autoFocus
                className="button"
                onClick={() => setPermissionConfirm(false)}
              >
                Kiểm tra lại
              </button>
              <button className="button primary" disabled={busy}>
                {busy && <Spinner />}Xác nhận vai trò và lưu
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="form-grid">
              {fields.map((field) => (
                <FormField
                  key={field.key}
                  field={field}
                  value={values[field.key]}
                  error={errors[field.key]}
                  onChange={(v) => {
                    setValues({ ...values, [field.key]: v });
                    setDirty(true);
                    onDirty(true);
                  }}
                />
              ))}
            </div>
            {error && <Notice error={error} />}
            <div className="actions form-actions">
              <CancelButton busy={busy} />
              <button className="button primary" disabled={busy}>
                {busy && <Spinner />}
                {schema.action === "issue" ? "Phát hành mã" : "Lưu thay đổi"}
              </button>
            </div>
          </>
        )}
      </form>
    </Modal>
  );
}

function Configuration({
  kind,
  notify,
  onDirty,
}: {
  kind: "config" | "providers" | "managed/config";
  notify: (s: string) => void;
  onDirty: (b: boolean) => void;
}) {
  const [refresh, setRefresh] = useState(0);
  const remote = useRemote<RecordValue>(`/v1/admin/${kind}`, refresh);
  const [value, setValue] = useState("");
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyLock = useRef(false);
  const [error, setError] = useState("");
  useEffect(() => {
    if (remote.data) {
      setValue(JSON.stringify(remote.data, null, 2));
      setDirty(false);
      onDirty(false);
    }
  }, [remote.data]);
  async function save(e: FormEvent) {
    e.preventDefault();
    if (busyLock.current) return;
    let payload: unknown;
    try {
      payload = JSON.parse(value);
      if (!payload || Array.isArray(payload) || typeof payload !== "object")
        throw new Error();
    } catch {
      setError("Nhập một đối tượng JSON hợp lệ.");
      document.getElementById("field-config-json")?.focus();
      return;
    }
    if (
      kind === "providers" &&
      /"(?:secret|password|token|api_key)"\s*:/i.test(value)
    ) {
      setError(
        "Không nhập khóa bí mật trực tiếp. Sử dụng tham chiếu biến môi trường hoặc đường dẫn file cấu hình.",
      );
      return;
    }
    busyLock.current = true;
    setBusy(true);
    setError("");
    try {
      const result = await api<RecordValue>(
        `/v1/admin/${kind}`,
        "PUT",
        payload,
      );
      setValue(JSON.stringify(result, null, 2));
      setDirty(false);
      onDirty(false);
      notify("Đã lưu cấu hình.");
    } catch (e) {
      setError(message(e));
    } finally {
      busyLock.current = false;
      setBusy(false);
    }
  }
  return (
    <section className="panel config-panel">
      {remote.busy ? (
        <Busy />
      ) : remote.error ? (
        <Notice error={remote.error} onRetry={() => setRefresh(refresh + 1)} />
      ) : (
        <form noValidate onSubmit={save}>
          {kind === "providers" ? (
            <div className="notice info">
              <Radio size={22} />
              <span>
                Khóa riêng được đọc từ biến môi trường hoặc file trên máy chủ.
                Màn hình này chỉ dùng cấu hình và tham chiếu; không hiển thị
                khóa bí mật.
              </span>
            </div>
          ) : (
            <p className="muted">
              Cấu hình công khai được gửi đến ứng dụng. Không đặt khóa bí mật
              trong cấu hình này.
            </p>
          )}
          <FormField
            field={{
              key: "config-json",
              label:
                kind === "providers"
                  ? "Cấu hình dịch vụ (JSON)"
                  : "Cấu hình ứng dụng (JSON)",
              type: "json",
              hint:
                kind === "providers"
                  ? "Giữ cấu trúc máy chủ trả về. Có thể đổi endpoint hoặc tên biến môi trường trong từng dịch vụ."
                  : "Đổi giá trị trong đối tượng máy chủ trả về.",
            }}
            value={value}
            error={error}
            onChange={(v) => {
              setValue(String(v));
              setDirty(true);
              onDirty(true);
            }}
          />
          <div className="actions">
            <button className="button primary" disabled={!dirty || busy}>
              {busy ? <Spinner /> : <Save size={18} />}Lưu cấu hình
            </button>
            <span className="muted">
              {dirty ? "Có thay đổi chưa lưu" : "Cấu hình hiện tại từ máy chủ"}
            </span>
          </div>
        </form>
      )}
    </section>
  );
}
