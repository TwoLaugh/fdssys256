/**
 * Shared feedback-routing primitives (activity.md §4): the route row with
 * server-decided tier marks, the per-route correction picker (#8), the
 * clarification answer form (#12), and the submission-status chip. Used by
 * both the Activity page and the global feedback modal.
 */

import { useState } from "react";
import { Link } from "react-router-dom";
import {
  answerClarification,
  correctRoute,
  DESTINATION_LABEL,
  tierForDecision,
} from "../mock/store";
import type {
  ClarificationQueryDto,
  Destination,
  RoutingDecisionDto,
  SubmissionStatus,
} from "../mock/types";
import { Modal } from "./Modal";
import { TierMark, TIER_INFO } from "./TierMark";
import { TintChip } from "./TintChip";

const ALL_DESTINATIONS: Destination[] = [
  "RECIPE",
  "PREFERENCE",
  "NUTRITION",
  "PROVISIONS",
];

/* ---- submission-status chip (§4d) ------------------------------------------------ */

export function SubmissionChip({ status }: { status: SubmissionStatus }) {
  switch (status) {
    case "RECEIVED":
    case "CLASSIFYING":
    case "CLASSIFIED": // transient internal hand-off — render as working
      return <span className="mp-chip muted">working…</span>;
    case "ROUTED":
      return <TintChip>✓ routed</TintChip>;
    case "CLARIFICATION_PENDING":
      return (
        <span className="mp-chip" style={{ color: "var(--mp-terra-dark)", borderColor: "var(--mp-terra)" }}>
          … needs you
        </span>
      );
    case "PARTIALLY_FAILED":
      return <span className="mp-chip amber">partly applied</span>;
    case "FAILED":
      return (
        <span className="mp-chip" style={{ color: "var(--mp-red)", borderColor: "var(--mp-red)" }}>
          failed
        </span>
      );
    case "CORRECTED":
      return <span className="mp-chip muted">✎ correction recorded</span>;
  }
}

/* ---- route-status chip (§4b/§4d) -------------------------------------------------- */

function RouteStatusChip({ status }: { status: RoutingDecisionDto["status"] }) {
  switch (status) {
    case "APPLIED":
      return <TintChip>applied ✓</TintChip>;
    case "AWAITING_USER_APPROVAL":
      // The recipe destination produced a pending-change card (§3).
      return (
        <Link to="/activity" className="mp-chip amber" style={{ textDecoration: "none" }}>
          ⧖ awaiting your approval
        </Link>
      );
    case "PENDING":
      return <span className="mp-chip muted">pending</span>;
    case "FAILED":
      return (
        <span className="mp-chip" style={{ color: "var(--mp-red)", borderColor: "var(--mp-red)" }}>
          ✕ failed
        </span>
      );
    case "CORRECTED_AWAY":
      return <span className="mp-chip muted">re-routed</span>;
    case "REPLAYED":
      return <span className="mp-chip muted">replayed</span>;
  }
}

/* ---- correction picker (§4c) ------------------------------------------------------ */

function CorrectionPicker({
  entryId,
  route,
  recipeAttached,
  onClose,
}: {
  entryId: string;
  route: RoutingDecisionDto;
  recipeAttached: boolean;
  onClose: () => void;
}) {
  const [picked, setPicked] = useState<Destination | null>(null);
  const [note, setNote] = useState("");
  const options = ALL_DESTINATIONS.filter((d) => d !== route.destination);

  return (
    <Modal label="Correct this routing" onClose={onClose}>
      <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
        Correct this routing
      </span>
      <p className="dialog-body" style={{ marginTop: 10 }}>
        “{route.extractedFeedback}” went to{" "}
        <strong>{DESTINATION_LABEL[route.destination]}</strong>. Where should it
        have gone? Corrections are logged as ground truth for the classifier.
      </p>
      <div className="clar-options">
        {options.map((dest) => {
          const blocked = dest === "RECIPE" && !recipeAttached;
          return (
            <button
              key={dest}
              className={`btn btn-small${picked === dest ? " btn-primary" : ""}`}
              disabled={blocked}
              title={
                blocked
                  ? "No recipe attached to this feedback — recipe corrections need one (422)"
                  : undefined
              }
              onClick={() => setPicked(dest)}
            >
              {DESTINATION_LABEL[dest]}
            </button>
          );
        })}
      </div>
      <input
        type="text"
        className="text-input"
        style={{ width: "100%", marginTop: 12 }}
        placeholder="What did you mean? (optional)"
        maxLength={512}
        value={note}
        onChange={(e) => setNote(e.target.value)}
        aria-label="Correction note"
      />
      <div className="modal-actions">
        <button className="btn" onClick={onClose}>
          Cancel
        </button>
        <button
          className="btn btn-primary"
          disabled={picked === null}
          onClick={() => {
            if (picked) correctRoute(entryId, route.id, picked, note);
            onClose();
          }}
        >
          Re-route
        </button>
      </div>
      <div className="grocery-footnote" style={{ marginTop: 12 }}>
        One correction per route; undo of the already-applied action is
        best-effort — the routing is what gets fixed.
      </div>
    </Modal>
  );
}

