/**
 * Admin console — rebuilt against the contract-complete page spec
 * (design/frontend/pages/admin.md). Allowlist-gated, fail-closed, read-only
 * in v1: status card, AI cost summary + call log, prompt drawer, decision-log
 * explorer (id / trace / ancestry) and the planner decision chain. All money
 * goes through the single two-unit formatter (§7 Q1).
 */

import { useMemo, useState } from "react";
import { relativeTime } from "../components/NotificationGlyph";
import { Modal } from "../components/Modal";
import { PageHeader } from "../components/PageHeader";
import { StatStrip } from "../components/StatStrip";
import {
  adminCostSummary,
  decisionsForTrace,
  findDecision,
  plannerChainFor,
  pushToast,
  setAdminAllowlisted,
  useStore,
  walkAncestry,
} from "../mock/store";
import type {
  AiCallLogDto,
  AiTaskType,
  AncestryResponse,
  DecisionLogDto,
  ModelTier,
  PromptTemplateDto,
} from "../mock/types";
import { microPenceDetail, poundsFromMicroPence, poundsFromPence } from "./admin/money";

const PAGE_SIZE = 20;

const TASK_TYPES: AiTaskType[] = [
  "PREFERENCE_DELTA_UPDATE",
  "INGREDIENT_MAPPING",
  "INTAKE_PARSE",
  "FEEDBACK_CLASSIFICATION",
  "RECIPE_ADAPTATION",
  "RECIPE_HTML_EXTRACTION",
  "DISCOVERY_FILTERING",
  "PLANNER_STAGE_C",
  "PLANNER_PHASE2_AUGMENTATION",
];

const TIER_COLOR: Record<ModelTier, string> = {
  CHEAP: "var(--mp-muted)",
  MID: "var(--mp-amber)",
  HIGH: "var(--mp-terra)",
};

const STATUS_COLOR: Record<AiCallLogDto["status"], string> = {
  PENDING: "var(--mp-amber)",
  SUCCEEDED: "var(--mp-olive)",
  FAILED: "var(--mp-red)",
};

const WINDOWS: Array<{ label: string; hours: number }> = [
  { label: "24h", hours: 24 },
  { label: "72h", hours: 72 },
  { label: "7d", hours: 168 },
  { label: "30d", hours: 720 },
];

function Json({ value }: { value: unknown }) {
  return <pre className="raw-json">{JSON.stringify(value, null, 2)}</pre>;
}

/* ---- §3a status card ------------------------------------------------------------------- */

function StatusCard() {
  const status = useStore((s) => s.admin.status);
  const up = status.status === "UP";
  return (
    <div className="mp-card side-card">
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Status
        </span>
        <span
          className="mp-chip"
          style={{ color: up ? "var(--mp-olive)" : "var(--mp-red)" }}
        >
          {up ? "✓ UP" : "✕ DEGRADED"}
        </span>
      </div>
      <div className="admin-status-row">
        <span style={{ flex: 1, fontWeight: 600, fontSize: 14 }}>Database</span>
        <span
          style={{
            color: status.dbConnected ? "var(--mp-olive)" : "var(--mp-red)",
            fontSize: 13,
          }}
        >
          {status.dbConnected ? "✓ connected" : "✕ unreachable"}
        </span>
      </div>
      <div className="admin-status-row">
        <span style={{ flex: 1, fontWeight: 600, fontSize: 14 }}>Last AI call</span>
        <span style={{ color: "var(--mp-muted)", fontSize: 13 }}>
          {status.lastAiCallAt ? relativeTime(status.lastAiCallAt) : "none yet"}
        </span>
      </div>
      <div className="admin-status-row">
        <span style={{ flex: 1, fontWeight: 600, fontSize: 14 }}>
          Last USDA call
          <span className="invite-sent"> · since last restart</span>
        </span>
        <span style={{ color: "var(--mp-muted)", fontSize: 13 }}>
          {status.lastUsdaCallAt ? relativeTime(status.lastUsdaCallAt) : "none yet"}
        </span>
      </div>
      <div className="admin-status-row">
        <span style={{ flex: 1, fontWeight: 600, fontSize: 14 }}>AI spend</span>
        {/* aiMonthToDatePence is plain PENCE — the one non-micro figure. */}
        <span className="mp-num" style={{ fontSize: 15 }}>
          {poundsFromPence(status.aiMonthToDatePence)}
          <span className="invite-sent"> this month (UTC)</span>
        </span>
      </div>
      <div className="grocery-footnote" style={{ marginTop: 10 }}>
        checked {relativeTime(status.checkedAt)} · DEGRADED is still a 200 —
        not an error state.
      </div>
    </div>
  );
}

