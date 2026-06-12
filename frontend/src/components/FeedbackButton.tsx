import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  clearComposePrefill,
  findClarification,
  submitFeedback,
  useStore,
} from "../mock/store";
import { ClarificationAnswer, RouteLine } from "./FeedbackBits";
import { Modal } from "./Modal";

type Phase = "compose" | "routed";

const COUNT_WORD = ["zero", "one", "two", "three", "four"] as const;

/**
 * Global "Give feedback" button + modal: POST /api/v1/feedback (202 +
 * Location), then poll the entry until classification settles — routes with
 * server-decided tier marks, or a clarification that pauses the whole entry
 * (activity.md §4b/§7). The 410-expired re-submit CTA opens this modal
 * pre-filled via the store's composePrefill.
 */
export function FeedbackButton() {
  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>("compose");
  const [text, setText] = useState("");
  const [entryId, setEntryId] = useState<string | null>(null);

  const entry = useStore((s) =>
    entryId === null
      ? undefined
      : s.activity.feedback.find((f) => f.id === entryId),
  );
  const pendingQuery = useStore((s) =>
    entry?.pendingClarificationQueryId
      ? findClarification(s, entry.pendingClarificationQueryId)
      : undefined,
  );
  const prefill = useStore((s) => s.activity.composePrefill);

  // 410-expired clarification → "re-submit your feedback" pre-fill (§5b).
  useEffect(() => {
    if (prefill !== null) {
      setText(prefill);
      setPhase("compose");
      setEntryId(null);
      setOpen(true);
      clearComposePrefill();
    }
  }, [prefill]);

  const close = () => {
    setOpen(false);
    setPhase("compose");
    setText("");
    setEntryId(null);
  };

  const submit = () => {
    const trimmed = text.trim();
    if (!trimmed) return;
    // 202 + Location — the receipt is polled, not awaited.
    setEntryId(submitFeedback(trimmed));
    setPhase("routed");
  };

  const classifying =
    entry !== undefined &&
    (entry.submissionStatus === "RECEIVED" ||
      entry.submissionStatus === "CLASSIFYING" ||
      entry.submissionStatus === "CLASSIFIED");

  return (
    <>
      {/* Below ~700px the label hides and the icon shows (CSS .feedback-fab). */}
      <button
        className="btn feedback-fab"
        aria-label="Give feedback"
        onClick={() => setOpen(true)}
      >
        <span className="fab-icon" aria-hidden="true">
          ✎
        </span>
        <span className="fab-label">Give feedback</span>
      </button>
      {open && (
        <Modal label="Give feedback" onClose={close} wide={phase === "routed"}>
          {phase === "compose" && (
            <>
              <span
                className="mp-label"
                style={{ color: "var(--mp-terra-dark)" }}
              >
                Give feedback
              </span>
              <p className="dialog-body" style={{ marginTop: 10 }}>
                Tell the advisor what worked and what didn't — too salty, too
                slow, loved it. It gets routed to the right place.
              </p>
              <textarea
                className="text-input feedback-textarea"
                placeholder="The stir fry was way too salty…"
                value={text}
                onChange={(e) => setText(e.target.value)}
                aria-label="Feedback text"
                autoFocus
              />
              <div className="modal-actions">
                <button className="btn" onClick={close}>
                  Cancel
                </button>
                <button
                  className="btn btn-primary"
                  onClick={submit}
                  disabled={!text.trim()}
                >
                  Submit
                </button>
              </div>
            </>
          )}

          {phase === "routed" && entry && classifying && (
            <>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span className="advisor-dot" />
                <span
                  className="mp-label"
                  style={{ color: "var(--mp-terra-dark)" }}
                >
                  Reading that back
                </span>
              </div>
              <div style={{ marginTop: 10 }}>
                <span className="mp-serif" style={{ fontSize: 21 }}>
                  Working out who needs to hear this…
                </span>
              </div>
              <div className="classify-pulse" aria-hidden="true" />
            </>
          )}

          {phase === "routed" && entry && !classifying && (
            <>
              <span
                className="mp-label"
                style={{ color: "var(--mp-terra-dark)" }}
              >
                Feedback received
              </span>
              <div className="feedback-echo mp-card">“{entry.text}”</div>

              {entry.submissionStatus === "CLARIFICATION_PENDING" &&
              pendingQuery ? (
                <div style={{ marginTop: 18 }}>
                  <span className="mp-label">One thing needs you first</span>
                  <div style={{ marginTop: 8 }}>
                    {/* Advisor voice — a service call back to you, not a chat. */}
                    <span className="mp-serif" style={{ fontSize: 19 }}>
                      {pendingQuery.questionText}
                    </span>
                  </div>
                  <ClarificationAnswer query={pendingQuery} />
                  <div className="grocery-footnote" style={{ marginTop: 12 }}>
                    Nothing routes until you answer — also waiting in{" "}
                    <Link to="/activity" onClick={close}>
                      Activity
                    </Link>
                    .
                  </div>
                </div>
              ) : (
                <div style={{ marginTop: 20 }}>
                  <span className="mp-label">
                    I heard{" "}
                    {COUNT_WORD[entry.routes.length] ?? entry.routes.length}{" "}
                    thing{entry.routes.length === 1 ? "" : "s"}
                  </span>
                  <div style={{ display: "grid", gap: 12, marginTop: 12 }}>
                    {entry.routes.map((route) => (
                      <RouteLine
                        key={route.id}
                        entryId={entry.id}
                        route={route}
                        recipeAttached={entry.context.recipeId != null}
                        framed
                      />
                    ))}
                  </div>
                </div>
              )}

              <div className="feedback-foot">
                <span className="grocery-footnote">
                  Correcting a route teaches the classifier — corrections are
                  logged as ground truth.
                </span>
                <button className="btn btn-primary" onClick={close}>
                  Done
                </button>
              </div>
            </>
          )}
        </Modal>
      )}
    </>
  );
}
