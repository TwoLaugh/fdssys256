/**
 * Stat band primitive (design-language §primitives): a full-width card of N
 * equal cells divided by hairlines — uppercase label / big numeral / sub-line.
 * Amber label+value when the cell is a warning. Distinct from StatBand, which
 * is the nutrition variant with segmented progress bars.
 */

export interface StatStripCell {
  label: string;
  value: string;
  sub?: string;
  warn?: boolean;
}

export function StatStrip({
  cells,
  numeralSize = 24,
}: {
  cells: StatStripCell[];
  numeralSize?: number;
}) {
  return (
    <div
      className="stat-strip mp-card"
      style={{ gridTemplateColumns: `repeat(${cells.length}, 1fr)` }}
    >
      {cells.map((cell) => (
        <div key={cell.label} className="stat-strip-cell">
          <span
            className="mp-label"
            style={cell.warn ? { color: "var(--mp-amber)" } : undefined}
          >
            {cell.label}
          </span>
          <div style={{ marginTop: 7 }}>
            <span
              className="mp-num"
              style={{
                fontSize: numeralSize,
                color: cell.warn ? "var(--mp-amber)" : "var(--mp-ink)",
              }}
            >
              {cell.value}
            </span>
          </div>
          {cell.sub && <div className="stat-strip-sub">{cell.sub}</div>}
        </div>
      ))}
    </div>
  );
}
