/**
 * Plan page — rebuilt against the contract-complete page spec
 * (design/frontend/pages/plan.md). PlanDto shapes throughout: per-status
 * header machine (§5), rollup stat strip (§3b), quality-warnings drill-in
 * (§3c), week grid with the slot state machine + 409 guards (§3d), re-opt
 * suggestions with the two-step confirm (§3e), history + revert (§3f).
 */

import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { AdvisorPanel } from "../components/AdvisorPanel";
import { Modal } from "../components/Modal";
import { PageHeader } from "../components/PageHeader";
import { StatStrip } from "../components/StatStrip";
import type { StatStripCell } from "../components/StatStrip";
import { StatusMark } from "../components/StatusMark";
import { SwapLine } from "../components/SwapLine";
import { MOCK_TODAY_ISO } from "../mock/nutritionSeed";
import { CURRENT_WEEK_START, KNOWN_WEEKS } from "../mock/plannerSeed";
import {
  abandonPlan,
  acceptPlan,
  acceptSuggestion,
  changeSlotState,
  recipeName,
  rejectPlan,
  rejectSuggestion,
  revertToPlan,
  useStore,
} from "../mock/store";
import type {
  MealSlotDto,
  PlanDto,
  ReoptSuggestionDto,
  SlotState,
} from "../mock/types";
import {
  PlanBadges,
  PlanStatusChip,
  QualityWarningsPanel,
  TRIGGER_LABEL,
  fmtDateTime,
  leadTime,
  planOutcomeLine,
  shortDayLabel,
  weekRangeLabel,
} from "./plan/shared";

/* ---- reason popover (reject #8 / abandon #9, ≤255 chars) ------------------------- */

function ReasonModal({
  title,
  cta,
  onClose,
  onConfirm,
}: {
  title: string;
  cta: string;
  onClose: () => void;
  onConfirm: (reason: string) => void;
}) {
  const [reason, setReason] = useState("");
  return (
    <Modal label={title} onClose={onClose}>
      <span className="mp-label">{title}</span>
      <div style={{ marginTop: 12 }}>
        <input
          type="text"
          className="text-input"
          style={{ width: "100%" }}
          maxLength={255}
          placeholder="Reason (optional, ≤255)"
          value={reason}
          autoFocus
          onChange={(e) => setReason(e.target.value)}
        />
      </div>
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>
          Cancel
        </button>
        <button
          className="btn btn-primary"
          onClick={() => {
            onConfirm(reason);
            onClose();
          }}
        >
          {cta}
        </button>
      </div>
    </Modal>
  );
}

/* ---- slot detail popover (§3d field map + slot actions) --------------------------- */

const SLOT_ACTIONS: Partial<
  Record<SlotState, Array<{ label: string; next: SlotState; primary?: boolean }>>
> = {
  PLANNED: [
    { label: "Start cooking", next: "COOKING", primary: true },
    { label: "Skip", next: "SKIPPED" },
  ],
  COOKING: [
    { label: "Mark cooked", next: "COOKED", primary: true },
    { label: "Skip", next: "SKIPPED" },
  ],
  COOKED: [{ label: "Mark eaten", next: "EATEN", primary: true }],
};

const PIN_COPY: Record<string, string> = {
  EATEN: "pinned: already eaten — re-optimisation won't touch this",
  COOKED: "pinned: already cooked — re-optimisation won't touch this",
  COOKING: "pinned: cooking now — re-optimisation won't touch this",
  SKIPPED: "pinned: skipped — re-optimisation won't touch this",
  USER_PINNED: "pinned by you — re-optimisation won't touch this",
};

