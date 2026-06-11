import type { SlotState } from "../mock/types";

/**
 * Symbol-redundant status marks (design-language principle 3):
 * ✓ eaten (olive) · ● cooked/cooking (amber) · ○ planned (muted) ·
 * ✕ affected by suggestion (red).
 */
const GLYPH: Record<SlotState, string> = {
  eaten: "✓",
  cooked: "●",
  cooking: "●",
  planned: "○",
  affected: "✕",
};

export function StatusMark({ status }: { status: SlotState }) {
  return (
    <span className={`status-mark ${status}`} aria-hidden="true">
      {GLYPH[status]}
    </span>
  );
}
