import { useEffect, useState } from "react";
import { api, dateFormat, message, type RecordValue } from "./api";
import { Busy, FormField, Modal, Notice, Spinner } from "./ui";
import type { LocalPremium as LocalPremiumRight } from "./generated/api";

type LocalRight = LocalPremiumRight;

export function LocalPremium({
  user,
  onClose,
  onSaved,
}: {
  user: RecordValue;
  onClose: () => void;
  onSaved: (text: string) => void;
}) {
  const path = `/v1/admin/premium-local/${encodeURIComponent(String(user.id))}`;
  const [right, setRight] = useState<LocalRight>();
  const [days, setDays] = useState("30");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [reload, setReload] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setError("");
    setRight(undefined);
    api<LocalRight>(path, "GET", undefined, controller.signal)
      .then(setRight)
      .catch((e) => {
        if (!controller.signal.aborted) setError(message(e));
      });
    return () => controller.abort();
  }, [path, reload]);

  async function save(revoke: boolean) {
    const duration = Number(days);
    if (
      !revoke &&
      (!Number.isInteger(duration) || duration < 1 || duration > 365)
    ) {
      setError("Nhập số ngày từ 1 đến 365.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await api(
        path,
        revoke ? "DELETE" : "PUT",
        revoke ? undefined : { duration_days: duration },
      );
      onSaved(
        revoke
          ? "Đã thu hồi Premium local."
          : "Đã cấp Premium local. Mở lại mục tài khoản trên ứng dụng để cập nhật quyền.",
      );
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal title="Premium local" busy={busy} onClose={onClose}>
      <div className="modal-body">
        <p>
          <strong>{String(user.name ?? user.id)}</strong>
        </p>
        <p>
          Cấp các tính năng Premium để kiểm thử ứng dụng, không cần thanh toán.
          Quyền chỉ áp dụng cho tài khoản này và không cấp quyền trên dịch vụ
          nhà cung cấp.
        </p>
        {right && (
          <div className="notice" role="status">
            {right.enabled
              ? `Đang bật · hết hạn ${dateFormat(right.expires_at)}`
              : "Premium local đang tắt."}
          </div>
        )}
        {!right && !error && <Busy />}
        {right && (
          <FormField
            field={{
              key: "local_premium_days",
              label: "Thời hạn mới (ngày)",
              type: "number",
              min: 1,
              max: 365,
              required: true,
              hint: "Tính từ thời điểm cấp; tối đa 365 ngày. Thu hồi local giữ các quyền từ gói khác.",
            }}
            value={days}
            disabled={busy}
            onChange={(value) => setDays(String(value))}
          />
        )}
        {error && (
          <Notice
            error={error}
            onRetry={!right ? () => setReload(reload + 1) : undefined}
          />
        )}
        <div className="actions">
          <button className="button" disabled={busy} onClick={onClose}>
            Hủy
          </button>
          {right?.enabled && (
            <button
              className="button danger-button"
              disabled={busy}
              onClick={() => save(true)}
            >
              Thu hồi local
            </button>
          )}
          <button
            className="button primary"
            disabled={busy || !right}
            onClick={() => save(false)}
          >
            {busy && <Spinner />}
            {right?.enabled ? "Cấp lại thời hạn" : "Cấp Premium local"}
          </button>
        </div>
      </div>
    </Modal>
  );
}
