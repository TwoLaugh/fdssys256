import { useState } from "react";
import { useNavigate } from "react-router-dom";
import type { NutritionStat } from "../api";
import { AdvisorCard } from "../components/AdvisorCard";
import { MealRow } from "../components/MealRow";
import type { MealRowMeal } from "../components/MealRow";
import { SegmentBar } from "../components/SegmentBar";
import { StatBand } from "../components/StatBand";
import { MOCK_TODAY_ISO, QUICK_SNACKS } from "../mock/nutritionSeed";
import {
  acceptTodaySuggestion,
  addSnack,
  computeDailyAggregate,
  macroWarn,
  setSlotState,
  useStore,
} from "../mock/store";
import type {
  MacroTargetDto,
  MealSlotKey,
  SlotState,
} from "../mock/types";

const SLOT_KEYS: MealSlotKey[] = ["breakfast", "lunch", "dinner"];

/** Next lifecycle step + its button label; eaten slots are pinned. */
const NEXT_ACTION: Partial<
  Record<SlotState, { label: string; next: SlotState }>
> = {
  planned: { label: "Start cooking", next: "cooking" },
  cooking: { label: "Mark cooked", next: "cooked" },
  cooked: { label: "Mark eaten", next: "eaten" },
};

export function Today() {
  const today = useStore((s) => s.today);
  const todayRow = useStore((s) => s.plan.days.find((d) => d.today));
  const budget = useStore((s) => s.pantry.budget);
  const intakeDay = useStore((s) => s.nutrition.intakeDays[MOCK_TODAY_ISO]);
  const targets = useStore((s) => s.targets);
  const navigate = useNavigate();
  const [snackOpen, setSnackOpen] = useState(false);

  // Stat band reads the computed daily aggregate (same numbers as the
  // Nutrition page); four cells here, six on /nutrition per the page spec.
  const agg = computeDailyAggregate(intakeDay, targets);

  const meals: Array<{ slot: MealSlotKey; meal: MealRowMeal }> = todayRow
    ? SLOT_KEYS.map((slot) => {
        const planSlot = todayRow.slots[slot];
        const meta = today.slotMeta[slot];
        return {
          slot,
          meal: {
            time: meta.time,
            slot,
            name: planSlot.name,
            meta: meta.meta,
            status: planSlot.state,
            batch: planSlot.batch,
            action: NEXT_ACTION[planSlot.state]?.label,
            alert: planSlot.state === "eaten" ? undefined : meta.alert,
          },
        };
      })
    : [];

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

  const budgetPct = budget.spent / budget.total;

  return (
    <div>
      <header className="today-header">
        <div>
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            {today.dateLabel} · {today.progressLabel}
          </span>
          <div>
            <span className="mp-serif today-greeting">{today.greeting}</span>
          </div>
        </div>
        <span className="mp-chip">Plan active</span>
      </header>

      <section aria-label="Meal timeline">
        {meals.map(({ slot, meal }) => (
          <MealRow
            key={slot}
            meal={meal}
            onAction={() => {
              const next = NEXT_ACTION[meal.status]?.next;
              if (next && todayRow) setSlotState(todayRow.day, slot, next);
            }}
          />
        ))}
      </section>

      <StatBand stats={nutritionStats} />

      <div className="today-lower">
        <section aria-label="Needs attention">
          <span className="mp-label">Needs attention</span>
          <div className="attention-list">
            {today.attention.map((item, i) => (
              <div key={item.text} className={`attention-item ${item.kind}`}>
                <span className="mp-num">{String(i + 1).padStart(2, "0")}</span>
                <span>{item.text}</span>
              </div>
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
                    setSnackOpen(false);
                  }}
                >
                  {snack.label}
                </button>
              ))}
              <button
                className="filter-chip"
                onClick={() => setSnackOpen(false)}
              >
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
            <span>
              <span className="mp-num" style={{ fontSize: 20 }}>
                £{budget.spent.toFixed(2)}
              </span>
              <span style={{ fontSize: 13, color: "var(--mp-muted)" }}>
                {" "}
                of £{budget.total}
              </span>
            </span>
          </div>
          <div style={{ marginTop: 10 }}>
            <SegmentBar pct={budgetPct} width={250} />
          </div>
          <div className="budget-note">{budget.note}</div>
        </section>
      </div>

      {today.suggestion && (
        <AdvisorCard
          label={today.suggestion.label}
          title={today.suggestion.title}
          sub={today.suggestion.sub}
          actions={
            <>
              <button
                className="btn"
                onClick={() => navigate(`/recipes/${today.suggestion?.recipeId}`)}
              >
                Review
              </button>
              <button className="btn btn-primary" onClick={acceptTodaySuggestion}>
                Accept
              </button>
            </>
          }
        />
      )}
    </div>
  );
}
