import { useState } from "react";
import {
  dayGroup,
  KIND_META,
  NotificationGlyph,
} from "../components/NotificationGlyph";
import { PageHeader } from "../components/PageHeader";
import {
  dismissNotification,
  markAllNotificationsRead,
  markNotificationRead,
  setQuietHours,
  toggleMutedKind,
  useStore,
} from "../mock/store";
import type { AppNotification, NotificationKind } from "../mock/types";

const ALL_KINDS = Object.keys(KIND_META) as NotificationKind[];
const DAY_ORDER = ["Today", "Yesterday", "Earlier"] as const;

const QUIET_START_OPTIONS = ["20:00", "21:00", "22:00", "23:00"];
const QUIET_END_OPTIONS = ["06:00", "07:00", "08:00", "09:00"];

function NotificationRow({ n }: { n: AppNotification }) {
  return (
    <div
      className="notif-row"
      role="button"
      tabIndex={0}
      onClick={() => markNotificationRead(n.id)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") markNotificationRead(n.id);
      }}
      aria-label={`${n.title}${n.read ? "" : " (unread)"} — mark read`}
    >
      <NotificationGlyph kind={n.kind} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <span
          className="notif-title"
          style={{ fontWeight: n.read ? 400 : 600 }}
        >
          {n.title}
        </span>
        <div className="notif-time">{n.time}</div>
      </div>
      {!n.read && <span className="unread-dot" aria-hidden="true" />}
      <button
        className="btn btn-small"
        onClick={(e) => {
          e.stopPropagation();
          dismissNotification(n.id);
        }}
        aria-label={`Dismiss: ${n.title}`}
      >
        Dismiss
      </button>
    </div>
  );
}

export function Notifications() {
  const notifications = useStore((s) => s.notifications);
  const prefs = useStore((s) => s.notificationPrefs);
  const [kindFilter, setKindFilter] = useState<NotificationKind | null>(null);

  const visible = kindFilter
    ? notifications.filter((n) => n.kind === kindFilter)
    : notifications;
  const unread = notifications.filter((n) => !n.read).length;
  const presentKinds = ALL_KINDS.filter((k) =>
    notifications.some((n) => n.kind === k),
  );

  return (
    <div>
      <PageHeader
        title="Notifications"
        meta={`${notifications.length} total · ${unread} unread`}
        actions={
          <button
            className="btn"
            onClick={markAllNotificationsRead}
            disabled={unread === 0}
          >
            Mark all read
          </button>
        }
      />

      <div className="filter-row" style={{ marginTop: 20 }}>
        <span className="mp-label">Kind</span>
        {presentKinds.map((kind) => (
          <button
            key={kind}
            className={`filter-chip${kindFilter === kind ? " active" : ""}`}
            onClick={() => setKindFilter(kindFilter === kind ? null : kind)}
          >
            {KIND_META[kind].label}
          </button>
        ))}
      </div>

      <div className="notif-layout">
        <div>
          {visible.length === 0 ? (
            <div className="page-loading">Nothing here — try another kind.</div>
          ) : (
            DAY_ORDER.map((day) => {
              const rows = visible.filter((n) => dayGroup(n.time) === day);
              if (rows.length === 0) return null;
              return (
                <div key={day} style={{ marginBottom: 22 }}>
                  <div className="group-head">
                    <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
                      {day}
                    </span>
                  </div>
                  {rows.map((n) => (
                    <NotificationRow key={n.id} n={n} />
                  ))}
                </div>
              );
            })
          )}
        </div>

        <div className="mp-card side-card" style={{ alignSelf: "start" }}>
          <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
            Preferences
          </span>
          <div style={{ marginTop: 10 }}>
            {ALL_KINDS.map((kind) => {
              const muted = prefs.muted.includes(kind);
              return (
                <div key={kind} className="mute-row">
                  <span style={{ display: "flex", alignItems: "center", gap: 9 }}>
                    <NotificationGlyph kind={kind} />
                    <span style={{ fontSize: 13.5 }}>
                      {KIND_META[kind].label}
                    </span>
                  </span>
                  <button
                    type="button"
                    className={`switch${muted ? "" : " on"}`}
                    role="switch"
                    aria-checked={!muted}
                    aria-label={`${KIND_META[kind].label} notifications ${
                      muted ? "muted" : "on"
                    }`}
                    onClick={() => toggleMutedKind(kind)}
                  >
                    <span className="switch-knob" />
                  </button>
                </div>
              );
            })}
          </div>
          <div style={{ marginTop: 18 }}>
            <span className="mp-label">Quiet hours</span>
            <div style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 9 }}>
              <select
                className="time-select"
                value={prefs.quietStart}
                onChange={(e) => setQuietHours(e.target.value, prefs.quietEnd)}
                aria-label="Quiet hours start"
              >
                {QUIET_START_OPTIONS.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
              <span style={{ color: "var(--mp-muted)", fontSize: 13 }}>to</span>
              <select
                className="time-select"
                value={prefs.quietEnd}
                onChange={(e) => setQuietHours(prefs.quietStart, e.target.value)}
                aria-label="Quiet hours end"
              >
                {QUIET_END_OPTIONS.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="grocery-footnote" style={{ marginTop: 14 }}>
            Muted kinds drop out of the bell and badge; quiet hours hold
            non-urgent pushes until morning.
          </div>
        </div>
      </div>
    </div>
  );
}
