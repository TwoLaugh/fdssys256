import { useRef, useState } from "react";
import {
  answerClarification,
  markFeedbackCorrected,
  submitFeedback,
  tierFor,
  useStore,
} from "../mock/store";
import type { FeedbackRoute } from "../mock/types";
import { Modal } from "./Modal";
import { TierMark, TIER_INFO } from "./TierMark";

type Phase = "compose" | "classifying" | "routed";

const COUNT_WORD = ["zero", "one", "two", "three", "four"] as const;

function RouteRow({
  route,
  entryId,
  corrected,
}: {
  route: FeedbackRoute;
  entryId: string;
  corrected: boolean;
}) {
  const tier = tierFor(route.conf);
  const info = TIER_INFO[tier];
  return (
    <div className="route-row mp-card">
      <TierMark tier={tier} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="route-head">
          <span className="route-dest">{route.dest}</span>
          <span className="route-conf">confidence {route.conf.toFixed(2)}</span>
          <span className="route-tier" style={{ color: info.color }}>
            {info.label}
          </span>
        </div>
        {route.action && <div className="route-action">{route.action}</div>}
        {route.question && (
          <div style={{ marginTop: 6 }}>
            <span className="mp-serif" style={{ fontSize: 17.5 }}>
              {route.question}
            </span>
            {route.answered ? (
              <div className="route-answered">
                ✓ answered — {route.answered.toLowerCase()}
              </div>
            ) : (
              <div
                style={{ display: "flex", gap: 8, marginTop: 10, flexWrap: "wrap" }}
              >
                {(route.options ?? []).map((option) => (
                  /* Equal-weight ghost options — never pre-select an answer. */
                  <button
                    key={option}
                    className="btn btn-small"
                    onClick={() => answerClarification(`c-${entryId}`, option)}
                  >
                    {option}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
      {tier !== "low" &&
        (corrected ? (
          <span className="route-corrected">correction recorded</span>
        ) : (
          <button
            className="btn btn-small"
            style={
              tier === "mid"
                ? { color: "var(--mp-amber)", borderColor: "var(--mp-amber)" }
                : undefined
            }
            onClick={() => markFeedbackCorrected(entryId)}
          >
            {tier === "mid" ? "Correct this" : "This isn't right"}
          </button>
        ))}
    </div>
  );
}

/**
 * Global "Give feedback" button + the real feedback modal: free text →
 * fake 0.8s classification → routing confirmation with confidence tiers
 * (mockup d6-feedback). Submissions land in Activity's feedback history,
 * raise a notification, and low-confidence routes file a clarification.
 */
export function FeedbackButton() {
  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>("compose");
  const [text, setText] = useState("");
  const [entryId, setEntryId] = useState<string | null>(null);
  const timerRef = useRef<number | null>(null);

  const entry = useStore((s) =>
    entryId === null
      ? undefined
      : s.activity.feedback.find((f) => f.id === entryId),
  );

  const close = () => {
    if (timerRef.current !== null) {
      // Closing mid-classification cancels the submission entirely.
      window.clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    setOpen(false);
    setPhase("compose");
    setText("");
    setEntryId(null);
  };

  const submit = () => {
    const trimmed = text.trim();
    if (!trimmed) return;
    setPhase("classifying");
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null;
      setEntryId(submitFeedback(trimmed));
      setPhase("routed");
    }, 800);
  };

  return (
    <>
      <button className="btn feedback-fab" onClick={() => setOpen(true)}>
        Give feedback
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

          {phase === "classifying" && (
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

          {phase === "routed" && entry && (
            <>
              <span
                className="mp-label"
                style={{ color: "var(--mp-terra-dark)" }}
              >
                Feedback received
              </span>
              <div className="feedback-echo mp-card">“{entry.text}”</div>
              <div style={{ marginTop: 20 }}>
                <span className="mp-label">
                  I heard{" "}
                  {COUNT_WORD[entry.routes.length] ?? entry.routes.length}{" "}
                  thing{entry.routes.length === 1 ? "" : "s"}
                </span>
                <div style={{ display: "grid", gap: 12, marginTop: 12 }}>
                  {entry.routes.map((route, i) => (
                    <RouteRow
                      key={`${route.dest}-${i}`}
                      route={route}
                      entryId={entry.id}
                      corrected={entry.corrected === true}
                    />
                  ))}
                </div>
              </div>
              <div className="feedback-foot">
                <span className="grocery-footnote">
                  Correcting a route teaches the classifier — corrections are
                  tracked.
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