/* ---- §3b cost card ----------------------------------------------------------------------- */

function CostCard({
  windowHours,
  setWindowHours,
  onUserClick,
}: {
  windowHours: number;
  setWindowHours: (h: number) => void;
  onUserClick: (userId: string) => void;
}) {
  const callLog = useStore((s) => s.admin.callLog);
  const summary = useMemo(
    () => adminCostSummary(callLog, windowHours),
    [callLog, windowHours],
  );
  return (
    <div className="mp-card side-card">
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          AI cost
        </span>
        <span style={{ display: "flex", gap: 6, marginLeft: "auto" }}>
          {WINDOWS.map((w) => (
            <button
              key={w.hours}
              className={`filter-chip${windowHours === w.hours ? " active" : ""}`}
              onClick={() => setWindowHours(w.hours)}
            >
              {w.label}
            </button>
          ))}
        </span>
      </div>
      <div style={{ marginTop: 12 }}>
        <StatStrip
          numeralSize={20}
          compact
          cells={[
            { label: "Calls", value: String(summary.totalCalls) },
            {
              label: "Spend",
              value: poundsFromMicroPence(summary.totalMicroPence),
              sub: "micro-pence ÷ 10⁸",
            },
          ]}
        />
      </div>
      <div style={{ marginTop: 10 }}>
        <span className="mp-label">Top spenders</span>
        {summary.topUsers.length === 0 && (
          <div className="inline-note" style={{ marginTop: 6 }}>
            No calls in this window.
          </div>
        )}
        {summary.topUsers.map((u) => (
          <button
            key={u.userId}
            className="admin-status-row link-btn"
            style={{ width: "100%", textAlign: "left" }}
            onClick={() => onUserClick(u.userId)}
            title="Filter the call log by this user"
          >
            {/* Raw userId — no username join exists admin-side (§7 Q3). */}
            <span style={{ flex: 1, fontFamily: "monospace", fontSize: 12.5 }}>
              {u.userId}
            </span>
            <span style={{ fontSize: 12.5, color: "var(--mp-muted)" }}>
              {u.calls} calls
            </span>
            <span className="mp-num" style={{ fontSize: 13.5 }}>
              {microPenceDetail(u.costMicroPence)}
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}

/* ---- §3c call log -------------------------------------------------------------------------- */

function CallLog({
  userFilter,
  setUserFilter,
  onTrace,
}: {
  userFilter: string | null;
  setUserFilter: (u: string | null) => void;
  onTrace: (traceId: string) => void;
}) {
  const callLog = useStore((s) => s.admin.callLog);
  const templates = useStore((s) => s.admin.promptTemplates);
  const [taskType, setTaskType] = useState<AiTaskType | "">("");
  const [page, setPage] = useState(0);
  const [openPrompt, setOpenPrompt] = useState<PromptTemplateDto | null>(null);

  const filtered = useMemo(
    () =>
      callLog.filter(
        (c) =>
          (!taskType || c.taskType === taskType) &&
          (!userFilter || c.userId === userFilter),
      ),
    [callLog, taskType, userFilter],
  );
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const rows = filtered.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE);

  const openPromptRef = (c: AiCallLogDto) => {
    const t = templates.find(
      (p) => p.name === c.promptRefName && p.version === c.promptRefVersion,
    );
    if (t) setOpenPrompt(t);
    else pushToast("404 — no such template version", "warn");
  };

  return (
    <div className="mp-card side-card" style={{ gridColumn: "1 / -1" }}>
      <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Call log
        </span>
        <select
          className="time-select"
          value={taskType}
          onChange={(e) => {
            setTaskType(e.target.value as AiTaskType | "");
            setPage(0);
          }}
          aria-label="Filter by task type"
        >
          <option value="">All task types</option>
          {TASK_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
        {userFilter && (
          <button
            className="filter-chip active"
            onClick={() => setUserFilter(null)}
            title="Clear user filter"
          >
            user: {userFilter} ✕
          </button>
        )}
      </div>

      <div className="table-scroll" style={{ marginTop: 10 }}>
        <table className="nv-table" style={{ width: "100%" }}>
          <thead>
            <tr>
              <th style={{ textAlign: "left" }}>When</th>
              <th style={{ textAlign: "left" }}>Task</th>
              <th style={{ textAlign: "left" }}>Tier · model</th>
              <th style={{ textAlign: "left" }}>Status</th>
              <th style={{ textAlign: "right" }}>Tokens</th>
              <th style={{ textAlign: "right" }}>Cost</th>
              <th style={{ textAlign: "left" }}>Prompt</th>
              <th style={{ textAlign: "left" }}>Trace</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id}>
                <td title={c.completedAt ? `completed ${c.completedAt}` : "running"}>
                  {relativeTime(c.createdAt)}
                  {c.latencyMs != null && (
                    <span className="invite-sent"> · {(c.latencyMs / 1000).toFixed(1)}s</span>
                  )}
                </td>
                <td style={{ fontSize: 12 }}>{c.taskType}</td>
                <td>
                  <span className="mp-chip" style={{ color: TIER_COLOR[c.modelTier] }}>
                    {c.modelTier}
                  </span>{" "}
                  <span style={{ fontSize: 12 }}>{c.modelId}</span>
                </td>
                <td>
                  <span style={{ color: STATUS_COLOR[c.status], fontSize: 12.5 }}>
                    {c.status.toLowerCase()}
                    {c.errorKind && ` · ${c.errorKind}`}
                  </span>
                </td>
                <td style={{ textAlign: "right", fontSize: 12.5 }}>
                  {c.requestTokens ?? "—"} / {c.responseTokens ?? "—"}
                </td>
                <td className="mp-num" style={{ textAlign: "right", fontSize: 12.5 }}>
                  {microPenceDetail(c.costMicroPence)}
                </td>
                <td>
                  {c.promptRefName ? (
                    <button className="link-btn" onClick={() => openPromptRef(c)}>
                      {c.promptRefName}@{c.promptRefVersion}
                    </button>
                  ) : (
                    "—"
                  )}
                </td>
                <td>
                  {c.traceId ? (
                    <button className="link-btn" onClick={() => onTrace(c.traceId!)}>
                      {c.traceId.slice(0, 12)}…
                    </button>
                  ) : (
                    "—"
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="filter-row" style={{ marginTop: 10 }}>
          <button
            className="btn btn-small"
            disabled={safePage === 0}
            onClick={() => setPage((p) => p - 1)}
          >
            ← Newer
          </button>
          <span style={{ fontSize: 12.5, color: "var(--mp-muted)" }}>
            page {safePage + 1} of {totalPages}
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

      {openPrompt && (
        <Modal
          label={`Prompt template ${openPrompt.name} v${openPrompt.version}`}
          onClose={() => setOpenPrompt(null)}
          wide
        >
          <div className="dialog-title">
            {openPrompt.name} · v{openPrompt.version}
            <span className="mp-chip" style={{ marginLeft: 8 }}>
              {openPrompt.modelTier}
            </span>
          </div>
          <div className="dialog-body">
            <span className="mp-label">System prompt</span>
            <pre className="raw-json">{openPrompt.systemPrompt}</pre>
            <span className="mp-label">User prompt template</span>
            <pre className="raw-json">{openPrompt.userPromptTemplate}</pre>
            {openPrompt.outputSchema && (
              <>
                <span className="mp-label">Output schema</span>
                <Json value={openPrompt.outputSchema} />
              </>
            )}
            {openPrompt.tools && (
              <>
                <span className="mp-label">Tools</span>
                <Json value={openPrompt.tools} />
              </>
            )}
            <div className="grocery-footnote">
              {openPrompt.sourceFile} · {openPrompt.sourceHash}
              {openPrompt.notes && ` · ${openPrompt.notes}`}
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}

/* ---- §3d decision-log explorer --------------------------------------------------------------- */

function DecisionRow({ d }: { d: DecisionLogDto }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="history-row" style={{ marginTop: 8, display: "block" }}>
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
        <span className="mp-chip">{d.scopeKind}</span>
        <span className="mp-chip">{d.scale}</span>
        <span style={{ fontSize: 12.5 }}>{d.triggeredBy}</span>
        <span className="invite-sent">
          iteration {d.iteration}
          {d.durationMs != null && ` · ${(d.durationMs / 1000).toFixed(1)}s`} ·{" "}
          {relativeTime(d.createdAt)}
          {d.actorUserId && ` · ${d.actorUserId}`}
        </span>
        <button
          className="link-btn"
          style={{ marginLeft: "auto" }}
          onClick={() => setOpen((o) => !o)}
        >
          {open ? "collapse" : "expand"}
        </button>
      </div>
      <div style={{ fontFamily: "monospace", fontSize: 11.5, color: "var(--mp-muted)", marginTop: 4 }}>
        {d.decisionId}
        {d.parentDecisionId && ` ← ${d.parentDecisionId}`}
      </div>
      {d.reasoning && (
        <div className="mp-serif" style={{ fontSize: 15.5, marginTop: 6 }}>
          {d.reasoning}
        </div>
      )}
      {open && (
        <div style={{ marginTop: 6 }}>
          <span className="mp-label">Inputs</span>
          <Json value={d.inputs} />
          {d.candidates && (
            <>
              <span className="mp-label">Candidates</span>
              <Json value={d.candidates} />
            </>
          )}
          {d.chosen && (
            <>
              <span className="mp-label">Chosen</span>
              <Json value={d.chosen} />
            </>
          )}
        </div>
      )}
    </div>
  );
}

function DecisionExplorer({
  query,
  setQuery,
  mode,
  setMode,
}: {
  query: string;
  setQuery: (q: string) => void;
  mode: "decision" | "trace";
  setMode: (m: "decision" | "trace") => void;
}) {
  const store = useStore((s) => s);
  const [ancestry, setAncestry] = useState<AncestryResponse | null>(null);
  const [searched, setSearched] = useState(false);

  const decision = mode === "decision" && searched ? findDecision(store, query) : undefined;
  const traceRows = mode === "trace" && searched ? decisionsForTrace(store, query) : null;

  return (
    <div className="mp-card side-card" style={{ gridColumn: "1 / -1" }}>
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Decision log
        </span>
        <button
          className={`filter-chip${mode === "decision" ? " active" : ""}`}
          onClick={() => setMode("decision")}
        >
          by decision id
        </button>
        <button
          className={`filter-chip${mode === "trace" ? " active" : ""}`}
          onClick={() => setMode("trace")}
        >
          by trace id
        </button>
        <input
          className="text-input"
          style={{ flex: 1, minWidth: 200 }}
          placeholder={mode === "decision" ? "decisionId, e.g. dcn-0100" : "traceId, e.g. trace-aaaa-0001"}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setSearched(false);
            setAncestry(null);
          }}
          onKeyDown={(e) => e.key === "Enter" && setSearched(true)}
          aria-label="Decision or trace id"
        />
        <button className="btn btn-small" onClick={() => setSearched(true)}>
          Look up
        </button>
      </div>

      {searched && mode === "decision" && !decision && (
        <div className="inline-note" style={{ marginTop: 8 }}>
          404 — no such decision.
        </div>
      )}
      {decision && (
        <>
          <DecisionRow d={decision} />
          {decision.parentDecisionId && (
            <button
              className="btn btn-small"
              style={{ marginTop: 8 }}
              onClick={() => setAncestry(walkAncestry(store, decision.decisionId))}
            >
              Walk ancestry
            </button>
          )}
          {ancestry && (
            <div style={{ marginTop: 8 }}>
              <span className="mp-label">Ancestry — root first</span>
              {ancestry.cycleDetected && (
                <div style={{ color: "var(--mp-red)", fontSize: 13, marginTop: 4 }}>
                  ✕ depth cap hit — parent chain may be cyclic.
                </div>
              )}
              {ancestry.ancestors.map((a) => (
                <DecisionRow key={a.decisionId} d={a} />
              ))}
            </div>
          )}
        </>
      )}
      {traceRows && (
        <div style={{ marginTop: 8 }}>
          {traceRows.length === 0 ? (
            // Empty list is a valid result, not an error (§3d).
            <div className="inline-note">No decisions for this trace.</div>
          ) : (
            traceRows.map((d) => <DecisionRow key={d.decisionId} d={d} />)
          )}
        </div>
      )}
    </div>
  );
}

/* ---- §3e planner chain ------------------------------------------------------------------------ */

function PlannerChainPanel() {
  const store = useStore((s) => s);
  const activePlanId = useStore(
    (s) => s.planner.plans.find((p) => p.status === "ACTIVE")?.id ?? "",
  );
  const [planId, setPlanId] = useState("");
  const [traceId, setTraceId] = useState("");
  const [searched, setSearched] = useState(false);

  const chain = searched ? plannerChainFor(store, planId, traceId || undefined) : null;

  return (
    <div className="mp-card side-card" style={{ gridColumn: "1 / -1" }}>
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
        <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
          Planner decision chain
        </span>
        <input
          className="text-input"
          style={{ width: 220 }}
          placeholder="planId"
          value={planId}
          onChange={(e) => {
            setPlanId(e.target.value);
            setSearched(false);
          }}
          aria-label="Plan id"
        />
        <input
          className="text-input"
          style={{ width: 220 }}
          placeholder="traceId (optional narrowing)"
          value={traceId}
          onChange={(e) => {
            setTraceId(e.target.value);
            setSearched(false);
          }}
          aria-label="Trace id"
        />
        <button
          className="btn btn-small"
          onClick={() => setSearched(true)}
          disabled={!planId.trim()}
        >
          Look up
        </button>
        {activePlanId && (
          <button
            className="link-btn"
            onClick={() => {
              setPlanId(activePlanId);
              setSearched(true);
            }}
          >
            use the active plan ({activePlanId})
          </button>
        )}
      </div>

      {chain &&
        (chain.rows.length === 0 ? (
          <div className="inline-note" style={{ marginTop: 8 }}>
            No rows — plans generated before planner-01l have no decision
            chain (empty list; no retroactive backfill).
          </div>
        ) : (
          <div style={{ marginTop: 10 }}>
            {chain.rows.map((r, i) => (
              <div key={r.decisionId} className="history-row" style={{ display: "block", marginTop: 8 }}>
                <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                  <span className="mp-num" style={{ fontSize: 13, width: 18 }}>
                    {i + 1}
                  </span>
                  <span className="mp-chip">{r.kind ?? "STEP"}</span>
                  <span className="invite-sent">{relativeTime(r.createdAt)}</span>
                </div>
                {r.reasoning && (
                  <div className="mp-serif" style={{ fontSize: 15, marginTop: 4, marginLeft: 26 }}>
                    {r.reasoning}
                  </div>
                )}
                <details style={{ marginLeft: 26, marginTop: 4 }}>
                  <summary style={{ fontSize: 12, color: "var(--mp-muted)", cursor: "pointer" }}>
                    inputs / outputs
                  </summary>
                  <Json value={{ inputs: r.inputs, outputs: r.outputs ?? null }} />
                </details>
              </div>
            ))}
          </div>
        ))}
    </div>
  );
}

/* ---- the page ----------------------------------------------------------------------------------- */

export function Admin() {
  const probeOutcome = useStore((s) => s.admin.probeOutcome);
  const [windowHours, setWindowHours] = useState(24);
  const [userFilter, setUserFilter] = useState<string | null>(null);
  const [explorerQuery, setExplorerQuery] = useState("");
  const [explorerMode, setExplorerMode] = useState<"decision" | "trace">("decision");

  // §5: 403 = a quiet full-page dead-end — no detail, no retry, fail-closed.
  if (probeOutcome !== "admin") {
    return (
      <div style={{ display: "grid", placeItems: "center", minHeight: "60vh" }}>
        <div style={{ textAlign: "center" }}>
          <div className="mp-serif" style={{ fontSize: 24 }}>
            This area is restricted.
          </div>
          {/* Mock-only demo control — not part of the spec UI. */}
          <button
            className="link-btn"
            style={{ marginTop: 18, fontSize: 11.5 }}
            data-mock-demo="allowlist"
            onClick={() => setAdminAllowlisted(true)}
          >
            (mock demo: restore allowlist membership)
          </button>
        </div>
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Admin"
        chip={<span className="mp-chip">allowlisted</span>}
        meta="Operator console — read-only in v1; mutating admin verbs stay ops/curl"
        actions={
          <button
            className="btn btn-small"
            data-mock-demo="allowlist"
            onClick={() => setAdminAllowlisted(false)}
            title="Mock demo: see the non-admin dead-end + hidden nav"
          >
            View as non-admin
          </button>
        }
      />

      <div className="settings-grid">
        <StatusCard />
        <CostCard
          windowHours={windowHours}
          setWindowHours={setWindowHours}
          onUserClick={(u) => setUserFilter(u)}
        />
        <CallLog
          userFilter={userFilter}
          setUserFilter={setUserFilter}
          onTrace={(t) => {
            setExplorerMode("trace");
            setExplorerQuery(t);
          }}
        />
        <DecisionExplorer
          query={explorerQuery}
          setQuery={setExplorerQuery}
          mode={explorerMode}
          setMode={setExplorerMode}
        />
        <PlannerChainPanel />
      </div>

      <div className="grocery-footnote" style={{ marginTop: 18 }}>
        Money: all AI figures are integer micro-pence (÷10⁸ → £) except the
        status card's month-to-date, which is plain pence (÷10²) — one
        formatter handles both (spec §7 Q1). userId columns render raw — no
        username join exists admin-side (§7 Q3). Allowlist editing is server
        config (mealprep.admin.user-ids); no API exists.
      </div>
    </div>
  );
}
