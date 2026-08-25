/**
 * Shared per-nutrient row grammar for both plan-vs-target lenses
 * (t5-plan-vs-target-ui): the Plan page's projection panel and the Nutrition
 * Overview's retrospective micros panel render the same row (label, value vs
 * target, segment bar) and differ only in what they feed it.
 *
 * NO_DATA rule for both lenses: value == null means unmeasured, never zero and
 * never failed. Renders muted "no data" with the target kept visible and an
 * empty bar. Warn is binary and caller-decided from data the backend already
 * carries (met/status on projection rows, upperLimit on retrospective rows);
 * this component authors no thresholds.
 */

import type { ReactNode } from "react";
import { SegmentBar } from "./SegmentBar";

export const fmtNutrient = (n: number): string =>
  n >= 100
    ? Math.round(n).toLocaleString("en-GB")
    : String(Math.round(n * 10) / 10);

/** Provenance badge for non-measured projection rows: the user should know a
 *  number is USDA-derived or an AI estimate rather than hard recipe data. */
export function ProvenanceBadge({ source }: { source?: string | null }) {
  if (!source || source === "measured") return null;
  const label = source === "estimated" ? "est" : "USDA";
  const title =
    source === "estimated"
      ? "AI estimate (low confidence) — no measured or USDA value available"
      : "derived from USDA by matching ingredients (approximate)";
  return (
    <span
      title={title}
      style={{
        marginLeft: 6,
        fontSize: "0.7em",
        padding: "0 4px",
        borderRadius: 3,
        border: "1px solid var(--mp-line)",
        color: source === "estimated" ? "var(--mp-amber)" : "var(--mp-ink-soft, #888)",
        verticalAlign: "middle",
      }}
    >
      {label}
    </span>
  );
}

export interface NutrientRowProps {
  label: string;
  unit: string;
  /** Null = untargeted (retrospective rows for actuals with no target). */
  target: number | null;
  /** Target renders as an upper bound ("≤ N") instead of "/ N". */
  upperBound?: boolean;
  /** Null = no data: muted row, target kept, empty bar, no warn treatment. */
  value: number | null;
  /** Amber treatment + short-of-target marker. Caller-decided from backend
   *  data; never from a client-authored threshold. */
  warn?: boolean;
  /** Tooltip for the warn marker (e.g. "short of target", "over the limit"). */
  warnTitle?: string;
  /** Hard-floor ▪ on the label. */
  hardFloor?: boolean;
  /** Extra element after the value (provenance badge). */
  badge?: ReactNode;
  tooltip?: string;
}

export function NutrientRow({
  label,
  unit,
  target,
  upperBound = false,
  value,
  warn = false,
  warnTitle = "short of target",
  hardFloor = false,
  badge,
  tooltip,
}: NutrientRowProps) {
  const targetNote =
    target == null
      ? " · untargeted"
      : upperBound
        ? ` ≤ ${fmtNutrient(target)}`
        : ` / ${fmtNutrient(target)}`;

  if (value == null) {
    return (
      <div className="micro-row" title={tooltip}>
        <span style={{ color: "var(--mp-ink-soft, #999)" }}>{label}</span>
        <span style={{ color: "var(--mp-ink-soft, #999)", fontStyle: "italic" }}>
          no data{targetNote}
        </span>
        <SegmentBar pct={0} segments={12} />
      </div>
    );
  }

  const denom = target != null && target > 0 ? target : 1;
  return (
    <div className="micro-row" title={tooltip}>
      <span>
        {label}
        {hardFloor && (
          <span className="hard-floor-mark" title="hard floor">
            ▪
          </span>
        )}
        {warn && (
          <span
            className="hard-floor-mark"
            style={{ color: "var(--mp-amber)" }}
            title={warnTitle}
          >
            ▪
          </span>
        )}
      </span>
      <span
        style={{
          fontVariantNumeric: "tabular-nums",
          color: warn ? "var(--mp-amber)" : undefined,
          fontWeight: warn ? 600 : 400,
        }}
      >
        {fmtNutrient(value)} {unit}
        {targetNote}
        {badge}
      </span>
      <SegmentBar
        pct={target != null && target > 0 ? value / denom : 0}
        segments={12}
        tone={warn ? "amber" : "olive"}
      />
    </div>
  );
}
