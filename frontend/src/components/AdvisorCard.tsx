import type { AdvisorSuggestion } from "../api/today";

export interface AdvisorCardProps {
  suggestion: AdvisorSuggestion;
  onReview?: () => void;
  onAccept?: () => void;
}

/**
 * AI advisor suggestion card: terra dot + kicker label, advisor-voice
 * (serif italic) title, ghost Review + primary Accept actions.
 */
export function AdvisorCard({
  suggestion,
  onReview,
  onAccept,
}: AdvisorCardProps) {
  return (
    <div className="advisor-card mp-card">
      <div>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span className="advisor-dot" />
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            {suggestion.label}
          </span>
        </div>
        <span className="mp-serif advisor-title">{suggestion.title}</span>
        <div className="advisor-sub">{suggestion.sub}</div>
      </div>
      <div style={{ display: "flex", gap: 10, flexShrink: 0 }}>
        <button className="btn" onClick={onReview}>
          Review
        </button>
        <button className="btn btn-primary" onClick={onAccept}>
          Accept
        </button>
      </div>
    </div>
  );
}
