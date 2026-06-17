/**
 * Generation flow (/plan/generate) — rebuilt to plan.md §4: feasibility →
 * generate → review. The contract returns ONE composed plan per generate
 * call (Stage C picks server-side; no candidates endpoint — spec §8 Q1), so
 * the review is a single result card; "Regenerate all" (fresh
 * Idempotency-Key) is the only alternative-seeking control.
 */

import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Modal } from "../components/Modal";
import { PageHeader } from "../components/PageHeader";
import { NEXT_WEEK_START } from "../mock/plannerSeed";
import {
  acceptPlan,
  activePlanForWeek,
  openGenerateFlow,
  recipeName,
  regenerateAll,
  rejectPlan,
  requestGeneration,
  setForceRegenerate,
  useStore,
} from "../mock/store";
import type {
  ConflictType,
  FeasibilityCheckResultDto,
  PlanDto,
  ResolutionOptionDto,
} from "../mock/types";
import {
  PlanBadges,
  ScoreBars,
  fmtMinutes,
  shortDayLabel,
  weekRangeLabel,
} from "./plan/shared";
import { LIVE } from "../live/flag";
import { submitGeneration, pollGeneration } from "../live/generate";

/* ---- feasibility gate (§4a) ---------------------------------------------------------- */

const CONFLICT_LABEL: Record<ConflictType, string> = {
  HOUSEHOLD_HARD_COLLISION: "diets collide",
  NUTRITION_VS_BUDGET: "targets vs budget",
  PROVISIONS_BOTTLENECK: "equipment / pantry limit",
  OVER_SPECIFIED_PREFERENCES: "preferences too narrow",
};

/** No apply endpoint exists for relaxations — deep link to the owning page
 *  (spec §8 Q4). */
function resolutionLink(key: string): { label: string; to: string } {
  if (key.includes("protein") || key.includes("floor"))
    return { label: "Open nutrition targets", to: "/nutrition" };
  if (key.includes("budget")) return { label: "Open pantry budget", to: "/pantry" };
  if (key.includes("slot")) return { label: "Open slot configuration", to: "/settings" };
  return { label: "Open preferences", to: "/preferences" };
}

