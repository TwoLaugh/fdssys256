import { TintChip } from "./TintChip";

/**
 * Before → after detail line used inside advisor cards: strikethrough old
 * value, arrow, bold new value, optional provenance note and delta.
 */
export interface SwapLineProps {
  from: string;
  to: string;
  /** Leading slot label, e.g. "Thu dinner". */
  prefix?: string;
  /** Olive-tinted provenance note, e.g. "uses expiring spinach". */
  note?: string;
  /** Trailing olive delta, e.g. "−£0.40". */
  delta?: string;
}

export function SwapLine({ from, to, prefix, note, delta }: SwapLineProps) {
  return (
    <div className="swap-line">
      {prefix && <span className="swap-prefix">{prefix}</span>}
      <span className="swap-from">{from}</span>
      <span className="swap-arrow">→</span>
      <span className="swap-to">{to}</span>
      {note && <TintChip>{note}</TintChip>}
      {delta && <span className="swap-delta">{delta}</span>}
    </div>
  );
}
