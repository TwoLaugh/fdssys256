import type { TodayMeal } from "../api/today";
import { StatusMark } from "./StatusMark";

const STATUS_COLOR: Record<TodayMeal["status"], string> = {
  eaten: "var(--mp-olive)",
  cooked: "var(--mp-amber)",
  planned: "var(--mp-muted)",
};

export interface MealRowProps {
  meal: TodayMeal;
  onAction?: (meal: TodayMeal) => void;
}

export function MealRow({ meal, onAction }: MealRowProps) {
  return (
    <div className="meal-row">
      <div>
        <span className="mp-num meal-time">{meal.time}</span>
        <div style={{ marginTop: 4 }}>
          <span className="mp-label">{meal.slot}</span>
        </div>
      </div>
      <div>
        <div style={{ display: "flex", alignItems: "baseline", gap: 9 }}>
          <span className="meal-name">{meal.name}</span>
          {meal.batch && <span className="batch-tag">BATCH</span>}
        </div>
        <div className="meal-meta">{meal.meta}</div>
        {meal.alert && <div className="meal-alert">❄ {meal.alert}</div>}
      </div>
      <div className="meal-status">
        <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
          <StatusMark status={meal.status} />
          <span
            className="mp-label"
            style={{ color: STATUS_COLOR[meal.status] }}
          >
            {meal.status}
          </span>
        </span>
        {meal.action && (
          <button
            className={`btn${meal.status === "planned" ? " btn-primary" : ""}`}
            onClick={() => onAction?.(meal)}
          >
            {meal.action}
          </button>
        )}
      </div>
    </div>
  );
}
