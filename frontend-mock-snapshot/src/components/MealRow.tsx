import type { ReactNode } from "react";
import type { SlotState } from "../mock/types";
import { StatusMark } from "./StatusMark";

const STATE_COLOR: Record<SlotState, string> = {
  EATEN: "var(--mp-olive)",
  COOKED: "var(--mp-amber)",
  COOKING: "var(--mp-amber)",
  PLANNED: "var(--mp-muted)",
  SKIPPED: "var(--mp-muted)",
};

export interface MealRowMeal {
  /** Wall-clock serve time (MealSlotDto.mealTime); null → no time shown. */
  time: string | null;
  /** Slot label, e.g. "Breakfast" / "Post-gym shake". */
  slot: string;
  name: string;
  /** Secondary line, e.g. "Just you · serves 1 · 380 kcal planned". */
  meta: string;
  /** Planner slot state (the cooking lifecycle machine). */
  state: SlotState;
  /** Linked to a batch-cook session. */
  batch?: boolean;
  /** Intake decided ("logged" tick — the nutrition logging machine). */
  logged?: boolean;
  /** Lead-time hint, e.g. "start cooking 18:35". */
  hint?: string;
}

export interface MealRowProps {
  meal: MealRowMeal;
  /** Action buttons (dual-write wiring lives with the caller). */
  actions?: ReactNode;
}

/** One timeline row reflecting BOTH machines: planner state + logged tick. */
export function MealRow({ meal, actions }: MealRowProps) {
  return (
    <div className="meal-row">
      <div>
        {meal.time !== null ? (
          <span className="mp-num meal-time">{meal.time}</span>
        ) : (
          <span
            className="meal-time meal-time-unset"
            title="No serve time set — slot override is null and the schedule fallback is server-internal (spec Q3)"
          >
            —
          </span>
        )}
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
        {meal.hint && <div className="meal-alert">⏱ {meal.hint}</div>}
      </div>
      <div className="meal-status">
        <span style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span style={{ display: "flex", alignItems: "center", gap: 5 }}>
            <StatusMark status={meal.state} />
            <span
              className="mp-label"
              style={{ color: STATE_COLOR[meal.state] }}
            >
              {meal.state.toLowerCase()}
            </span>
          </span>
          {meal.logged && (
            <span className="mp-label" style={{ color: "var(--mp-olive)" }}>
              ✓ logged
            </span>
          )}
        </span>
        {actions}
      </div>
    </div>
  );
}
