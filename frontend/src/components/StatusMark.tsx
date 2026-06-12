import type { SlotMark } from "../mock/types";

/**
 * Symbol-redundant status marks (design-language principle 3, plan.md §3d):
 * ○ planned · ◐ cooking (amber) · ● cooked (amber) · ✓ eaten (olive) ·
 * — skipped (muted) · ✕ affected-by-suggestion (red, derived overlay).
 */
const MARK: Record<SlotMark, { glyph: string; className: string }> = {
  PLANNED: { glyph: "○", className: "planned" },
  COOKING: { glyph: "◐", className: "cooking" },
  COOKED: { glyph: "●", className: "cooked" },
  EATEN: { glyph: "✓", className: "eaten" },
  SKIPPED: { glyph: "—", className: "skipped" },
  AFFECTED: { glyph: "✕", className: "affected" },
};

export function StatusMark({ status }: { status: SlotMark }) {
  const m = MARK[status];
  return (
    <span className={`status-mark ${m.className}`} aria-hidden="true">
      {m.glyph}
    </span>
  );
}

export const slotMarkClass = (status: SlotMark): string =>
  MARK[status].className;
