/**
 * Today page — rebuilt against the contract-complete page spec
 * (design/frontend/pages/today.md). A cross-module composite: each card
 * shows the glanceable subset and deep-links to the owning page. The meal
 * timeline reflects BOTH machines per row — planner slot state + nutrition
 * intake status — and the buttons perform the specified client-side
 * dual-writes (planner first, intake second; spec §3b/§8 Q1).
 */

import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { AdvisorCard } from "../components/AdvisorCard";
import { MealRow } from "../components/MealRow";
import type { MealRowMeal } from "../components/MealRow";
import {
  NotificationGlyph,
  resolveActionTarget,
} from "../components/NotificationGlyph";
import { StatBand } from "../components/StatBand";
import type { NutritionStat } from "../components/StatBand";
import { QUICK_SNACKS } from "../mock/nutritionSeed";
// Date anchors are live-aware: real clock in live mode, fixed fixtures in mock
// mode (see src/live/dates.ts) — so the page lines up with the backend's plan.
import { CURRENT_WEEK_START, MOCK_TODAY_ISO, WEEK_DATES } from "../live/dates";
import {
  acceptPendingChange,
  activePlanForWeek,
  addSnack,
  changeSlotState,
  computeDailyAggregate,
  confirmSlot,
  macroWarn,
  pushToast,
  recipeName,
  skipSlot,
  useStore,
} from "../mock/store";
import type {
  MacroTargetDto,
  MealSlot,
  MealSlotDto,
  SlotState,
} from "../mock/types";
import { prettyDate } from "./nutrition/shared";
import { leadTime } from "./plan/shared";

/* ---- §3b: the two machines, one row ------------------------------------------------ */

/** Planner kind ↔ nutrition mealSlot join (enum names differ for snacks;
 *  CUSTOM has no intake row — spec §8 Q3). */
function intakeSlotFor(kind: MealSlotDto["kind"]): MealSlot | null {
  if (kind === "BREAKFAST" || kind === "LUNCH" || kind === "DINNER") return kind;
  return null; // SNACK → day-level SNACKS bucket (not slot-shaped); CUSTOM → none
}

const ROW_ACTIONS: Partial<
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

const DIMENSION_LABEL: Record<string, string> = {
  SALT_LEVEL: "salt level",
  PROTEIN: "protein",
  METHOD_SIMPLIFICATION: "method",
  PORTION_SIZE: "portion size",
  FLAVOUR_BALANCE: "flavour balance",
  ACID_BALANCE: "acid balance",
  TEXTURE: "texture",
  COOKING_TIME: "cooking time",
  SUBSTITUTION_PROMOTION: "substitution",
  GENERAL: "general",
};

