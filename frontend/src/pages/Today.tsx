import { useEffect, useState } from "react";
import { todayApi } from "../api";
import type {
  AdvisorSuggestion,
  NotificationsSummary,
  NutritionStat,
  PlanToday,
  WeekBudget,
} from "../api";
import { AdvisorCard } from "../components/AdvisorCard";
import { MealRow } from "../components/MealRow";
import { SegmentBar } from "../components/SegmentBar";
import { StatBand } from "../components/StatBand";

interface TodayState {
  plan: PlanToday;
  nutrition: NutritionStat[];
  notifications: NotificationsSummary;
  budget: WeekBudget;
  suggestion: AdvisorSuggestion | null;
}

export function Today() {
  const [state, setState] = useState<TodayState | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      todayApi.getActivePlanToday(),
      todayApi.getNutritionToday(),
      todayApi.getNotificationsSummary(),
      todayApi.getWeekBudget(),
      todayApi.getTopPendingChange(),
    ])
      .then(([plan, nutrition, notifications, budget, suggestion]) => {
        if (!cancelled) {
          setState({ plan, nutrition, notifications, budget, suggestion });
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : "Failed to load today");
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return <div className="page-loading">Couldn't load today: {error}</div>;
  }
  if (!state) {
    return <div className="page-loading">Setting the table…</div>;
  }

  const { plan, nutrition, notifications, budget, suggestion } = state;

  return (
    <div>
      <header className="today-header">
        <div>
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            {plan.dateLabel} · {plan.progressLabel}
          </span>
          <div>
            <span className="mp-serif today-greeting">{plan.greeting}</span>
          </div>
        </div>
        {plan.planActive && <span className="mp-chip">Plan active</span>}
      </header>

      <section aria-label="Meal timeline">
        {plan.meals.map((meal) => (
          <MealRow key={`${meal.time}-${meal.slot}`} meal={meal} />
        ))}
      </section>

      <StatBand stats={nutrition} />

      <div className="today-lower">
        <section aria-label="Needs attention">
          <span className="mp-label">Needs attention</span>
          <div className="attention-list">
            {notifications.attention.map((item, i) => (
              <div key={i} className={`attention-item ${item.kind}`}>
                <span className="mp-num">
                  {String(i + 1).padStart(2, "0")}
                </span>
                <span>{item.text}</span>
              </div>
            ))}
          </div>
          <button className="btn" style={{ marginTop: 18 }}>
            + Log a snack
          </button>
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
                {budget.spentDisplay}
              </span>
              <span style={{ fontSize: 13, color: "var(--mp-muted)" }}>
                {" "}
                of {budget.totalDisplay}
              </span>
            </span>
          </div>
          <div style={{ marginTop: 10 }}>
            <SegmentBar pct={budget.pct / 100} width={250} />
          </div>
          <div className="budget-note">{budget.note}</div>
        </section>
      </div>

      {suggestion && <AdvisorCard suggestion={suggestion} />}
    </div>
  );
}
