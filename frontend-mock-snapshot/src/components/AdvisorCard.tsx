import type { ReactNode } from "react";

export interface AdvisorCardProps {
  /** Kicker label, e.g. "Suggestion · from your feedback". */
  label: string;
  /** Advisor-voice (serif italic) title. */
  title: string;
  titleSize?: number;
  /** Supporting line. */
  sub?: string;
  /** Optional detail rows (e.g. a SwapLine). */
  children?: ReactNode;
  /** Action buttons, rendered to the right of the content. */
  actions: ReactNode;
}

/**
 * AI advisor card with side-aligned actions: terra dot + kicker label,
 * advisor-voice (serif italic) title, optional sub line and detail rows.
 * The full-width footer-action variant lives in AdvisorPanel.
 */
export function AdvisorCard({
  label,
  title,
  titleSize = 21,
  sub,
  children,
  actions,
}: AdvisorCardProps) {
  return (
    <div className="advisor-card mp-card">
      <div style={{ minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span className="advisor-dot" />
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            {label}
          </span>
        </div>
        <span className="mp-serif advisor-title" style={{ fontSize: titleSize }}>
          {title}
        </span>
        {sub && <div className="advisor-sub">{sub}</div>}
        {children}
      </div>
      <div style={{ display: "flex", gap: 10, flexShrink: 0 }}>{actions}</div>
    </div>
  );
}
