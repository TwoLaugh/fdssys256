/**
 * Directives tab — spec §5: proposed health directives from connected
 * platforms, never auto-applied. Status filter chips, expandable rows with
 * evidence + instruction + safety-gate verdict, Accept (disabled on BLOCKED)
 * with a modify-before-accepting expander, Reject with optional reason.
 */

import { useState } from "react";
import {
  acceptDirective,
  rejectDirective,
  useStore,
} from "../../mock/store";
import type {
  DirectiveStatus,
  HealthDirectiveDto,
} from "../../mock/types";
import { shortDate, shortTime } from "./shared";

const STATUSES: DirectiveStatus[] = [
  "PENDING_REVIEW",
  "ACCEPTED",
  "REJECTED",
  "SUPERSEDED",
  "EXPIRED",
];

const statusLabel = (s: string): string => s.toLowerCase().replace(/_/g, " ");

function StatusChip({ status }: { status: DirectiveStatus }) {
  if (status === "PENDING_REVIEW") {
    return <span className="tint-chip terra">pending review</span>;
  }
  if (status === "ACCEPTED") {
    return <span className="tint-chip olive">accepted</span>;
  }
  return <span className="tier-badge">{statusLabel(status)}</span>;
}

function ConfidenceChip({ conf }: { conf: "LOW" | "MODERATE" | "HIGH" }) {
  const cls =
    conf === "HIGH" ? "tint-chip olive" : conf === "LOW" ? "tint-chip amber" : "tier-badge";
  return <span className={cls}>{conf.toLowerCase()} confidence</span>;
}

const VERDICT_TONE: Record<string, string> = {
  PASSED: "olive",
  PASSED_WITH_WARNINGS: "amber",
  BLOCKED: "red",
};

function prettyDateTime(iso: string): string {
  return `${shortDate(iso.slice(0, 10))} · ${shortTime(iso)}`;
}

