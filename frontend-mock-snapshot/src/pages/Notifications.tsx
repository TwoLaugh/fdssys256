/**
 * Notifications page — rebuilt against the contract-complete page spec
 * (design/frontend/pages/notifications.md). Contract list params (single
 * status/kind + pager), the §3b status state machine, the delivery-log
 * drawer (§3d) and the full-replace preferences PUT (§3e). The shell bell
 * consumes the same store (§4) — one source of truth.
 */

import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  KIND_META,
  NotificationGlyph,
  relativeTime,
  resolveActionTarget,
  SEVERITY_COLOR,
} from "../components/NotificationGlyph";
import { PageHeader } from "../components/PageHeader";
import { ALL_NOTIFICATION_KINDS } from "../mock/settingsAdminSeed";
import {
  actionNotification,
  bulkMarkNotificationsRead,
  dismissNotification,
  loadNotificationPrefs,
  markNotificationRead,
  pushToast,
  saveNotificationPrefs,
  selectNotificationSummary,
  useStore,
} from "../mock/store";
import type {
  AnyNotificationKind,
  DeliveryLogEntryDto,
  MockNotificationDto,
  NotificationStatus,
} from "../mock/types";

const PAGE_SIZE = 20;

/** Status tabs — `status` is a SINGLE optional param; "All" omits it (§3a).
 *  There is no multi-status param, so the "all except dismissed" inbox
 *  default isn't expressible — dismissed rows render collapsed instead
 *  (spec §8 Q3). */
const STATUS_TABS: Array<{ label: string; value: NotificationStatus | null }> = [
  { label: "All", value: null },
  { label: "Unread", value: "UNREAD" },
  { label: "Read", value: "READ" },
  { label: "Actioned", value: "ACTIONED" },
  { label: "Dismissed", value: "DISMISSED" },
];

/* ---- §3d delivery-log drawer ------------------------------------------------------- */

const OUTCOME_META: Record<
  DeliveryLogEntryDto["outcome"],
  { mark: string; color: string; label: string }
> = {
  DELIVERED: { mark: "✓", color: "var(--mp-olive)", label: "delivered" },
  SKIPPED: { mark: "—", color: "var(--mp-muted)", label: "skipped" },
  DEFERRED: { mark: "⏲", color: "var(--mp-amber)", label: "deferred" },
  FAILED: { mark: "✕", color: "var(--mp-red)", label: "failed" },
};

const SKIP_REASON_COPY: Record<string, string> = {
  DISABLED_BY_PREF: "muted in preferences",
  QUIET_HOURS: "held for quiet hours",
  DEDUPED_INTO_BUNDLE: "bundled into an earlier alert",
  CHANNEL_UNAVAILABLE: "channel unavailable",
};

