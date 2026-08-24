import { Bell } from "lucide-react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  actionNotification,
  markNotificationRead,
  selectNotificationSummary,
  useStore,
} from "../mock/store";
import {
  NotificationGlyph,
  relativeTime,
  resolveActionTarget,
} from "./NotificationGlyph";

/**
 * Rail bell — the notifications page's endpoints on a poll, not a separate
 * surface (notifications.md §4): badge = summary.unreadCount (red variant
 * when urgentCount > 0); dropdown = list #1 with status=UNREAD&size=5; row
 * click marks read (+ actioned when deep-linked) then navigates. No dismiss
 * or preferences here — those live on /notifications.
 */
export function BellMenu() {
  const [open, setOpen] = useState(false);
  const rows = useStore((s) => s.notifications.rows);
  const summary = useStore(selectNotificationSummary);
  const navigate = useNavigate();

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  const recent = rows.filter((n) => n.status === "UNREAD").slice(0, 5);

  const onRow = (id: string) => {
    const n = rows.find((r) => r.id === id);
    markNotificationRead(id);
    const route = resolveActionTarget(n?.actionTargetUri);
    if (route) {
      actionNotification(id); // fire-and-forget before navigation (§3b)
      setOpen(false);
      navigate(route);
    }
  };

  return (
    <div style={{ position: "relative" }}>
      <button
        type="button"
        className={`rail-item${open ? " active" : ""}`}
        title="Notifications"
        aria-label="Notifications"
        aria-haspopup="true"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <Bell size={19} strokeWidth={1.8} />
        {summary.unreadCount > 0 && (
          <span
            className="rail-badge"
            style={
              summary.urgentCount > 0 ? { background: "var(--mp-red)" } : undefined
            }
          >
            {summary.unreadCount > 9 ? "9+" : summary.unreadCount}
          </span>
        )}
      </button>
      {open && (
        <>
          <div
            className="bell-backdrop"
            onClick={() => setOpen(false)}
            role="presentation"
          />
          <div className="bell-pop mp-card" aria-label="Latest notifications">
            <div className="bell-pop-head">
              <span className="mp-label">Notifications</span>
            </div>
            {recent.length === 0 ? (
              <div className="bell-empty">You're all caught up.</div>
            ) : (
              recent.map((n) => (
                <button
                  key={n.id}
                  type="button"
                  className="bell-row"
                  onClick={() => onRow(n.id)}
                >
                  <NotificationGlyph kind={n.kind} />
                  <span style={{ flex: 1, minWidth: 0 }}>
                    <span className="bell-row-title" style={{ fontWeight: 600 }}>
                      {n.title}
                    </span>
                    <span className="bell-row-time">
                      {relativeTime(n.createdAt)}
                      {n.bundleCount > 1 && ` · ×${n.bundleCount} bundled`}
                    </span>
                  </span>
                  <span className="unread-dot" />
                </button>
              ))
            )}
            <div className="bell-pop-foot">
              <button
                className="btn btn-small"
                onClick={() => {
                  setOpen(false);
                  navigate("/notifications");
                }}
              >
                View all
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