function DirectiveRow({ d }: { d: HealthDirectiveDto }) {
  const [open, setOpen] = useState(false);
  const [modifyOpen, setModifyOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [modAction, setModAction] = useState(d.instruction.action);
  const [modTarget, setModTarget] = useState(d.instruction.target ?? "");
  const [modScope, setModScope] = useState(d.instruction.scope ?? "");

  const blocked = d.safetyGateVerdict === "BLOCKED";
  const duration = d.instruction.duration;
  const phases = duration?.phases ?? [];

  return (
    <div className="mp-card section-card">
      <div
        style={{
          display: "flex",
          gap: 10,
          alignItems: "center",
          flexWrap: "wrap",
        }}
      >
        <strong style={{ fontSize: 15 }}>{d.sourcePlatform}</strong>
        <span className="tier-badge">{statusLabel(d.directiveType)}</span>
        <span className="inline-note">received {prettyDateTime(d.receivedAt)}</span>
        {d.temporary && (
          <span
            className="tint-chip amber"
            title={
              d.autoExpiresAt
                ? `auto-expires ${prettyDateTime(d.autoExpiresAt)}`
                : "temporary"
            }
          >
            ⏱ temporary
          </span>
        )}
        <span style={{ flex: 1 }} />
        <StatusChip status={d.status} />
        <button className="btn btn-small" onClick={() => setOpen((o) => !o)}>
          {open ? "Hide" : "Details"}
        </button>
      </div>

      {open && (
        <div style={{ marginTop: 14, display: "grid", gap: 16 }}>
          {/* Evidence */}
          <div>
            <span className="mp-label">Evidence</span>
            <div style={{ fontSize: 14, marginTop: 6, lineHeight: 1.5 }}>
              {d.evidenceSummary ?? "No evidence summary supplied."}
            </div>
            {d.evidenceConfidence && (
              <div style={{ marginTop: 6 }}>
                <ConfidenceChip conf={d.evidenceConfidence} />
              </div>
            )}
          </div>

          {/* Instruction */}
          <div>
            <span className="mp-label">Instruction</span>
            <table className="nv-table" style={{ marginTop: 6, maxWidth: 560 }}>
              <tbody>
                <tr>
                  <td className="inline-note" style={{ width: 90 }}>
                    action
                  </td>
                  <td>{d.instruction.action}</td>
                </tr>
                {d.instruction.target && (
                  <tr>
                    <td className="inline-note">target</td>
                    <td>{d.instruction.target}</td>
                  </tr>
                )}
                {d.instruction.scope && (
                  <tr>
                    <td className="inline-note">scope</td>
                    <td>{d.instruction.scope}</td>
                  </tr>
                )}
                {duration && (
                  <tr>
                    <td className="inline-note">duration</td>
                    <td>
                      {duration.type?.toLowerCase().replace(/_/g, " ")}
                      {duration.durationWeeks
                        ? ` · ${duration.durationWeeks} weeks`
                        : ""}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
            {phases.length > 0 && (
              <table className="nv-table" style={{ marginTop: 10, maxWidth: 560 }}>
                <thead>
                  <tr>
                    <th>Phase</th>
                    <th>Weeks</th>
                    <th>Rule</th>
                  </tr>
                </thead>
                <tbody>
                  {phases.map((ph) => (
                    <tr key={ph.phase}>
                      <td>{ph.phase}</td>
                      <td>{ph.durationWeeks ?? "—"}</td>
                      <td>{ph.rule ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          {/* Safety gate */}
          <div>
            <span className="mp-label">Safety gate</span>
            <div style={{ marginTop: 8 }}>
              {d.safetyGateVerdict ? (
                <span
                  className={`verdict-pill ${VERDICT_TONE[d.safetyGateVerdict]}`}
                >
                  {statusLabel(d.safetyGateVerdict)}
                </span>
              ) : (
                <span className="inline-note">not evaluated</span>
              )}
            </div>
            {(d.safetyGateFindings ?? []).map((f) => (
              <div key={f.code} className="finding-row">
                <span className={`severity-mark ${f.severity}`}>
                  {f.severity}
                </span>
                <span>{f.message}</span>
              </div>
            ))}
          </div>

          {/* Decision */}
          {d.status === "PENDING_REVIEW" ? (
            <div style={{ display: "grid", gap: 10 }}>
              <div
                style={{
                  display: "flex",
                  gap: 10,
                  flexWrap: "wrap",
                  alignItems: "center",
                }}
              >
                <button
                  className="btn btn-primary"
                  disabled={blocked}
                  title={
                    blocked
                      ? "Blocked by the safety gate — cannot be accepted"
                      : undefined
                  }
                  onClick={() => acceptDirective(d.id)}
                >
                  Accept
                </button>
                <button
                  className="btn"
                  disabled={blocked}
                  title={
                    blocked
                      ? "Blocked by the safety gate — cannot be accepted"
                      : undefined
                  }
                  onClick={() => setModifyOpen((o) => !o)}
                >
                  Modify before accepting
                </button>
                <button className="btn" onClick={() => setRejectOpen((o) => !o)}>
                  Reject
                </button>
              </div>

              {modifyOpen && !blocked && (
                <div className="mp-card" style={{ padding: "14px 18px" }}>
                  <span className="mp-serif" style={{ fontSize: 18 }}>
                    Tweak the instruction before it lands —
                  </span>
                  <div style={{ display: "grid", gap: 8, marginTop: 10 }}>
                    <label style={{ display: "grid", gap: 4 }}>
                      <span className="field-label">action</span>
                      <input
                        type="text"
                        className="text-input"
                        value={modAction}
                        onChange={(e) => setModAction(e.target.value)}
                        aria-label="Modified action"
                      />
                    </label>
                    <label style={{ display: "grid", gap: 4 }}>
                      <span className="field-label">target</span>
                      <input
                        type="text"
                        className="text-input"
                        value={modTarget}
                        onChange={(e) => setModTarget(e.target.value)}
                        aria-label="Modified target"
                      />
                    </label>
                    <label style={{ display: "grid", gap: 4 }}>
                      <span className="field-label">scope</span>
                      <input
                        type="text"
                        className="text-input"
                        value={modScope}
                        onChange={(e) => setModScope(e.target.value)}
                        aria-label="Modified scope"
                      />
                    </label>
                  </div>
                  <div className="modal-actions">
                    <button
                      className="btn btn-primary"
                      disabled={!modAction.trim()}
                      onClick={() =>
                        acceptDirective(d.id, {
                          action: modAction.trim(),
                          target: modTarget.trim() || null,
                          scope: modScope.trim() || null,
                        })
                      }
                    >
                      Accept with modification
                    </button>
                  </div>
                </div>
              )}

              {rejectOpen && (
                <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                  <input
                    type="text"
                    className="text-input"
                    style={{ flex: 1, minWidth: 220 }}
                    placeholder="Reason (optional, ≤255)"
                    maxLength={255}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    aria-label="Rejection reason"
                  />
                  <button
                    className="btn"
                    onClick={() => rejectDirective(d.id, reason)}
                  >
                    Confirm reject
                  </button>
                </div>
              )}
            </div>
          ) : (
            <div className="inline-note">
              decided {d.decidedAt ? prettyDateTime(d.decidedAt) : "—"}
              {d.userModification &&
                ` · modified: ${[
                  d.userModification.action,
                  d.userModification.target,
                  d.userModification.scope,
                ]
                  .filter(Boolean)
                  .join(" / ")}`}
              {d.rejectionReason && ` · reason: “${d.rejectionReason}”`}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export function DirectivesTab() {
  const directives = useStore((s) => s.nutrition.directives);
  const [filter, setFilter] = useState<DirectiveStatus>("PENDING_REVIEW");
  const list = directives.filter((d) => d.status === filter);

  return (
    <div>
      <div className="filter-row" style={{ marginTop: 18 }}>
        {STATUSES.map((st) => {
          const count = directives.filter((d) => d.status === st).length;
          return (
            <button
              key={st}
              className={`filter-chip${filter === st ? " active" : ""}`}
              onClick={() => setFilter(st)}
            >
              {statusLabel(st)}
              {count > 0 ? ` · ${count}` : ""}
            </button>
          );
        })}
      </div>
      {list.length === 0 ? (
        <div className="intake-meta" style={{ padding: "16px 0" }}>
          Nothing with this status.
        </div>
      ) : (
        list.map((d) => <DirectiveRow key={d.id} d={d} />)
      )}
      <div className="inline-note" style={{ marginTop: 14 }}>
        Directives arrive from connected health platforms and are never
        auto-applied — accepting one edits your targets on your behalf.
      </div>
    </div>
  );
}
