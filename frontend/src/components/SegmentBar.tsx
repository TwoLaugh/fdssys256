const DEFAULT_SEGMENTS = 22;

export interface SegmentBarProps {
  /** Fill fraction, 0–1 (clamped). */
  pct: number;
  /** Olive = on track (default); amber = behind / time-sensitive. */
  tone?: "olive" | "amber";
  segments?: number;
  /** Bar width; defaults to filling its container. */
  width?: number;
}

export function SegmentBar({
  pct,
  tone = "olive",
  segments = DEFAULT_SEGMENTS,
  width,
}: SegmentBarProps) {
  const filled = Math.round(Math.min(1, Math.max(0, pct)) * segments);
  return (
    <div
      className={`segbar${tone === "amber" ? " amber" : ""}`}
      style={width !== undefined ? { width } : undefined}
      role="img"
      aria-label={`${Math.round(pct * 100)}%`}
    >
      {Array.from({ length: segments }, (_, i) => (
        <span key={i} className={i < filled ? "filled" : undefined} />
      ))}
    </div>
  );
}
