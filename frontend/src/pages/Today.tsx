import { useState } from "react";
import { useNavigate } from "react-router-dom";
import type { NutritionStat } from "../api";
import { AdvisorCard } from "../components/AdvisorCard";
import { MealRow } from "../components/MealRow";
import type { MealRowMeal } from "../components/MealRow";
import { SegmentBar } from "../components/SegmentBar";
import { StatBand } from "../components/StatBand";
import {
  acceptTodaySuggestion,
  logSnack,
  setSlotState,
  useStore,
} from "../mock/store";
import type { MealSlotKey, SlotState } from "../mock/types";

const SLOT_KEYS: MealSlotKey[] = ["breakfast", "lunch", "dinner"];

/** Next lifecycle step + its button label; eaten slots are pinned. */
const NEXT_ACTION: Partial<
  Record<SlotState, { label: string; next: SlotState }>
> = {
  planned: { label: "Start cooking", next: "cooking" },
  cooking: { label: "Mark cooked", next: "cooked" },
  cooked: { label: "Mark eaten", next: "eaten" },
};

const SNACKS: Array<{ name: string; kcal: number }> = [
  { name: "Banana", kcal: 105 },
  { name: "Greek yoghurt", kcal: 150 },
  { name: "Protein bar", kcal: 210 },
  { name: "Handful of nuts", kcal: 180 },
];

export function Today() {
  const today = useStore((s) => s.today);
  const todayRow = useStore((s) => s.plan.days.find((d) => d.today));
  const budget = useStore((s) => s.pantry.budget);
  const navigate = useNavigate();
  const [snackOpen, setSnackOpen] = useState(false);

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

  const nutritionStats: NutritionStat[] = today.nutrition.map((n) => ({
    label: n.label,
    value: n.value,
    target: n.target,
    display: n.value.toLocaleString("en-GB"),
    targetDisplay: `${n.target.toLocaleString("en-GB")}${n.unit}`,
    behind: n.behind,
  }));

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
              {SNACKS.map((snack) => (
                <button
                  key={snack.name}
                  className="filter-chip"
                  onClick={() => {
                    logSnack(snack.name, snack.kcal);
                    setSnackOpen(false);
                  }}
                >
                  {snack.name} · {snack.kcal} kcal
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
