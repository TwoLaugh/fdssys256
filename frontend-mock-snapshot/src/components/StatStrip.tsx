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
  compact = false,
}: {
  cells: StatStripCell[];
  numeralSize?: number;
  /** Lower per-cell width floor for strips inside narrow containers. */
  compact?: boolean;
}) {
  // Column layout lives in CSS (.stat-strip auto-fit) so cells reflow on
  // narrow windows instead of crushing.
  return (
    <div className={`stat-strip${compact ? " compact" : ""} mp-card`}>
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
