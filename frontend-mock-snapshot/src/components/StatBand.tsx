import { SegmentBar } from "./SegmentBar";

/** A single glanceable nutrition stat cell (Today + Nutrition stat bands). */
export interface NutritionStat {
  label: string;
  /** Current intake in the stat's unit. */
  value: number;
  /** Daily target in the same unit. */
  target: number;
  /** Formatted intake, e.g. "1,420". */
  display: string;
  /** Formatted target including unit, e.g. "2,000" or "120 g". */
  targetDisplay: string;
  /** Time-adjusted pacing flag: behind where you should be by now. */
  behind?: boolean;
}

export function StatBand({ stats }: { stats: NutritionStat[] }) {
  return (
    <div className="stat-band mp-card">
      {stats.map((stat) => (
        <div key={stat.label} className="stat-cell">
          <span
            className="mp-label"
            style={stat.behind ? { color: "var(--mp-amber)" } : undefined}
          >
            {stat.label}
            {stat.behind ? " · behind" : ""}
          </span>
          <div className="stat-value">
            <span
              className="mp-num"
              style={{
                fontSize: 30,
                color: stat.behind ? "var(--mp-amber)" : "var(--mp-ink)",
              }}
            >
              {stat.display}
            </span>
            <span className="stat-target">/ {stat.targetDisplay}</span>
          </div>
          <SegmentBar
            pct={stat.target > 0 ? stat.value / stat.target : 0}
            tone={stat.behind ? "amber" : "olive"}
            width={150}
          />
        </div>
      ))}
    </div>
  );
}