function DeliveryLog({ notificationId }: { notificationId: string }) {
  // Lazy: only mounted (≈fetched) when the drawer opens (§2 #8).
  const entries = useStore(
    (s) => s.notifications.deliveryLog[notificationId] ?? null,
  );
  if (!entries || entries.length === 0) {
    return <div className="inline-note">No delivery attempts logged.</div>;
  }
  return (
    <div style={{ marginTop: 6 }}>
      {entries.map((e) => {
        const meta = OUTCOME_META[e.outcome];
        return (
          <div key={e.id} className="delivery-row">
            <span style={{ color: meta.color, fontWeight: 700, width: 16 }}>
              {meta.mark}
            </span>
            <span className="mp-chip">{e.channel.replace("_", "-").toLowerCase()}</span>
            <span style={{ fontSize: 13 }}>
              {meta.label}
              {e.skipReason && (
                <span style={{ color: "var(--mp-muted)" }}>
                  {" "}
                  — {SKIP_REASON_COPY[e.skipReason] ?? e.skipReason.toLowerCase()}
                </span>
              )}
            </span>
            <span className="notif-time" style={{ marginLeft: "auto" }}>
              {relativeTime(e.attemptedAt)}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/* ---- §3b row ------------------------------------------------------------------------ */

function NotificationRow({ n }: { n: MockNotificationDto }) {
  const navigate = useNavigate();
  const [expanded, setExpanded] = useState(false);
  const [logOpen, setLogOpen] = useState(false);
  const dismissed = n.status === "DISMISSED";
  const route = resolveActionTarget(n.actionTargetUri);

  // Row click marks read AND expands (§3b button semantics).
  const onRowClick = () => {
    if (!dismissed) markNotificationRead(n.id);
    setExpanded((e) => !e);
  };

  // Following the deep link marks actioned (fire-and-forget) then navigates.
  const onView = () => {
    actionNotification(n.id);
    if (route) navigate(route);
  };

  const stamps = [
    n.readAt && `read ${relativeTime(n.readAt)}`,
    n.actionedAt && `actioned ${relativeTime(n.actionedAt)}`,
    n.dismissedAt && `dismissed ${relativeTime(n.dismissedAt)}`,
  ]
    .filter(Boolean)
    .join(" · ");

  return (
    <div
      className="notif-row"
      style={dismissed ? { opacity: 0.55 } : undefined}
      role="button"
      tabIndex={0}
      onClick={onRowClick}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") onRowClick();
      }}
      aria-label={`${n.title}${n.status === "UNREAD" ? " (unread)" : ""}`}
    >
      <NotificationGlyph kind={n.kind} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <span
          className="notif-title"
          style={{
            fontWeight: n.status === "UNREAD" ? 600 : 400,
            color: SEVERITY_COLOR[n.severity],
          }}
        >
          {n.title}
          {n.bundleCount > 1 && (
            <span className="mp-chip" style={{ marginLeft: 8 }}>
              ×{n.bundleCount} bundled
            </span>
          )}
          {n.status === "ACTIONED" && (
            <span
              style={{ color: "var(--mp-olive)", marginLeft: 8, fontWeight: 700 }}
              title={stamps || undefined}
            >
              ✓
            </span>
          )}
        </span>
        <div className="notif-time" title={stamps || undefined}>
          {relativeTime(n.createdAt)}
          {n.severity !== "INFO" && ` · ${n.severity.toLowerCase()}`}
        </div>
        {expanded && (
          <div onClick={(e) => e.stopPropagation()} style={{ cursor: "default" }}>
            <div style={{ fontSize: 13.5, color: "var(--mp-muted)", marginTop: 6 }}>
              {n.body}
            </div>
            <button
              className="link-btn"
              style={{ marginTop: 6, fontSize: 12 }}
              onClick={() => setLogOpen((o) => !o)}
            >
              {logOpen ? "hide delivery" : "delivery"}
            </button>
            {logOpen && <DeliveryLog notificationId={n.id} />}
          </div>
        )}
      </div>
      {n.status === "UNREAD" && <span className="unread-dot" aria-hidden="true" />}
      {route && !dismissed && (
        <button
          className="btn btn-small"
          onClick={(e) => {
            e.stopPropagation();
            onView();
          }}
        >
          View
        </button>
      )}
      {/* No un-dismiss exists — dismiss hidden on terminal rows (§3b). */}
      {!dismissed && (
        <button
          className="btn btn-small"
          onClick={(e) => {
            e.stopPropagation();
            dismissNotification(n.id);
          }}
          aria-label={`Dismiss: ${n.title}`}
        >
          ✕
        </button>
      )}
    </div>
  );
}

/* ---- §3e preferences panel ----------------------------------------------------------- */

const TIMEZONES = ["Europe/London", "Europe/Dublin", "Europe/Paris", "UTC"];

function PreferencesPanel() {
  const prefs = useStore((s) => s.notifications.prefs);

  // GET on panel open — auto-seeds defaults server-side (idempotent, §3e).
  useEffect(() => {
    loadNotificationPrefs();
  }, []);

  // Form state initialised from the fetched row.
  const [enabled, setEnabled] = useState<Record<string, boolean> | null>(null);
  const [quietEnabled, setQuietEnabled] = useState(false);
  const [quietStart, setQuietStart] = useState<string>("22:00");
  const [quietEnd, setQuietEnd] = useState<string>("07:00");
  const [timezone, setTimezone] = useState("Europe/London");
  const [debounce, setDebounce] = useState(30);
  const [loadedVersion, setLoadedVersion] = useState<number | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);

  useEffect(() => {
    if (prefs && loadedVersion === null) {
      setEnabled(prefs.enabledKinds);
      setQuietEnabled(prefs.quietHoursEnabled);
      setQuietStart(prefs.quietHoursStart ?? "22:00");
      setQuietEnd(prefs.quietHoursEnd ?? "07:00");
      setTimezone(prefs.timezone);
      setDebounce(prefs.debounceWindowMinutes);
      setLoadedVersion(prefs.version);
    }
  }, [prefs, loadedVersion]);

  if (!prefs || enabled === null) {
    return <div className="page-loading">Loading preferences…</div>;
  }

  const save = () => {
    setFieldError(null);
    // Full replace — always send the whole document (§3e contract nit).
    const outcome = saveNotificationPrefs({
      enabledKinds: enabled,
      quietHoursEnabled: quietEnabled,
      quietHoursStart: quietEnabled ? quietStart : null,
      quietHoursEnd: quietEnabled ? quietEnd : null,
      timezone,
      debounceWindowMinutes: debounce,
      expectedVersion: loadedVersion ?? 0,
    });
    if (outcome === "ok") {
      setLoadedVersion((v) => (v == null ? v : v + 1));
    } else if (outcome === "conflict") {
      // 409 → re-fetch + re-apply (the store row is the server copy).
      setLoadedVersion(null);
    } else {
      setFieldError(
        "Quiet hours need both a start and an end time (window may wrap midnight).",
      );
    }
  };

  return (
    <div className="mp-card side-card" style={{ alignSelf: "start" }}>
      <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
        Preferences
      </span>
      <div style={{ marginTop: 10 }}>
        {ALL_NOTIFICATION_KINDS.map((kind) => {
          const on = enabled[kind] ?? true;
          return (
            <div key={kind} className="mute-row">
              <span style={{ display: "flex", alignItems: "center", gap: 9 }}>
                <NotificationGlyph kind={kind} />
                <span style={{ fontSize: 13.5 }}>
                  {KIND_META[kind].label}
                  {KIND_META[kind].gap && (
                    <span
                      style={{ color: "var(--mp-muted)" }}
                      title="In the Java enum but missing from the OpenAPI contract — see footnote"
                    >
                      {" "}
                      *
                    </span>
                  )}
                  {kind === "PLANNER_PLAN_GENERATED" && (
                    <span style={{ color: "var(--mp-muted)", fontSize: 11.5 }}>
                      {" "}
                      · opt-in
                    </span>
                  )}
                </span>
              </span>
              <button
                type="button"
                className={`switch${on ? " on" : ""}`}
                role="switch"
                aria-checked={on}
                aria-label={`${KIND_META[kind].label} notifications ${on ? "on" : "muted"}`}
                onClick={() => setEnabled({ ...enabled, [kind]: !on })}
              >
                <span className="switch-knob" />
              </button>
            </div>
          );
        })}
      </div>

      <div style={{ marginTop: 16 }}>
        <div className="mute-row" style={{ borderBottom: "none" }}>
          <span className="mp-label">Quiet hours</span>
          <button
            type="button"
            className={`switch${quietEnabled ? " on" : ""}`}
            role="switch"
            aria-checked={quietEnabled}
            aria-label="Quiet hours enabled"
            onClick={() => setQuietEnabled((q) => !q)}
          >
            <span className="switch-knob" />
          </button>
        </div>
        {quietEnabled && (
          <div style={{ display: "flex", gap: 8, alignItems: "center", marginTop: 4 }}>
            <input
              type="time"
              className="time-select"
              value={quietStart}
              onChange={(e) => setQuietStart(e.target.value)}
              aria-label="Quiet hours start"
            />
            <span style={{ color: "var(--mp-muted)", fontSize: 13 }}>to</span>
            <input
              type="time"
              className="time-select"
              value={quietEnd}
              onChange={(e) => setQuietEnd(e.target.value)}
              aria-label="Quiet hours end"
            />
          </div>
        )}
        <div className="grocery-footnote" style={{ marginTop: 6 }}>
          Deferred, never dropped — held alerts land after the window ends.
        </div>
      </div>

      <div style={{ marginTop: 14 }}>
        <span className="mp-label">Timezone</span>
        <select
          className="time-select"
          style={{ display: "block", marginTop: 6, width: "100%" }}
          value={timezone}
          onChange={(e) => setTimezone(e.target.value)}
          aria-label="Timezone"
        >
          {TIMEZONES.map((tz) => (
            <option key={tz} value={tz}>
              {tz}
            </option>
          ))}
        </select>
      </div>

      <details style={{ marginTop: 14 }}>
        <summary className="mp-label" style={{ cursor: "pointer" }}>
          Advanced — bundling
        </summary>
        <label
          style={{
            display: "flex",
            gap: 10,
            alignItems: "center",
            marginTop: 8,
            fontSize: 13,
          }}
        >
          Bundle repeats within
          <input
            type="number"
            className="num-input"
            min={0}
            max={360}
            value={debounce}
            onChange={(e) =>
              setDebounce(Math.max(0, Math.min(360, Number(e.target.value) || 0)))
            }
            aria-label="Debounce window minutes"
          />
          min
        </label>
      </details>

      {fieldError && (
        <div className="rf-errors" style={{ marginTop: 10 }}>
          {fieldError}
        </div>
      )}

      <button className="btn btn-primary" style={{ marginTop: 14 }} onClick={save}>
        Save preferences
      </button>

      <div className="grocery-footnote" style={{ marginTop: 14 }}>
        * STAPLE_REPLENISHMENT_NEEDED and FEEDBACK_CONFIRMATION exist in the
        backend but are missing from the OpenAPI enum — generated clients
        can't parse rows of these kinds (backend gap, spec §8 Q1).
      </div>
    </div>
  );
}

/* ---- the page ------------------------------------------------------------------------- */

export function Notifications() {
  const rows = useStore((s) => s.notifications.rows);
  const summary = useStore(selectNotificationSummary);

  // §3a contract list params: single status, single kind, page, size.
  const [status, setStatus] = useState<NotificationStatus | null>(null);
  const [kind, setKind] = useState<AnyNotificationKind | "">("");
  const [page, setPage] = useState(0);

  const filtered = useMemo(
    () =>
      rows.filter(
        (n) => (!status || n.status === status) && (!kind || n.kind === kind),
      ),
    [rows, status, kind],
  );

  // Spring page metadata, as the contract returns it (§3a).
  const totalElements = filtered.length;
  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const content = filtered.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE);

  // Bulk read scoped to the active kind filter (§3f); ignores status filter.
  const markAll = () => {
    const updated = bulkMarkNotificationsRead(kind ? [kind] : []);
    pushToast(`${updated} marked read`);
  };

  const presentKinds = ALL_NOTIFICATION_KINDS.filter((k) =>
    rows.some((n) => n.kind === k),
  );

  return (
    <div>
      <PageHeader
        title="Notifications"
        meta={`${summary.unreadCount} unread`}
        chip={
          <>
            {summary.attentionCount > 0 && (
              <span className="mp-chip" style={{ color: "var(--mp-amber)" }}>
                {summary.attentionCount} need attention
              </span>
            )}
            {summary.urgentCount > 0 && (
              <span className="mp-chip" style={{ color: "var(--mp-red)" }}>
                {summary.urgentCount} urgent
              </span>
            )}
          </>
        }
        actions={
          <button
            className="btn"
            onClick={markAll}
            disabled={summary.unreadCount === 0}
            title={
              kind
                ? `Marks unread ${KIND_META[kind].label.toLowerCase()} rows read`
                : "Marks every unread row read"
            }
          >
            Mark all read{kind ? ` · ${KIND_META[kind].label.toLowerCase()}` : ""}
          </button>
        }
      />

      <div className="filter-row" style={{ marginTop: 20 }}>
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.label}
            className={`filter-chip${status === tab.value ? " active" : ""}`}
            onClick={() => {
              setStatus(tab.value);
              setPage(0);
            }}
          >
            {tab.label}
          </button>
        ))}
        <span className="mp-label" style={{ marginLeft: 12 }}>
          Kind
        </span>
        {/* Single-valued param — a dropdown, not multi-select chips (§3a). */}
        <select
          className="time-select"
          value={kind}
          onChange={(e) => {
            setKind(e.target.value as AnyNotificationKind | "");
            setPage(0);
          }}
          aria-label="Filter by kind"
        >
          <option value="">All kinds</option>
          {presentKinds.map((k) => (
            <option key={k} value={k}>
              {KIND_META[k].label}
              {KIND_META[k].gap ? " *" : ""}
            </option>
          ))}
        </select>
      </div>

      <div className="notif-layout">
        <div>
          {content.length === 0 ? (
            <div className="page-loading">
              {status || kind
                ? `No ${kind ? KIND_META[kind].label.toLowerCase() : ""} notifications${
                    status ? ` with status ${status.toLowerCase()}` : ""
                  }.`
                : "You're all caught up."}
            </div>
          ) : (
            content.map((n) => <NotificationRow key={n.id} n={n} />)
          )}

          {totalPages > 1 && (
            <div className="filter-row" style={{ marginTop: 14 }}>
              <button
                className="btn btn-small"
                disabled={safePage === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                ← Newer
              </button>
              <span style={{ fontSize: 12.5, color: "var(--mp-muted)" }}>
                page {safePage + 1} of {totalPages} · {totalElements} total
              </span>
              <button
                className="btn btn-small"
                disabled={safePage >= totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
              >
                Older →
              </button>
            </div>
          )}

          <div className="grocery-footnote" style={{ marginTop: 18 }}>
            Deep links come from the server as /app/* URIs that don't match
            the IA routes — this client maps them statically (spec §8 Q2).
            Status and kind filters are single-valued in the contract, so the
            inbox default shows dismissed rows collapsed instead of excluded
            (spec §8 Q3).
          </div>
        </div>

        <PreferencesPanel />
      </div>
    </div>
  );
}
