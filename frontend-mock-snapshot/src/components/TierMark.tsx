import type { ConfidenceTier } from "../mock/types";

/**
 * Confidence-tier marks (design-language §primitives): ✓ olive "routed" /
 * ? amber "check me" / … terracotta "needs you". Reading order = decreasing
 * machine confidence, increasing user involvement.
 */
export const TIER_INFO: Record<
  ConfidenceTier,
  { mark: string; color: string; label: string }
> = {
  high: { mark: "✓", color: "var(--mp-olive)", label: "routed" },
  mid: { mark: "?", color: "var(--mp-amber)", label: "check me" },
  low: { mark: "…", color: "var(--mp-terra)", label: "needs you" },
};

export function TierMark({ tier }: { tier: ConfidenceTier }) {
  const t = TIER_INFO[tier];
  return (
    <span
      className="tier-mark"
      style={{ borderColor: t.color, color: t.color }}
      aria-label={t.label}
    >
      {t.mark}
    </span>
  );
}