/* ---- route row (§4b) --------------------------------------------------------------- */

/**
 * One routing-decision row. The tier mark renders from the SERVER decision
 * (AUTO_ROUTED ✓ / ROUTED_WITH_FLAG ?) — never re-derived from confidence.
 * `framed` switches to the modal's card layout (.route-row vs .route-line).
 */
export function RouteLine({
  entryId,
  route,
  recipeAttached,
  framed = false,
}: {
  entryId: string;
  route: RoutingDecisionDto;
  recipeAttached: boolean;
  framed?: boolean;
}) {
  const [correcting, setCorrecting] = useState(false);
  const [showResult, setShowResult] = useState(false);
  const tier = tierForDecision(route.decision);
  const info = TIER_INFO[tier];
  const correctedAway = route.status === "CORRECTED_AWAY";
  const correctable =
    !correctedAway && route.status !== "REPLAYED" && route.status !== "FAILED";

  return (
    <div className={framed ? "route-row mp-card" : "route-line"}>
      <TierMark tier={tier} />
      <div style={{ flex: 1, minWidth: 0, opacity: correctedAway ? 0.62 : 1 }}>
        <div className="route-head">
          <span
            className="route-dest"
            style={correctedAway ? { textDecoration: "line-through" } : undefined}
          >
            {DESTINATION_LABEL[route.destination]}
          </span>
          <span className="route-conf">confidence {route.confidence.toFixed(2)}</span>
          <span className="route-tier" style={{ color: info.color }}>
            {info.label}
          </span>
          <RouteStatusChip status={route.status} />
        </div>
        <div className="route-fragment">“{route.extractedFeedback}”</div>
        {route.decision === "ROUTED_WITH_FLAG" && !correctedAway && (
          <div className="route-flag-caption">
            I think you meant {DESTINATION_LABEL[route.destination].toLowerCase()}{" "}
            — correct me if wrong.
          </div>
        )}
        {route.actionTaken && <div className="route-action">{route.actionTaken}</div>}
        {route.failureMessage && (
          <div className="route-failure">{route.failureMessage}</div>
        )}
        {route.destinationResult != null && (
          <div style={{ marginTop: 4 }}>
            <button className="link-btn" onClick={() => setShowResult((v) => !v)}>
              {showResult ? "hide raw result" : "raw result"}
            </button>
            {showResult && (
              <pre className="raw-json">
                {JSON.stringify(route.destinationResult, null, 2)}
              </pre>
            )}
          </div>
        )}
      </div>
      {correctable && (
        <button
          className="btn btn-small"
          style={
            route.decision === "ROUTED_WITH_FLAG"
              ? { color: "var(--mp-amber)", borderColor: "var(--mp-amber)" }
              : undefined
          }
          onClick={() => setCorrecting(true)}
        >
          {route.decision === "ROUTED_WITH_FLAG" ? "Correct this" : "This isn't right"}
        </button>
      )}
      {correcting && (
        <CorrectionPicker
          entryId={entryId}
          route={route}
          recipeAttached={recipeAttached}
          onClose={() => setCorrecting(false)}
        />
      )}
    </div>
  );
}

/* ---- clarification answer form (§5b) ----------------------------------------------- */

/**
 * Equal-weight option buttons (never pre-selected) — tapping one submits
 * immediately; the free-text alternative has its own send. ≥1 of the two is
 * required by the contract (the send button stays disabled until text exists).
 */
export function ClarificationAnswer({ query }: { query: ClarificationQueryDto }) {
  const [freeText, setFreeText] = useState("");
  return (
    <div>
      <div className="clar-options">
        {query.options.map((option) => (
          <button
            key={`${option.destination}-${option.snippet}`}
            className="btn btn-small"
            title={option.classifierJustification ?? undefined}
            onClick={() =>
              answerClarification(query.id, { selectedDestination: option.destination })
            }
          >
            <span style={{ fontWeight: 600 }}>{DESTINATION_LABEL[option.destination]}</span>
            <span className="clar-option-snippet"> · “{option.snippet}”</span>
          </button>
        ))}
      </div>
      <div style={{ display: "flex", gap: 8, marginTop: 10 }}>
        <input
          type="text"
          className="text-input"
          style={{ flex: 1 }}
          placeholder="…or tell me more in your own words"
          maxLength={4000}
          value={freeText}
          onChange={(e) => setFreeText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && freeText.trim()) {
              answerClarification(query.id, { userClarificationText: freeText });
              setFreeText("");
            }
          }}
          aria-label="Clarify in your own words"
        />
        <button
          className="btn btn-small"
          disabled={!freeText.trim()}
          onClick={() => {
            answerClarification(query.id, { userClarificationText: freeText });
            setFreeText("");
          }}
        >
          Send
        </button>
      </div>
    </div>
  );
}