function SlotDetailModal({
  plan,
  slot,
  date,
  nameOf,
  memberNames,
  onClose,
}: {
  plan: PlanDto;
  slot: MealSlotDto;
  date: string;
  nameOf: (id: string) => string;
  memberNames: Record<string, string>;
  onClose: () => void;
}) {
  const navigate = useNavigate();
  const sr = slot.scheduledRecipe;
  const actions = plan.status === "ACTIVE" ? SLOT_ACTIONS[slot.state] : undefined;
  const row = (label: string, value: React.ReactNode) => (
    <div className="slot-detail-row">
      <span className="mp-label">{label}</span>
      <span>{value}</span>
    </div>
  );
  return (
    <Modal label={`${slot.label} · ${shortDayLabel(date)}`} onClose={onClose}>
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <StatusMark status={slot.state} />
        <span className="mp-label">
          {shortDayLabel(date)} · {slot.label} · {slot.state.toLowerCase()}
        </span>
      </div>
      <div style={{ marginTop: 6, fontSize: 19, fontWeight: 600 }}>
        {sr ? nameOf(sr.recipeId) : "— no recipe scheduled"}
      </div>
      {slot.pinnedReason && (
        <div className="inline-note" style={{ marginTop: 4 }}>
          {PIN_COPY[slot.pinnedReason]}
        </div>
      )}
      <div style={{ marginTop: 14, display: "grid", gap: 7 }}>
        {row(
          "Serve time",
          slot.mealTime ?? "not set — schedule fallback is server-internal (spec Q3)",
        )}
        {row("Time budget", `${slot.timeBudgetMin} min`)}
        {row(
          "Eaters",
          slot.shared
            ? `Shared · ${slot.eaters.length} eating (${slot.eaters
                .map((id) => memberNames[id] ?? id)
                .join(", ")})`
            : "Just you",
        )}
        {sr && row("Servings", `serves ${sr.servings}`)}
        {sr?.batchCookSessionId &&
          row("Batch session", sr.batchCookSessionId)}
        {sr?.augmentationNotes &&
          row(
            "Augmentation",
            <span>
              <span className="mp-serif" style={{ fontSize: 15 }}>
                {sr.augmentationNotes}
              </span>{" "}
              <span className="tint-chip terra">
                {sr.augmentationSource === "LLM" ? "AI addition" : "your addition"}
              </span>
            </span>,
          )}
        {sr?.phase2Addition && row("Origin", "added in creative pass")}
      </div>
      <div className="modal-actions" style={{ justifyContent: "space-between" }}>
        {sr ? (
          <button
            className="btn btn-small"
            onClick={() => {
              onClose();
              navigate(`/recipes/${sr.recipeId}`);
            }}
          >
            View recipe version
          </button>
        ) : (
          <span />
        )}
        <span style={{ display: "flex", gap: 8 }}>
          {actions?.map((a) => (
            <button
              key={a.next}
              className={`btn btn-small${a.primary ? " btn-primary" : ""}`}
              onClick={() => {
                changeSlotState(plan.id, slot.id, a.next);
                onClose();
              }}
            >
              {a.label}
            </button>
          ))}
          <button className="btn btn-small" onClick={onClose}>
            Close
          </button>
        </span>
      </div>
      {plan.status !== "ACTIVE" && (
        <div className="inline-note" style={{ marginTop: 10 }}>
          read-only — slot actions are enabled only while the plan is active
        </div>
      )}
    </Modal>
  );
}

/* ---- week grid (§3d) --------------------------------------------------------------- */