function greeting(): string {
  const h = new Date().getHours();
  return h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

export function Today() {
  const activePlan = useStore((s) => activePlanForWeek(s, CURRENT_WEEK_START));
  const recipes = useStore((s) => s.recipes);
  const intakeDay = useStore((s) => s.nutrition.intakeDays[MOCK_TODAY_ISO]);
  const targets = useStore((s) => s.targets);
  const notificationRows = useStore((s) => s.notifications.rows);
  // Budget is a provisions concern — the pantry BudgetDto is the record.
  const weeklyBudget = useStore((s) => s.pantry.budget?.weeklyTarget ?? 55);
  const pendingChange = useStore((s) => s.adaptation.pendingChanges[0]);
  const userName = useStore(
    (s) =>
      s.household.current?.members.find((m) => m.role === "primary")
        ?.displayName ??
      s.session.user?.username ??
      "there",
  );
  const navigate = useNavigate();
  const [snackOpen, setSnackOpen] = useState(false);

  const todayDay = activePlan?.days.find((d) => d.date === MOCK_TODAY_ISO);
  const dayNumber = WEEK_DATES.indexOf(MOCK_TODAY_ISO) + 1;

  // First upcoming slot with a serve time gets the lead-time hint (§3b).
  const hintSlotId = todayDay?.slots.find(
    (sl) => sl.state === "PLANNED" && sl.mealTime != null,
  )?.id;

  const onSlotAction = (slot: MealSlotDto, next: SlotState) => {
    if (!activePlan) return;
    // Dual-write order: planner first (authoritative lifecycle), intake
    // second; intake calls are skipped when already decided (422 no-op).
    const ok = changeSlotState(activePlan.id, slot.id, next);
    if (!ok) return;
    const mealSlot = intakeSlotFor(slot.kind);
    if (!mealSlot) return;
    if (next === "EATEN") confirmSlot(MOCK_TODAY_ISO, mealSlot);
    if (next === "SKIPPED") skipSlot(MOCK_TODAY_ISO, mealSlot);
  };

  const rows = (todayDay?.slots ?? [])
    .slice()
    .sort((a, b) => a.slotIndex - b.slotIndex)
    .map((slot) => {
      const mealSlot = intakeSlotFor(slot.kind);
      const intake = mealSlot
        ? intakeDay?.slots.find((sl) => sl.mealSlot === mealSlot)
        : undefined;
      const metaParts = [
        slot.shared ? `Shared · ${slot.eaters.length} eating` : "Just you",
      ];
      if (slot.scheduledRecipe) {
        metaParts.push(`serves ${slot.scheduledRecipe.servings}`);
      }
      if (intake?.planned.calories != null) {
        metaParts.push(`${intake.planned.calories} kcal planned`);
      }
      if (!mealSlot) {
        metaParts.push("planner only — no intake row (spec Q3)");
      }
      const meal: MealRowMeal = {
        time: slot.mealTime ?? null,
        slot: slot.label,
        name: slot.scheduledRecipe
          ? recipeName(recipes, slot.scheduledRecipe.recipeId)
          : `— ${slot.label.toLowerCase()}`,
        meta: metaParts.join(" · "),
        state: slot.state,
        batch: Boolean(slot.scheduledRecipe?.batchCookSessionId),
        logged: intake != null && intake.actual.status !== "PENDING",
        hint:
          slot.id === hintSlotId && slot.mealTime
            ? `start cooking ${leadTime(slot.mealTime, slot.timeBudgetMin)}`
            : undefined,
      };
      const actions = ROW_ACTIONS[slot.state];
      return (
        <MealRow
          key={slot.id}
          meal={meal}
          actions={
            actions && (
              <span style={{ display: "flex", gap: 8 }}>
                {actions.map((a) => (
                  <button
                    key={a.next}
                    className={`btn${a.primary ? " btn-primary" : ""}`}
                    onClick={() => onSlotAction(slot, a.next)}
                  >
                    {a.label}
                  </button>
                ))}
              </span>
            )
          }
        />
      );
    });

  /* ---- §3c stat band (4 cells; deep-links to /nutrition) ---- */
  const agg = computeDailyAggregate(intakeDay, targets);
  const macroStat = (
    label: string,
    actualG: number,
    t: MacroTargetDto,
  ): NutritionStat => ({
    label,
    value: actualG,
    target: t.targetG ?? 0,
    display: Math.round(actualG).toLocaleString("en-GB"),
    targetDisplay: `${(t.targetG ?? 0).toLocaleString("en-GB")} g`,
    behind: macroWarn(t.direction, actualG, t.targetG ?? 0) || undefined,
  });
  const nutritionStats: NutritionStat[] = [
    {
      label: "Calories",
      value: agg.caloriesActualSoFar,
      target: targets.calories.dailyTarget,
      display: agg.caloriesActualSoFar.toLocaleString("en-GB"),
      targetDisplay: targets.calories.dailyTarget.toLocaleString("en-GB"),
      behind:
        macroWarn(
          targets.calories.direction,
          agg.caloriesActualSoFar,
          targets.calories.dailyTarget,
        ) || undefined,
    },
    macroStat("Protein", agg.protein.actualSoFarG, targets.protein),
    macroStat("Carbs", agg.carbs.actualSoFarG, targets.carbs),
    macroStat("Fat", agg.fat.actualSoFarG, targets.fat),
  ];

  /* ---- §3d needs attention (top-3 unread; read-only here) ---- */
  const unread = notificationRows.filter((n) => n.status === "UNREAD");
  const urgentCount = unread.filter((n) => n.severity === "URGENT").length;

  /* ---- §3f teaser expiry ---- */
  const expiresInDays = pendingChange
    ? Math.max(
        0,
        Math.round(
          (Date.parse(pendingChange.expiresAt) -
            Date.parse(`${MOCK_TODAY_ISO}T18:00:00Z`)) /
            86400000,
        ),
      )
    : 0;

  return (
    <div>
      <header className="today-header">
        <div>
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            {prettyDate(MOCK_TODAY_ISO)}
            {activePlan && ` · week plan day ${dayNumber} of 7`}
          </span>
          <div>
            <span className="mp-serif today-greeting">
              {greeting()}, {userName}
            </span>
          </div>
        </div>
        {activePlan ? (
          <span className="mp-chip">Plan active</span>
        ) : (
          <span style={{ display: "flex", gap: 10, alignItems: "center" }}>
            <span className="mp-chip muted">No plan this week</span>
            <button
              className="btn btn-primary"
              onClick={() => navigate(`/plan/generate?week=${CURRENT_WEEK_START}`)}
            >
              Generate
            </button>
          </span>
        )}
      </header>

      <section aria-label="Meal timeline">
        {activePlan && todayDay ? (
          rows
        ) : (
          <div className="page-loading">
            No plan this week — generate one to plan and log your meals.
            <div style={{ marginTop: 14 }}>
              <button
                className="btn btn-primary"
                onClick={() =>
                  navigate(`/plan/generate?week=${CURRENT_WEEK_START}`)
                }
              >
                Generate a plan
              </button>
            </div>
          </div>
        )}
      </section>

      {/* Whole band deep-links to /nutrition (remaining lines, micros, week
          strip live there — §3c). */}
      <div
        role="link"
        tabIndex={0}
        style={{ cursor: "pointer" }}
        title="Open nutrition"
        onClick={() => navigate("/nutrition")}
        onKeyDown={(e) => {
          if (e.key === "Enter") navigate("/nutrition");
        }}
      >
        <StatBand stats={nutritionStats} />
      </div>

      <div className="today-lower">
        <section aria-label="Needs attention">
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "baseline",
            }}
          >
            <span className="mp-label">
              Needs attention{" "}
              <span className="attention-count">
                {unread.length}
                {urgentCount > 0 && (
                  <span style={{ color: "var(--mp-red)" }}> · {urgentCount} urgent</span>
                )}
              </span>
            </span>
            <button
              className="btn btn-small"
              onClick={() => navigate("/notifications")}
            >
              View all
            </button>
          </div>
          <div className="attention-list">
            {unread.length === 0 && (
              <div className="inline-note">Nothing needs you right now.</div>
            )}
            {unread.slice(0, 3).map((n) => (
              <button
                key={n.id}
                type="button"
                className="attention-item attention-link"
                onClick={() =>
                  navigate(resolveActionTarget(n.actionTargetUri) ?? "/notifications")
                }
              >
                <NotificationGlyph kind={n.kind} />
                <span>{n.title}</span>
              </button>
            ))}
          </div>
          {snackOpen ? (
            <div className="snack-chips">
              {QUICK_SNACKS.map((snack) => (
                <button
                  key={snack.label}
                  className="filter-chip"
                  onClick={() => {
                    addSnack(MOCK_TODAY_ISO, snack.req);
                    pushToast(`Snack logged — ${snack.req.freeText.toLowerCase()}`);
                    setSnackOpen(false);
                  }}
                >
                  {snack.label}
                </button>
              ))}
              <button className="filter-chip" onClick={() => setSnackOpen(false)}>
                Cancel
              </button>
            </div>
          ) : (
            <button
              className="btn"
              style={{ marginTop: 18 }}
              onClick={() => setSnackOpen(true)}
            >
              + Log a snack
            </button>
          )}
        </section>

        <section aria-label="Week budget">
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "baseline",
            }}
          >
            <span className="mp-label">Week budget</span>
            <button className="btn btn-small" onClick={() => navigate("/pantry")}>
              Open pantry
            </button>
          </div>
          <div style={{ marginTop: 10 }}>
            <span className="mp-num" style={{ fontSize: 26 }}>
              £{weeklyBudget}
            </span>
            <span style={{ fontSize: 13, color: "var(--mp-muted)" }}>
              {" "}
              weekly target
            </span>
          </div>
          <div className="budget-note">soft ceiling +£5</div>
          <div className="inline-note" style={{ marginTop: 8 }}>
            Spend-so-far arrives with order history (spendTracking is null in
            v1 — spec Q5); target only for now.
          </div>
        </section>
      </div>

      {pendingChange && (
        <AdvisorCard
          label="Suggestion · from your feedback"
          title={
            pendingChange.reasoningPreview ??
            `${DIMENSION_LABEL[pendingChange.changeDimension] ?? pendingChange.changeDimension} change suggested`
          }
          sub={`${recipeName(recipes, pendingChange.recipeId)} · confidence ${pendingChange.confidence.toFixed(
            2,
          )} · expires in ${expiresInDays} day${expiresInDays === 1 ? "" : "s"}`}
          actions={
            <>
              <button
                className="btn"
                onClick={() => navigate(`/recipes/${pendingChange.recipeId}`)}
              >
                Review
              </button>
              <button
                className="btn btn-primary"
                title="Accept fetches the latest detail first — the list item carries no expectedOptimisticVersion (spec Q6)"
                onClick={() => {
                  acceptPendingChange(pendingChange.id);
                  pushToast("Suggestion accepted — new recipe version created");
                }}
              >
                Accept
              </button>
            </>
          }
        >
          <div style={{ marginTop: 6 }}>
            <span className="tint-chip terra">
              {DIMENSION_LABEL[pendingChange.changeDimension] ??
                pendingChange.changeDimension.toLowerCase()}
            </span>
          </div>
        </AdvisorCard>
      )}
    </div>
  );
}
