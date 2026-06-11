import type { MealStatus } from "../api/today";

const GLYPH: Record<MealStatus, string> = {
  eaten: "✓", // ✓ olive
  cooked: "●", // ● amber
  planned: "○", // ○ muted
};

export function StatusMark({ status }: { status: MealStatus }) {
  return (
    <span className={`status-mark ${status}`} aria-hidden="true">
      {GLYPH[status]}
    </span>
  );
}