function WeekGrid({
  plan,
  affectedSlotIds,
  nameOf,
  memberNames,
}: {
  plan: PlanDto;
  affectedSlotIds: Set<string>;
  nameOf: (id: string) => string;
  memberNames: Record<string, string>;
}) {
  const [openSlot, setOpenSlot] = useState<{ date: string; slotId: string } | null>(
    null,
  );

  // Columns from the plan's slot set (slot configuration drives the slots;
  // CUSTOM slots render under their own label), ordered by slotIndex.
  const columns = useMemo(() => {
    const byIndex = new Map<number, string>();
    for (const d of plan.days) {
      for (const sl of d.slots) {
        if (!byIndex.has(sl.slotIndex)) byIndex.set(sl.slotIndex, sl.label);
      }
    }
    return [...byIndex.entries()].sort((a, b) => a[0] - b[0]);
  }, [plan]);

  const open =
    openSlot &&
    plan.days
      .find((d) => d.date === openSlot.date)
      ?.slots.find((sl) => sl.id === openSlot.slotId);

  return (
    <div>
      <div className="plan-scroll">
        <div
          className="plan-grid plan-grid-head"
          style={{ gridTemplateColumns: `118px repeat(${columns.length}, 1fr)` }}
        >
          <span />
          {columns.map(([idx, label]) => (
            <span key={idx} className="mp-label">
              {label}
            </span>
          ))}
        </div>
        {plan.days.map((day) => {
          const isToday = day.date === MOCK_TODAY_ISO;
          return (
            <div
              key={day.date}
              className={`plan-grid plan-row${isToday ? " today" : ""}`}
              style={{ gridTemplateColumns: `118px repeat(${columns.length}, 1fr)` }}
            >
              <div className="plan-day-cell">
                <div className="plan-day">
                  <span
                    className="mp-num"
                    style={{
                      fontSize: 17,
                      color: isToday ? "var(--mp-terra)" : "var(--mp-ink)",
                    }}
                  >
                    {shortDayLabel(day.date)}
                  </span>
                  {isToday && <span className="plan-today-tag">TODAY</span>}
                </div>
                {day.notes && <div className="plan-day-notes">{day.notes}</div>}
              </div>
              {columns.map(([idx]) => {
                const slot = day.slots.find((sl) => sl.slotIndex === idx);
                if (!slot) return <span key={idx} />;
                const affected = affectedSlotIds.has(slot.id);
                const name = slot.scheduledRecipe
                  ? nameOf(slot.scheduledRecipe.recipeId)
                  : slot.kind === "CUSTOM"
                    ? slot.label
                    : "— no recipe";
                const empty = slot.scheduledRecipe === null && slot.kind !== "CUSTOM";
                const upcoming =
                  isToday &&
                  plan.status === "ACTIVE" &&
                  slot.state === "PLANNED" &&
                  slot.mealTime != null;
                return (
                  <button
                    key={slot.id}
                    type="button"
                    className="plan-cell plan-cell-btn"
                    title={
                      slot.pinnedReason
                        ? PIN_COPY[slot.pinnedReason]
                        : affected
                          ? "affected by the pending suggestion"
                          : undefined
                    }
                    onClick={() => setOpenSlot({ date: day.date, slotId: slot.id })}
                  >
                    <StatusMark status={affected ? "AFFECTED" : slot.state} />
                    <span style={{ minWidth: 0 }}>
                      <span
                        className={`plan-cell-name ${
                          affected
                            ? "affected"
                            : empty && plan.qualityWarning
                              ? "empty-warn"
                              : slot.state.toLowerCase()
                        }`}
                        title={name}
                      >
                        {name}
                      </span>
                      {(slot.mealTime || upcoming) && (
                        <span className="plan-cell-time">
                          {slot.mealTime}
                          {upcoming &&
                            ` · start by ${leadTime(slot.mealTime as string, slot.timeBudgetMin)}`}
                        </span>
                      )}
                    </span>
                    {slot.scheduledRecipe?.batchCookSessionId && (
                      <span className="batch-tag">BATCH</span>
                    )}
                    {affected && (
                      <span className="visually-hidden">affected by suggestion</span>
                    )}
                  </button>
                );
              })}
            </div>
          );
        })}
      </div>
      <div className="plan-legend">
        <span>
          <span style={{ color: "var(--mp-mark-planned)" }}>○</span> planned
        </span>
        <span>
          <span style={{ color: "var(--mp-amber)" }}>◐</span> cooking
        </span>
        <span>
          <span style={{ color: "var(--mp-amber)" }}>●</span> cooked
        </span>
        <span>
          <span style={{ color: "var(--mp-olive)", fontWeight: 700 }}>✓</span>{" "}
          eaten
        </span>
        <span>
          <span style={{ color: "var(--mp-muted)" }}>—</span> skipped
        </span>
        <span>
          <span style={{ color: "var(--mp-red)", fontWeight: 700 }}>✕</span>{" "}
          affected by suggestion
        </span>
        <span>
          <span className="batch-tag" style={{ fontSize: 11 }}>
            BATCH
          </span>{" "}
          batch-cook link
        </span>
      </div>
      {openSlot && open && (
        <SlotDetailModal
          plan={plan}
          slot={open}
          date={openSlot.date}
          nameOf={nameOf}
          memberNames={memberNames}
          onClose={() => setOpenSlot(null)}
        />
      )}
    </div>
  );
}

/* ---- re-opt suggestions panel (§3e) -------------------------------------------------- */

const REOPT_TRIGGER_LABEL: Record<ReoptSuggestionDto["triggerKind"], string> = {
  PROVISIONS: "provisions",
  NUTRITION: "nutrition",
  PREFERENCE: "preference",
  HOUSEHOLD_SETTINGS: "household settings",
  USER: "you",
};

