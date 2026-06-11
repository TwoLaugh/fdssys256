import type { NutritionStat } from "../api/today";
import { SegmentBar } from "./SegmentBar";

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
