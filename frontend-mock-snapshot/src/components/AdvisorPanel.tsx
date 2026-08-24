import type { ReactNode } from "react";

/**
 * Full-width AI advisor card with the consistent anatomy from the design
 * language: terracotta dot + uppercase label → serif (advisor voice) title →
 * detail rows → impact line → Dismiss (ghost) + Accept (filled).
 *
 * Used for plan re-optimisation fixes and grocery substitutions; the
 * side-action variant lives in AdvisorCard.
 */
export interface AdvisorPanelProps {
  label: string;
  /** Right-aligned header note, e.g. "2 future slots affected · …". */
  headerRight?: string;
  /** Advisor-voice title (rendered serif italic). */
  title: string;
  titleSize?: number;
  children?: ReactNode;
  /** Impact line shown left of the actions, e.g. "Cost −£1.10 · …". */
  impact?: string;
  dismissLabel?: string;
  acceptLabel: string;
  onDismiss?: () => void;
  onAccept: () => void;
  /** Compact spacing + small buttons for right-rail cards. */
  small?: boolean;
}

export function AdvisorPanel({
  label,
  headerRight,
  title,
  titleSize = 23,
  children,
  impact,
  dismissLabel = "Dismiss",
  acceptLabel,
  onDismiss,
  onAccept,
  small = false,
}: AdvisorPanelProps) {
  const btnClass = small ? "btn btn-small" : "btn";
  const actions = (
    <div style={{ display: "flex", gap: small ? 8 : 10 }}>
      {onDismiss && (
        <button className={btnClass} onClick={onDismiss}>
          {dismissLabel}
        </button>
      )}
      <button className={`${btnClass} btn-primary`} onClick={onAccept}>
        {acceptLabel}
      </button>
    </div>
  );

  return (
    <div className={`advisor-panel mp-card${small ? " small" : ""}`}>
      <div className="advisor-panel-head">
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span className="advisor-dot" />
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            {label}
          </span>
        </div>
        {headerRight && <span className="advisor-panel-note">{headerRight}</span>}
      </div>
      <div style={{ marginTop: small ? 10 : 8 }}>
        <span className="mp-serif" style={{ fontSize: titleSize }}>
          {title}
        </span>
      </div>
      {children}
      {impact !== undefined ? (
        <div className="advisor-panel-footer">
          <span className="advisor-panel-impact">{impact}</span>
          {actions}
        </div>
      ) : (
        <div style={{ marginTop: 14 }}>{actions}</div>
      )}
    </div>
  );
}