function ReoptPanel({ suggestion }: { suggestion: ReoptSuggestionDto }) {
  return (
    <AdvisorPanel
      label={`Re-optimisation suggested · trigger: ${REOPT_TRIGGER_LABEL[suggestion.triggerKind]}`}
      headerRight={`${suggestion.affectedSlotIds.length} future slot${
        suggestion.affectedSlotIds.length === 1 ? "" : "s"
      } affected · eaten and cooked meals stay pinned`}
      title={suggestion.summary}
      impact={`raised ${fmtDateTime(suggestion.createdAt)}${
        suggestion.expiresAt
          ? ` · expires ${shortDayLabel(suggestion.expiresAt.slice(0, 10))}`
          : ""
      }`}
      acceptLabel="Accept changes"
      onAccept={() => acceptSuggestion(suggestion.id)}
      onDismiss={() => rejectSuggestion(suggestion.id)}
    >
      <div className="inline-note" style={{ marginTop: 10 }}>
        Affected slots are struck in the grid. Accepting writes a new draft
        generation for your review — this is a two-step confirmation. The
        concrete diff is only returned by the accept response (no preview —
        see spec open question Q2).
      </div>
    </AdvisorPanel>
  );
}

/* ---- history drawer (§3f) ------------------------------------------------------------ */