function FeasibilityGate({
  feasibility,
  slotLabelOf,
}: {
  feasibility: FeasibilityCheckResultDto;
  slotLabelOf: (id: string) => string | null;
}) {
  const navigate = useNavigate();
  if (feasibility.feasible) {
    return (
      <div className="feasibility-band">
        <span style={{ color: "var(--mp-olive)", fontWeight: 700, fontSize: 14 }}>
          ✓
        </span>
        <span>
          Constraints look workable — recipes are available for every slot.
        </span>
      </div>
    );
  }
  return (
    <div>
      {feasibility.conflicts.map((c, i) => {
        const labels = c.affectedSlotIds
          .map(slotLabelOf)
          .filter((x): x is string => x !== null);
        return (
          <div key={i} className="conflict-card mp-card">
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <span className="mp-label" style={{ color: "var(--mp-amber)" }}>
                ⚠ Constraint conflict
              </span>
              <span className="tint-chip muted-chip">{CONFLICT_LABEL[c.type]}</span>
            </div>
            <div style={{ marginTop: 8, fontSize: 14.5 }}>{c.description}</div>
            <div style={{ marginTop: 8, display: "flex", gap: 6, flexWrap: "wrap" }}>
              {labels.length === c.affectedSlotIds.length ? (
                labels.map((l) => (
                  <span key={l} className="tint-chip muted-chip">
                    {l}
                  </span>
                ))
              ) : (
                <span className="tint-chip muted-chip">
                  {c.affectedSlotIds.length} slots affected
                </span>
              )}
            </div>
          </div>
        );
      })}
      {feasibility.resolutions.length > 0 && (
        <div className="mp-card resolution-card">
          <span className="mp-label">
            Ranked relaxations · best first · informational only — each opens
            its owning page (no apply endpoint; spec open question Q4)
          </span>
          <div style={{ marginTop: 10, display: "grid", gap: 8 }}>
            {feasibility.resolutions.map((r: ResolutionOptionDto) => {
              const link = resolutionLink(r.key);
              return (
                <div key={r.key} className="resolution-row">
                  <span style={{ flex: 1, minWidth: 0 }}>{r.description}</span>
                  <span className="tint-chip olive">+{r.slotsRecovered} slots</span>
                  <span className="tint-chip olive">
                    +{r.scoreRecovered.toFixed(2)} score
                  </span>
                  <button
                    className="btn btn-small"
                    onClick={() => navigate(link.to)}
                  >
                    {link.label}
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

/* ---- review card (§4c — single result; candidate grid is contract-divergent) -------- */

function ReviewCard({
  plan,
  replayed,
  nameOf,
  onReasonReject,
}: {
  plan: PlanDto;
  replayed: boolean;
  nameOf: (id: string) => string;
  onReasonReject: () => void;
}) {
  const navigate = useNavigate();
  const weekly = plan.rollupSummary.weekly;
  const daily = plan.rollupSummary.daily;
  const violatedDays = daily.filter((d) => d.violations.length > 0);
  const prepMin = daily.reduce((acc, d) => acc + d.totalTimeMin, 0);
  const dinners = plan.days.map(
    (d) => d.slots.find((sl) => sl.kind === "DINNER")?.scheduledRecipe ?? null,
  );

  return (
    <div className="mp-card review-card">
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
        <span className="mp-label">
          Proposed plan · generation {plan.generation}
        </span>
        <span className={`tint-chip ${replayed ? "muted-chip" : "olive"}`}>
          {replayed ? "cached replay (200)" : "new generation (201)"}
        </span>
        {plan.qualityWarning && (
          <span className="warn-pill">
            quality warnings ({weekly.constraintViolations.length})
          </span>
        )}
      </div>

      <div className="review-grid">
        <div>
          <div className="candidate-fit">
            <span className="mp-num" style={{ fontSize: 40, color: "var(--mp-terra)" }}>
              {Math.round(plan.scoreBreakdown.composite * 100)}
            </span>
            <span className="candidate-fit-max">/ 100</span>
          </div>
          <div className="candidate-fit-sub">composite fit</div>
          <div style={{ marginTop: 14 }}>
            <ScoreBars plan={plan} />
          </div>
        </div>

        <div className="review-lines">
          <div>
            <span className="candidate-key">Nutrition · </span>
            {violatedDays.length === 0 ? (
              <span style={{ color: "var(--mp-olive)", fontWeight: 600 }}>
                on target all days
              </span>
            ) : (
              <span style={{ color: "var(--mp-amber)", fontWeight: 600 }}>
                violations on{" "}
                {violatedDays.map((d) => shortDayLabel(d.date)).join(", ")}
              </span>
            )}
          </div>
          <div>
            <span className="candidate-key">Cost · </span>
            <span style={{ fontWeight: 600 }}>
              £{weekly.costEstimateGbp.toFixed(0)} ·{" "}
              {Math.round(weekly.costConfidence * 100)}% confidence
            </span>
            {weekly.staleIngredientCount > 0 && (
              <span className="candidate-conf">
                {weekly.staleIngredientCount} stale prices
              </span>
            )}
          </div>
          <div>
            <span className="candidate-key">Variety · </span>
            <span style={{ fontWeight: 600 }}>
              {Math.round(weekly.varietyIndex * 100)}%
            </span>
          </div>
          <div>
            <span className="candidate-key">Prep load · </span>
            <span style={{ fontWeight: 600 }}>{fmtMinutes(prepMin)}</span>
          </div>
          <div>
            <span className="candidate-key">Batch · </span>
            <span style={{ fontWeight: 600 }}>
              {weekly.batchCookSessions} cook session
              {weekly.batchCookSessions === 1 ? "" : "s"}
            </span>
          </div>
          <PlanBadges plan={plan} />
          {plan.qualityWarning && (
            <div className="inline-note">
              {weekly.constraintViolations.join(" · ")}
            </div>
          )}
        </div>
      </div>

      <div className="reasoning-lineup">
        <span className="mp-label">Dinner line-up · Mon → Sun</span>
        <div className="lineup-chips">
          {dinners.map((sr, i) => (
            <span
              key={i}
              className="lineup-chip"
              style={sr === null ? { color: "var(--mp-amber)" } : undefined}
            >
              {sr ? nameOf(sr.recipeId) : "— unfilled"}
            </span>
          ))}
        </div>
      </div>

      <div className="inline-note" style={{ marginTop: 14 }}>
        Single result — the backend auto-picks from its internal top-5 via
        Stage C (candidate pick — see spec open question Q1). Stage-C
        reasoning is decision-log/admin only, so there is no "why this plan"
        card (spec Q5).
      </div>

      <div className="reasoning-actions">
        <button className="btn" onClick={regenerateAll}>
          Regenerate all
        </button>
        <button className="btn" onClick={onReasonReject}>
          Reject
        </button>
        <button
          className="btn btn-primary"
          onClick={() => {
            acceptPlan(plan.id);
            navigate("/plan");
          }}
        >
          Accept plan
        </button>
      </div>
    </div>
  );
}

/* ---- the page -------------------------------------------------------------------------- */

export function PlanGenerate() {
  const [search] = useSearchParams();
  const week = search.get("week") ?? NEXT_WEEK_START;
  const planner = useStore((s) => s.planner);
  const recipes = useStore((s) => s.recipes);
  const navigate = useNavigate();
  const [rejecting, setRejecting] = useState(false);
  const [rejectReason, setRejectReason] = useState("");

  // Live (real-backend) generation runs ASYNC: submit returns instantly, then we poll the job and
  // show a processing state until it is terminal — so the user is never blocked on the multi-second
  // Stage-A→D run. In mock mode this branch is dormant and the in-memory store flow drives the UI.
  const liveHousehold = useStore((s) => s.household.current);
  const [livePhase, setLivePhase] = useState<"idle" | "generating" | "failed">(
    "idle",
  );
  const [liveError, setLiveError] = useState<string | null>(null);

  async function runLiveGeneration() {
    const hid = liveHousehold?.id;
    if (!hid) {
      setLiveError("No household loaded yet — try again in a moment.");
      setLivePhase("failed");
      return;
    }
    setLivePhase("generating");
    setLiveError(null);
    try {
      const key = `gen-${week}-${Date.now()}`;
      const job = await submitGeneration(
        hid,
        week,
        planner.generation.forceRegenerateIfActive,
        key,
      );
      const done = await pollGeneration(job.jobId, () => {});
      if (done.status === "COMPLETED") {
        // The live Plan page reads the freshly GENERATED plan on mount.
        navigate("/plan");
      } else {
        setLiveError(
          done.errorCode
            ? `Generation failed (${done.errorCode}). Try again.`
            : "Generation failed. Try again.",
        );
        setLivePhase("failed");
      }
    } catch (e) {
      setLiveError(
        e instanceof Error ? e.message : "Generation failed. Try again.",
      );
      setLivePhase("failed");
    }
  }

  // Entering the stepper targets a week; feasibility (#5) is read before
  // the generate button enables.
  useEffect(() => {
    openGenerateFlow(week);
  }, [week]);

  const g = planner.generation;
  const feasibility = planner.feasibility[week] ?? {
    feasible: true,
    conflicts: [],
    resolutions: [],
  };
  const activeForWeek = useStore((s) => activePlanForWeek(s, week));
  const pendingGenerated = planner.plans.find(
    (p) => p.weekStartDate === week && p.status === "GENERATED",
  );
  const resultPlan =
    g.status === "review" && g.resultPlanId
      ? planner.plans.find((p) => p.id === g.resultPlanId)
      : undefined;

  const nameOf = (id: string) => recipeName(recipes, id);
  const slotLabelOf = (slotId: string): string | null => {
    for (const p of planner.plans) {
      for (const d of p.days) {
        const sl = d.slots.find((x) => x.id === slotId);
        if (sl) return `${shortDayLabel(d.date)} ${sl.label.toLowerCase()}`;
      }
    }
    return null;
  };

  return (
    <div>
      <PageHeader
        title="Generate plan"
        meta={`Week of ${weekRangeLabel(week)} · household of 4 · weekStartDate must be a Monday (≤8 weeks past, ≤4 weeks ahead)`}
        actions={
          <button className="btn" onClick={() => navigate("/plan")}>
            Back to plan
          </button>
        }
      />

      <FeasibilityGate feasibility={feasibility} slotLabelOf={slotLabelOf} />

      {g.status === "idle" && pendingGenerated && (
        <div className="inline-note" style={{ marginTop: 14 }}>
          Generation {pendingGenerated.generation} for this week is already
          awaiting approval —{" "}
          <button
            className="btn btn-small"
            onClick={() => navigate("/plan")}
            style={{ marginLeft: 6 }}
          >
            review it on the plan page
          </button>{" "}
          or generate a replacement below.
        </div>
      )}

      {activeForWeek && (
        <label className="consent-row">
          <input
            type="checkbox"
            checked={g.forceRegenerateIfActive}
            onChange={(e) => setForceRegenerate(e.target.checked)}
          />
          <span>
            Replace the current active plan for this week
            <span className="inline-note" style={{ display: "block" }}>
              forceRegenerateIfActive — an active plan already exists for{" "}
              {weekRangeLabel(week)}; pinned (eaten / cooking / cooked) meals
              never regenerate
            </span>
          </span>
        </label>
      )}

      {(LIVE ? livePhase !== "generating" : g.status !== "review") && (
        <div style={{ marginTop: 18, display: "flex", gap: 10 }}>
          {feasibility.feasible ? (
            <button
              className="btn btn-primary"
              disabled={LIVE ? livePhase === "generating" : g.status === "generating"}
              onClick={LIVE ? runLiveGeneration : requestGeneration}
            >
              Generate
            </button>
          ) : (
            <button
              className="btn"
              disabled={LIVE ? livePhase === "generating" : g.status === "generating"}
              onClick={LIVE ? runLiveGeneration : requestGeneration}
              title="Proceed without resolving — the plan ships flagged with quality warnings (no silent relaxation)"
            >
              Generate anyway
            </button>
          )}
        </div>
      )}

      {LIVE && livePhase === "failed" && liveError && (
        <div
          className="inline-note"
          style={{ marginTop: 14, color: "var(--mp-amber)" }}
        >
          {liveError}
        </div>
      )}

      {(LIVE ? livePhase === "generating" : g.status === "generating") && (
        <div>
          <div className="candidate-skeleton" style={{ marginTop: 22 }} />
          <div className="gen-wait mp-card">
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span className="advisor-dot" />
              <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
                Generating
              </span>
            </div>
            <div style={{ marginTop: 8 }}>
              <span className="mp-serif" style={{ fontSize: 21 }}>
                Composing one plan through the week…
              </span>
            </div>
            <div className="gen-wait-sub">
              {LIVE
                ? "Running Stage A→D on a background worker — this can take up to a"
                  + " minute. You can leave this page; we'll land on your new plan when"
                  + " it's ready."
                : "One blocking call, Stage A→D server-side — typically under 20"
                  + " seconds; no progress endpoint in v1."}
            </div>
          </div>
        </div>
      )}

      {!LIVE && g.status === "review" && resultPlan && (
        <ReviewCard
          plan={resultPlan}
          replayed={g.replayed}
          nameOf={nameOf}
          onReasonReject={() => setRejecting(true)}
        />
      )}

      {rejecting && resultPlan && (
        <Modal label="Reject generated plan" onClose={() => setRejecting(false)}>
          <span className="mp-label">Reject this generated plan</span>
          <div style={{ marginTop: 12 }}>
            <input
              type="text"
              className="text-input"
              style={{ width: "100%" }}
              maxLength={255}
              placeholder="Reason (optional, ≤255)"
              value={rejectReason}
              autoFocus
              onChange={(e) => setRejectReason(e.target.value)}
            />
          </div>
          <div className="modal-actions">
            <button className="btn" onClick={() => setRejecting(false)}>
              Cancel
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                rejectPlan(resultPlan.id, rejectReason);
                setRejecting(false);
                navigate("/plan");
              }}
            >
              Reject plan
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
