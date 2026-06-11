import { useState } from "react";

/**
 * Global "Give feedback" floating button. Opens a placeholder modal —
 * wiring to the feedback API comes later.
 */
export function FeedbackButton() {
  const [open, setOpen] = useState(false);

  return (
    <>
      <button className="btn feedback-fab" onClick={() => setOpen(true)}>
        Give feedback
      </button>
      {open && (
        <div
          className="modal-overlay"
          onClick={() => setOpen(false)}
          role="presentation"
        >
          <div
            className="modal-card mp-card"
            role="dialog"
            aria-modal="true"
            aria-label="Give feedback"
            onClick={(e) => e.stopPropagation()}
          >
            <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
              Give feedback
            </span>
            <p style={{ marginTop: 12, color: "var(--mp-muted)" }}>
              Tell the advisor what worked and what didn't — too salty, too
              slow, loved it. Feedback wiring lands with the feedback
              subsystem; this is a placeholder.
            </p>
            <div className="modal-actions">
              <button className="btn" onClick={() => setOpen(false)}>
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