function HistoryDrawer({
  plans,
  viewedId,
  onView,
}: {
  plans: PlanDto[];
  viewedId: string;
  onView: (id: string) => void;
}) {
  const [revertTarget, setRevertTarget] = useState<PlanDto | null>(null);
  return (
    <div className="history-drawer mp-card" aria-label="Plan history">
      <span className="mp-label">
        History · latest generation first · revert copies a plan forward
      </span>
      <div style={{ marginTop: 10, display: "grid", gap: 0 }}>
        {plans.map((p) => {
          const outcome = planOutcomeLine(p);
          return (
            <div
              key={p.id}
              className={`history-row${p.id === viewedId ? " viewed" : ""}`}
            >
              <button
                type="button"
                className="history-open"
                onClick={() => onView(p.id)}
                title="Open this generation (read-only unless active)"
              >
                <span className="mp-num" style={{ fontSize: 15 }}>
                  gen {p.generation}
                </span>
                <PlanStatusChip status={p.status} />
                <span className="history-meta">
                  {fmtDateTime(p.createdAt)} · {TRIGGER_LABEL[p.triggerKind]}
                  {p.replacesPlanId && (
                    <span className="history-chain"> · ↳ replaces gen {p.generation - 1}</span>
                  )}
                </span>
                <span className="history-flags">
                  {p.qualityWarning && (
                    <span title="quality warnings" style={{ color: "var(--mp-amber)" }}>
                      ⚠
                    </span>
                  )}
                  {p.coldStart && (
                    <span title="cold-start plan" style={{ color: "var(--mp-terra)" }}>
                      ❋
                    </span>
                  )}
                  {!p.aiAugmented && (
                    <span title="AI ranking unavailable" style={{ color: "var(--mp-muted)" }}>
                      ai✕
                    </span>
                  )}
                </span>
              </button>
              <span className="history-outcome">{outcome ?? "awaiting decision"}</span>
              {p.status !== "ACTIVE" && p.status !== "GENERATED" && (
                <button
                  className="btn btn-small"
                  onClick={() => setRevertTarget(p)}
                >
                  Revert to this plan
                </button>
              )}
            </div>
          );
        })}
      </div>
      {revertTarget && (
        <Modal
          label="Revert to plan"
          onClose={() => setRevertTarget(null)}
        >
          <span className="mp-label">
            Revert to generation {revertTarget.generation}
          </span>
          <div style={{ marginTop: 10, fontSize: 14 }}>
            This copies generation {revertTarget.generation} onto a brand-new
            generation. Recipes that now break your constraints are replaced;
            slots that can't be refilled ship empty with a quality warning.
          </div>
          <div className="modal-actions">
            <button className="btn" onClick={() => setRevertTarget(null)}>
              Cancel
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                revertToPlan(revertTarget.id);
                setRevertTarget(null);
              }}
            >
              Revert
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

/* ---- the page ------------------------------------------------------------------------- */

export function Plan() {
  const planner = useStore((s) => s.planner);
  const recipes = useStore((s) => s.recipes);
  // Member id → display name join; a member without a displayName renders
  // as the userId stub (no username join exists — settings.md §8 Q2).
  const members = useStore((s) => s.household.current?.members ?? null);
  const navigate = useNavigate();

  const [weekIdx, setWeekIdx] = useState(KNOWN_WEEKS.indexOf(CURRENT_WEEK_START));
  const [viewPlanId, setViewPlanId] = useState<string | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [warningsOpen, setWarningsOpen] = useState(false);
  const [reasonFor, setReasonFor] = useState<"reject" | "abandon" | null>(null);

  const week = KNOWN_WEEKS[weekIdx];
  const weekPlans = planner.plans
    .filter((p) => p.weekStartDate === week)
    .sort((a, b) => b.generation - a.generation);
  const active = weekPlans.find((p) => p.status === "ACTIVE");
  const viewed =
    (viewPlanId && weekPlans.find((p) => p.id === viewPlanId)) ||
    active ||
    weekPlans.find((p) => p.status === "GENERATED") ||
    weekPlans[0];

  const nameOf = (id: string) => recipeName(recipes, id);
  const memberNames = Object.fromEntries(
    (members ?? []).map((m) => [m.id, m.displayName ?? m.userId]),
  );

  const suggestion = planner.suggestions.find(
    (sg) => sg.weekStartDate === week,
  );
  const affectedSlotIds = new Set(
    viewed && suggestion?.planId === viewed.id ? suggestion.affectedSlotIds : [],
  );
  const reoptOutcome =
    planner.lastReoptOutcome &&
    viewed &&
    planner.lastReoptOutcome.newPlanId === viewed.id
      ? planner.lastReoptOutcome
      : null;

  const changeWeek = (delta: number) => {
    const next = weekIdx + delta;
    if (next < 0 || next >= KNOWN_WEEKS.length) return;
    setWeekIdx(next);
    setViewPlanId(null);
    setWarningsOpen(false);
  };

  /* ---- empty state: no plan for this week (active read 404, §8) ---- */
  if (!viewed) {
    return (
      <div>
        <PageHeader
          title={`Week of ${weekRangeLabel(week)}`}
          meta="No plan for this week yet"
          actions={<WeekNav weekIdx={weekIdx} onStep={changeWeek} />}
        />
        <div className="page-loading" style={{ marginTop: 40 }}>
          No plan for this week yet.
          <div style={{ marginTop: 14 }}>
            <button
              className="btn btn-primary"
              onClick={() => navigate(`/plan/generate?week=${week}`)}
            >
              Generate a plan
            </button>
          </div>
        </div>
      </div>
    );
  }

  /* ---- header machinery (§3a + §5) ---- */
  const weekly = viewed.rollupSummary.weekly;
  const metaLine = [
    `generation ${viewed.generation}`,
    TRIGGER_LABEL[viewed.triggerKind],
    `created ${fmtDateTime(viewed.createdAt)}`,
    planOutcomeLine(viewed),
  ]
    .filter(Boolean)
    .join(" · ");

  const headerActions = (
    <>
      <WeekNav weekIdx={weekIdx} onStep={changeWeek} />
      <button
        className="btn"
        onClick={() => setHistoryOpen((v) => !v)}
        aria-expanded={historyOpen}
      >
        History ({weekPlans.length})
      </button>
      {viewed.status === "GENERATED" && (
        <>
          <button className="btn" onClick={() => setReasonFor("reject")}>
            Reject
          </button>
          <button className="btn btn-primary" onClick={() => acceptPlan(viewed.id)}>
            Accept plan
          </button>
        </>
      )}
      {viewed.status === "ACTIVE" && (
        <>
          <button className="btn" onClick={() => setReasonFor("abandon")}>
            Abandon week
          </button>
          <button
            className="btn"
            onClick={() => navigate(`/plan/generate?week=${week}`)}
          >
            Re-optimise
          </button>
          <button
            className="btn btn-primary"
            onClick={() =>
              navigate(`/plan/generate?week=${KNOWN_WEEKS[KNOWN_WEEKS.length - 1]}`)
            }
          >
            Generate next week
          </button>
        </>
      )}
    </>
  );

  /* ---- stat strip from rollupSummary.weekly (§3b) ---- */
  const stats: StatStripCell[] = [
    {
      label: "Est. cost",
      value: `£${weekly.costEstimateGbp.toFixed(0)}`,
      sub: `${Math.round(weekly.costConfidence * 100)}% confidence${
        weekly.staleIngredientCount > 0
          ? ` · ${weekly.staleIngredientCount} stale prices`
          : ""
      }`,
      warn: weekly.costConfidence < 0.5,
    },
    { label: "Variety", value: `${Math.round(weekly.varietyIndex * 100)}%` },
    {
      label: "Energy",
      value: `${weekly.kcalTotal.toLocaleString("en-GB")}`,
      sub: `kcal week · ${Math.round(weekly.proteinAvgG)}P · ${Math.round(
        weekly.carbsAvgG,
      )}C · ${Math.round(weekly.fatAvgG)}F g/day avg`,
    },
    {
      label: "Batch",
      value: String(weekly.batchCookSessions),
      sub: "cook sessions",
    },
    {
      label: "Warnings",
      value: String(weekly.constraintViolations.length),
      sub: weekly.constraintViolations.length > 0 ? "view in banner" : "none",
      warn: weekly.constraintViolations.length > 0,
    },
  ];

  return (
    <div>
      <PageHeader
        title={`Week of ${weekRangeLabel(viewed.weekStartDate)}`}
        chip={<PlanStatusChip status={viewed.status} />}
        meta={metaLine}
        actions={headerActions}
      />
      <PlanBadges plan={viewed} />

      {viewed.qualityWarning && (
        <button
          type="button"
          className="quality-banner"
          onClick={() => setWarningsOpen((v) => !v)}
          aria-expanded={warningsOpen}
        >
          ⚠ This plan has quality warnings — {warningsOpen ? "hide" : "view"}{" "}
          details
        </button>
      )}
      {warningsOpen && viewed.qualityWarning && (
        <QualityWarningsPanel plan={viewed} />
      )}

      <div style={{ marginTop: 22 }}>
        <StatStrip cells={stats} />
      </div>

      {reoptOutcome && (
        <AdvisorPanel
          label="Changes applied as a new draft plan"
          headerRight="step 2 of 2 — review and accept"
          title={`${reoptOutcome.dto.summary} — generation ${viewed.generation} drafted`}
          impact="the previous plan is superseded; accept to make this active"
          acceptLabel="Accept plan"
          dismissLabel="Reject"
          onAccept={() => acceptPlan(viewed.id)}
          onDismiss={() => rejectPlan(viewed.id)}
        >
          <div style={{ marginTop: 14, display: "grid", gap: 8 }}>
            {reoptOutcome.dto.proposedAssignments.changes.map((c) => (
              <SwapLine
                key={c.slotId}
                from={c.oldRecipeId ? nameOf(c.oldRecipeId) : "—"}
                to={`${nameOf(c.newRecipeId)} · serves ${c.newServings}`}
                note={c.reason ?? undefined}
              />
            ))}
          </div>
        </AdvisorPanel>
      )}

      {suggestion && !reoptOutcome && <ReoptPanel suggestion={suggestion} />}

      {historyOpen && (
        <HistoryDrawer
          plans={weekPlans}
          viewedId={viewed.id}
          onView={(id) => setViewPlanId(id)}
        />
      )}

      <div style={{ marginTop: 30 }}>
        <WeekGrid
          plan={viewed}
          affectedSlotIds={affectedSlotIds}
          nameOf={nameOf}
          memberNames={memberNames}
        />
      </div>

      {reasonFor === "reject" && (
        <ReasonModal
          title="Reject this generated plan"
          cta="Reject plan"
          onClose={() => setReasonFor(null)}
          onConfirm={(reason) => rejectPlan(viewed.id, reason)}
        />
      )}
      {reasonFor === "abandon" && (
        <ReasonModal
          title="Abandon this week's plan"
          cta="Abandon week"
          onClose={() => setReasonFor(null)}
          onConfirm={(reason) => abandonPlan(viewed.id, reason)}
        />
      )}
    </div>
  );
}

function WeekNav({
  weekIdx,
  onStep,
}: {
  weekIdx: number;
  onStep: (delta: number) => void;
}) {
  return (
    <span style={{ display: "flex", gap: 4 }}>
      <button
        className="stepper-btn"
        aria-label="Previous week"
        disabled={weekIdx === 0}
        onClick={() => onStep(-1)}
      >
        ‹
      </button>
      <button
        className="stepper-btn"
        aria-label="Next week"
        disabled={weekIdx === KNOWN_WEEKS.length - 1}
        onClick={() => onStep(1)}
      >
        ›
      </button>
    </span>
  );
}
