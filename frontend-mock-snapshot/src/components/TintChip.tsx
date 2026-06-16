import type { ReactNode } from "react";

/**
 * Provenance chip (design-language §primitives): a small tinted pill tying
 * data to its cause — "added by suggested fix", "swapped after feedback",
 * "uses expiring spinach". Olive tint = done/confirmed provenance, terra
 * tint = suggestion-driven provenance.
 */
export function TintChip({
  children,
  tone = "olive",
}: {
  children: ReactNode;
  tone?: "olive" | "terra";
}) {
  return <span className={`tint-chip ${tone}`}>{children}</span>;
}
