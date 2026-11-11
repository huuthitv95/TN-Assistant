import { useEffect, useRef, useState } from "react";
import { Check, X, Headphones, Upload } from "lucide-react";
import { api, message, type RecordValue } from "./api";
import { Notice, Spinner } from "./ui";

export function NewsAudioAction({
  id,
  onReady,
}: {
  id: string;
  onReady?: () => void;
}) {
  const [job, setJob] = useState<RecordValue>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  useEffect(() => {
    if (
      !job ||
      !["queued", "running", "processing"].includes(String(job.status))
    )
      return;
    const timer = window.setTimeout(() => {
      api<RecordValue>(`/v1/admin/managed/news/${encodeURIComponent(id)}/job`)
        .then((j) => {
          if (active.current) {
            setJob(j);
            if (j.status === "ready") onReady?.();
          }
        })
        .catch((e) => {
          if (active.current) {
            setError(message(e));
            setJob(undefined);
          }
        });
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [job, id]);
  async function generate() {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError("");
    try {
      const j = await api<RecordValue>(
        `/v1/admin/managed/news/${encodeURIComponent(id)}/audio`,
        "POST",
      );
      if (active.current) {
        setJob(j);
        if (j.status === "ready") onReady?.();
      }
    } catch (e) {
      if (active.current) setError(message(e));
    } finally {
      lock.current = false;
      if (active.current) setBusy(false);
    }
  }
  const running =
    !!job && ["queued", "running", "processing"].includes(String(job.status));
  const statusLabels: Record<string, string> = {
    queued: "Đang chờ",
    running: "Đang tạo audio",
    processing: "Đang tạo audio",
    ready: "Audio đã sẵn sàng",
    failed: "Không tạo được audio",
  };
  const statusLabel = statusLabels[String(job?.status)] ?? "";
  return (
    <div>
      <button
        className="button small"
        disabled={busy || running}
        onClick={generate}
      >
        {busy || running ? <Spinner /> : <Headphones size={16} />} Tạo audio
      </button>
      {job && (
        <small role="status">
          {" "}
          {statusLabel} {String(job.completed_chunks ?? 0)}/
          {String(job.total_chunks ?? 0)}
        </small>
      )}
      {error && <Notice error={error} />}
    </div>
  );
}
export function FeedbackAction({
  id,
  onDone,
}: {
  id: string;
  onDone: () => void;
}) {
  const [note, setNote] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  async function review(status: string) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError("");
    try {
      await api(
        `/v1/admin/managed/feedback/${encodeURIComponent(id)}`,
        "PATCH",
        { status, review_note: note },
      );
      onDone();
    } catch (e) {
      setError(message(e));
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <div>
      <input
        aria-label={`Ghi chú duyệt ${id}`}
        placeholder="Ghi chú kiểm chứng"
        value={note}
        disabled={busy}
        onChange={(e) => setNote(e.target.value)}
      />
      <div className="actions">
        <button
          className="button small"
          disabled={busy}
          onClick={() => review("accepted")}
        >
          <Check size={16} /> Duyệt
        </button>
        <button
          className="button small"
          disabled={busy}
          onClick={() => review("rejected")}
        >
          <X size={16} /> Từ chối
        </button>
      </div>
      {error && <Notice error={error} />}
    </div>
  );
}
export function RoadImport({ onDone }: { onDone: () => void }) {
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState("");
  const lock = useRef(false);
  async function read(file?: File) {
    if (!file || lock.current) return;
    lock.current = true;
    setBusy(true);
    setError("");
    setProgress("");
    try {
      if (file.size > 40 * 1024 * 1024)
        throw new Error(
          "Tệp JSON tối đa 40 MB; chia dữ liệu thành các tệp nhỏ hơn.",
        );
      const data: unknown = JSON.parse(await file.text());
      const items = Array.isArray(data)
        ? data
        : (data as { items?: unknown[] }).items;
      if (!Array.isArray(items) || !items.length)
        throw new Error("Chọn JSON có mảng items từ công cụ nhập OSM.");
      for (let i = 0; i < items.length; i += 2000) {
        await api("/v1/admin/managed/roads/import", "POST", {
          items: items.slice(i, i + 2000),
        });
        setProgress(
          `Đã nhập ${Math.min(i + 2000, items.length)}/${items.length} bản ghi`,
        );
      }
      onDone();
    } catch (e) {
      setError(message(e));
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <section className="panel">
      <label className="button">
        <Upload size={18} /> Nhập dữ liệu đường JSON
        <input
          aria-label="Nhập dữ liệu đường JSON"
          type="file"
          accept=".json,application/json"
          disabled={busy}
          onChange={(e) => {
            void read(e.target.files?.[0]);
            e.target.value = "";
          }}
        />
      </label>
      <p role="status">
        {busy && <Spinner />}
        {progress}
      </p>
      {error && <Notice error={error} />}
    </section>
  );
}
