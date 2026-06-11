import { Bell } from "lucide-react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { markNotificationRead, useStore } from "../mock/store";
import { NotificationGlyph } from "./NotificationGlyph";

/**
 * Rail bell: click opens a digest dropdown with the latest five unmuted
 * notifications (click = mark read) and a "View all" link to /notifications.
 * Esc or clicking outside closes it.
 */
export function BellMenu({ badge }: { badge: number }) {
  const [open, setOpen] = useState(false);
  const notifications = useStore((s) => s.notifications);
  const muted = useStore((s) => s.notificationPrefs.muted);
  const navigate = useNavigate();

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  const recent = notifications
    .filter((n) => !muted.includes(n.kind))
    .slice(0, 5);

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
        {badge > 0 && (
          <span className="rail-badge">{badge > 9 ? "9+" : badge}</span>
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
              <div className="bell-empty">Nothing yet.</div>
            ) : (
              recent.map((n) => (
                <button
                  key={n.id}
                  type="button"
                  className="bell-row"
                  onClick={() => markNotificationRead(n.id)}
                >
                  <NotificationGlyph kind={n.kind} />
                  <span style={{ flex: 1, minWidth: 0 }}>
                    <span
                      className="bell-row-title"
                      style={{ fontWeight: n.read ? 400 : 600 }}
                    >
                      {n.title}
                    </span>
                    <span className="bell-row-time">{n.time}</span>
                  </span>
                  {!n.read && <span className="unread-dot" />}
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
