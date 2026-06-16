import { useState } from "react";
import { Link } from "react-router-dom";
import {
  ClarificationAnswer,
  RouteLine,
  SubmissionChip,
} from "../components/FeedbackBits";
import { Modal } from "../components/Modal";
import { PageHeader } from "../components/PageHeader";
import { TintChip } from "../components/TintChip";
import {
  acceptPendingChange,
  diffFromProposed,
  recipeName,
  rejectPendingChange,
  requestComposePrefill,
  useStore,
} from "../mock/store";
import type {
  ClarificationQueryDto,
  ClarificationStatus,
  FeedbackEntryDto,
  PendingChangeDto,
  PendingChangeListItemDto,
  RecipeDiffDto,
  UiContextDto,
} from "../mock/types";
import { DiffView, shortWhen } from "./recipes/shared";

// "Now" in epoch-ms — real clock in live mode (see src/live/dates.ts).
import { MOCK_NOW_MS } from "../live/dates";

const DIMENSION_LABEL: Record<string, string> = {
  SALT_LEVEL: "Salt level",
  PROTEIN: "Protein",
  METHOD_SIMPLIFICATION: "Simpler method",
  PORTION_SIZE: "Portion size",
  FLAVOUR_BALANCE: "Flavour balance",
  ACID_BALANCE: "Acid balance",
  TEXTURE: "Texture",
  COOKING_TIME: "Cooking time",
  SUBSTITUTION_PROMOTION: "Substitution",
  GENERAL: "General",
};

const SCREEN_LABEL: Record<UiContextDto["screen"], string> = {
  RECIPE_DETAIL: "recipe",
  PLAN_MEAL_DETAIL: "plan meal",
  PLAN_VIEW: "plan",
  GROCERY: "groceries",
  NUTRITION_DASHBOARD: "nutrition",
  SETTINGS: "settings",
  GENERAL: "general",
};

function hoursUntil(iso: string): number {
  return (Date.parse(iso) - MOCK_NOW_MS) / 3_600_000;
}

function expiresLabel(iso: string): string {
  const hours = hoursUntil(iso);
  if (hours <= 0) return "expired";
  if (hours < 48) return `expires in ${Math.max(1, Math.round(hours))} h`;
  return `expires in ${Math.round(hours / 24)} days`;
}

/* ================= pending changes (§3) ============================================ */

function ImpactMeter({ score }: { score: number }) {
  return (
    <span className="impact-meter" title={`impact ${score.toFixed(2)}`}>
      <span className="impact-meter-fill" style={{ width: `${Math.round(score * 100)}%` }} />
    </span>
  );
}

function kindChip(detail: PendingChangeDto): string {
  return detail.proposedClassification === "VERSION"
    ? "updates the recipe"
    : detail.proposedClassification === "BRANCH"
      ? "new variant alongside"
      : detail.proposedClassification === "SUBSTITUTION"
        ? "ingredient swap"
        : "no change";
}

/** Status-less per-recipe history drawer (#5): the list projection carries no
 *  status/resolvedAt — outcomes only appear on a per-row detail fetch (§3d). */
function PendingHistoryDrawer({ recipeId }: { recipeId: string }) {
  const history = useStore((s) => s.adaptation.historyByRecipe[recipeId] ?? []);
  const [openIds, setOpenIds] = useState<string[]>([]);
  if (history.length === 0) {
    return <div className="inline-note">No earlier proposals for this recipe.</div>;
  }
  return (
    <div style={{ marginTop: 8 }}>
      {history.map((h) => {
        const expanded = openIds.includes(h.id);
        return (
          <div key={h.id} className="history-row" style={{ marginTop: 6 }}>
            <div style={{ minWidth: 0 }}>
              <div className="history-query">
                {DIMENSION_LABEL[h.changeDimension] ?? h.changeDimension}
              </div>
              <div className="history-meta">
                proposed {shortWhen(h.createdAt)}
                {Date.parse(h.expiresAt) < MOCK_NOW_MS && ` · expired ${shortWhen(h.expiresAt)}`}
              </div>
            </div>
            {expanded ? (
              /* Outcome comes from the by-id fetch (#2), not the list row. */
              <span className={`mp-chip${h.status === "ACCEPTED" || h.status === "MODIFIED" ? "" : " muted"}`}>
                {h.status.toLowerCase()}
                {h.resolvedAt ? ` · ${shortWhen(h.resolvedAt)}` : ""}
              </span>
            ) : (
              <button
                className="btn btn-small"
                onClick={() => setOpenIds((xs) => [...xs, h.id])}
              >
                Outcome (fetch)
              </button>
            )}
          </div>
        );
      })}
      <div className="grocery-footnote" style={{ marginTop: 8 }}>
        History rows carry no status in the list contract — outcomes need a
        per-row fetch (spec §8 Q1; backend ticket candidate).
      </div>
    </div>
  );
}

function PendingChangeCard({ listItem }: { listItem: PendingChangeListItemDto }) {
  const recipes = useStore((s) => s.recipes);
  const [expanded, setExpanded] = useState(false);
  // §3b: the card expand IS the detail fetch — accept is impossible from the
  // list row (no optimisticVersion, no diff; spec §8 Q1).
  const detail = useStore((s) =>
    expanded ? s.adaptation.detailById[listItem.id] : undefined,
  );
  const [modify, setModify] = useState(false);
  const [editedQty, setEditedQty] = useState("");
  const [rejecting, setRejecting] = useState(false);
  const [reasonNote, setReasonNote] = useState("");
  const [showHistory, setShowHistory] = useState(false);
  const [showRawDiff, setShowRawDiff] = useState(false);

  const name = recipeName(recipes, listItem.recipeId);
  const recipe = recipes.find((r) => r.id === listItem.recipeId);
  const baseDiff = detail ? diffFromProposed(detail) : null;
  const expiringSoon = hoursUntil(listItem.expiresAt) < 48;

  const buildUserEdits = (): RecipeDiffDto | null => {
    if (!baseDiff || !modify) return null;
    const qty = Number(editedQty);
    if (!Number.isFinite(qty)) return null;
    return {
      ...baseDiff,
      ingredientChanges: baseDiff.ingredientChanges.map((ch, i) =>
        i === 0 && ch.to ? { ...ch, to: { ...ch.to, quantity: qty } } : ch,
      ),
    };
  };

  return (
    <div className="advisor-panel mp-card">
      <div className="advisor-panel-head">
        <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
          <span className="advisor-dot" />
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            Suggested change ·{" "}
            <Link to={`/recipes/${listItem.recipeId}`} className="attention-link">
              {name}
            </Link>
          </span>
          <span className="mp-chip muted">
            {DIMENSION_LABEL[listItem.changeDimension] ?? listItem.changeDimension}
          </span>
        </div>
        <span className="advisor-panel-note">
          confidence {listItem.confidence.toFixed(2)} · impact{" "}
          <ImpactMeter score={listItem.impactScore} />
          {" · "}
          <span style={expiringSoon ? { color: "var(--mp-amber)", fontWeight: 600 } : undefined}>
            {expiresLabel(listItem.expiresAt)}
          </span>
        </span>
      </div>
      <div style={{ marginTop: 8 }}>
        <span className="mp-serif" style={{ fontSize: 21 }}>
          {listItem.reasoningPreview ??
            `${DIMENSION_LABEL[listItem.changeDimension] ?? listItem.changeDimension} change suggested`}
        </span>
      </div>
      <div className="history-meta" style={{ marginTop: 4 }}>
        proposed {shortWhen(listItem.createdAt)}
      </div>

      {!expanded ? (
        <div style={{ marginTop: 12 }}>
          <button className="btn btn-small" onClick={() => setExpanded(true)}>
            Review (fetch detail)
          </button>
        </div>
      ) : detail ? (
        <div style={{ marginTop: 10 }}>
          <div className="dialog-body" style={{ marginTop: 0 }}>
            {detail.reasoning}
          </div>
          {detail.nutritionalNotes && (
            <div className="inline-note" style={{ marginTop: 6 }}>
              {detail.nutritionalNotes}
            </div>
          )}
          <div style={{ display: "flex", gap: 8, marginTop: 8, flexWrap: "wrap" }}>
            <span className="mp-chip">{kindChip(detail)}</span>
            {recipe && (
              <span className="mp-chip muted">proposed against v{recipe.currentVersion}</span>
            )}
            {detail.status !== "PENDING" && (
              <span className="mp-chip muted">
                {detail.status.toLowerCase()}
                {detail.resolvedAt ? ` · ${shortWhen(detail.resolvedAt)}` : ""}
              </span>
            )}
            {detail.supersededBy && (
              <span className="mp-chip muted">replaced by a newer suggestion</span>
            )}
            {detail.acceptedVersionId && (
              <Link to={`/recipes/${detail.recipeId}`} className="mp-chip" style={{ textDecoration: "none" }}>
                view the new version
              </Link>
            )}
          </div>
          {baseDiff ? (
            <div style={{ marginTop: 10 }}>
              {/* Original-red / replacement-green per the HLD approval UX. */}
              <DiffView diff={baseDiff} />
            </div>
          ) : (
            <div style={{ marginTop: 10 }}>
              <button className="link-btn" onClick={() => setShowRawDiff((v) => !v)}>
                {showRawDiff ? "hide raw diff" : "raw diff (unknown shape)"}
              </button>
              {showRawDiff && (
                <pre className="raw-json">{JSON.stringify(detail.proposedDiff, null, 2)}</pre>
              )}
            </div>
          )}
          {detail.userEdits != null && (
            <div className="inline-note" style={{ marginTop: 8 }}>
              you modified this before accepting
            </div>
          )}
          {modify && baseDiff?.ingredientChanges[0]?.to && (
            <div className="rf-grid2" style={{ marginTop: 10, maxWidth: 360 }}>
              <label>
                <span className="field-label">
                  Modified quantity ({baseDiff.ingredientChanges[0].to.displayName})
                </span>
                <input
                  type="number"
                  className="text-input"
                  value={editedQty}
                  aria-label="Modified proposal quantity"
                  onChange={(e) => setEditedQty(e.target.value)}
                />
              </label>
            </div>
          )}
          {detail.status === "PENDING" && (
            <div className="advisor-panel-footer">
              <span className="advisor-panel-impact">
                accept carries expectedOptimisticVersion {detail.optimisticVersion} from
                this detail fetch — never from the list row (spec §8 Q1)
              </span>
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                {baseDiff?.ingredientChanges[0]?.to && (
                  <button
                    className="btn btn-small"
                    onClick={() => {
                      setModify((v) => !v);
                      setEditedQty(String(baseDiff.ingredientChanges[0]?.to?.quantity ?? ""));
                    }}
                  >
                    {modify ? "Accept as proposed instead" : "Modify before accepting"}
                  </button>
                )}
                <button className="btn btn-small" onClick={() => setRejecting(true)}>
                  Dismiss
                </button>
                <button
                  className="btn btn-small btn-primary"
                  onClick={() =>
                    acceptPendingChange(detail.id, buildUserEdits(), detail.optimisticVersion)
                  }
                >
                  {modify ? "Accept with edits" : "Accept"}
                </button>
              </div>
            </div>
          )}
        </div>
      ) : null}

      <div style={{ marginTop: 12 }}>
        <button className="btn btn-small" onClick={() => setShowHistory((v) => !v)}>
          {showHistory ? "Hide history for this recipe" : "History for this recipe"}
        </button>
        {showHistory && <PendingHistoryDrawer recipeId={listItem.recipeId} />}
      </div>

      {rejecting && (
        <Modal label="Dismiss suggestion" onClose={() => setRejecting(false)}>
          <div className="dialog-title">Dismiss this suggestion?</div>
          <div className="dialog-body">
            Optional note (≤200) — helps the advisor learn.
          </div>
          <input
            type="text"
            className="text-input"
            style={{ width: "100%" }}
            maxLength={200}
            value={reasonNote}
            aria-label="Rejection note"
            onChange={(e) => setReasonNote(e.target.value)}
          />
          <div className="modal-actions">
            <button className="btn" onClick={() => setRejecting(false)}>
              Cancel
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                rejectPendingChange(listItem.id, reasonNote || undefined);
                setRejecting(false);
              }}
            >
              Dismiss
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

/* ================= feedback history (§4) =========================================== */

function ContextChip({ context }: { context: UiContextDto }) {
  const label = SCREEN_LABEL[context.screen];
  if (context.recipeId) {
    return (
      <Link to={`/recipes/${context.recipeId}`} className="mp-chip muted" style={{ textDecoration: "none" }}>
        {label} ↗
      </Link>
    );
  }
  if (context.planId) {
    return (
      <Link to="/plan" className="mp-chip muted" style={{ textDecoration: "none" }}>
        {label} ↗
      </Link>
    );
  }
  return <span className="mp-chip muted">{label}</span>;
}

function FeedbackCard({ entry }: { entry: FeedbackEntryDto }) {
  return (
    <div className="mp-card feedback-card" id={entry.id}>
      <div className="feedback-card-head">
        <span className="mp-label">{shortWhen(entry.createdAt)}</span>
        <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
          {entry.classificationAttempts > 1 && (
            <span className="route-conf" title="After ~3 rounds, consider submitting fresh feedback">
              attempt {entry.classificationAttempts}
            </span>
          )}
          <ContextChip context={entry.context} />
          <SubmissionChip status={entry.submissionStatus} />
        </div>
      </div>
      <div className="feedback-quote">“{entry.text}”</div>
      {entry.submissionStatus === "CLARIFICATION_PENDING" &&
        entry.pendingClarificationQueryId && (
          <div className="inline-note" style={{ marginTop: 10 }}>
            Paused — no partial routing while a question is open.{" "}
            <a href={`#${entry.pendingClarificationQueryId}`}>
              Answer it in the inbox →
            </a>
          </div>
        )}
      {entry.submissionStatus === "FAILED" && entry.routes.length === 0 && (
        <div className="inline-note" style={{ marginTop: 10 }}>
          The classifier gave up on this one — its question expired unanswered.
        </div>
      )}
      {entry.routes.length > 0 && (
        <div style={{ display: "grid", gap: 10, marginTop: 12 }}>
          {entry.routes.map((route) => (
            <RouteLine
              key={route.id}
              entryId={entry.id}
              route={route}
              recipeAttached={entry.context.recipeId != null}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function CorrectionsLog() {
  const corrections = useStore((s) => s.activity.corrections);
  if (corrections.length === 0) return null;
  return (
    <details className="micros-details" style={{ marginTop: 14 }}>
      <summary>Corrections log ({corrections.length})</summary>
      <div style={{ display: "grid", gap: 8, marginTop: 10 }}>
        {corrections.map((c) => (
          <div key={c.id} className="history-row">
            <div style={{ minWidth: 0 }}>
              <div className="history-query">
                <span className="diff-from">{c.originalDestination.toLowerCase()}</span>
                {" → "}
                <strong>{c.correctedDestination.toLowerCase()}</strong>
                <span className="route-conf" style={{ marginLeft: 8 }}>
                  was {c.originalConfidence.toFixed(2)} confident
                </span>
              </div>
              {c.userCorrectionNote && (
                <div className="history-meta" style={{ fontStyle: "italic" }}>
                  “{c.userCorrectionNote}”
                </div>
              )}
              <div className="history-meta">{shortWhen(c.occurredAt)}</div>
            </div>
            {c.replayStatus === "APPLIED" ? (
              <TintChip>applied ✓</TintChip>
            ) : c.replayStatus === "PENDING_REPLAY" ? (
              <span className="mp-chip muted">replaying…</span>
            ) : c.replayStatus === "DESTINATION_REJECTED" ? (
              <span className="mp-chip amber" title="The new destination couldn't use it">
                destination rejected
              </span>
            ) : (
              <span className="mp-chip" style={{ color: "var(--mp-red)", borderColor: "var(--mp-red)" }}>
                failed
              </span>
            )}
          </div>
        ))}
      </div>
      <div className="grocery-footnote" style={{ marginTop: 8 }}>
        Rows carry no feedback text in the contract (spec §8 Q5) — follow the
        entry link by date above.
      </div>
    </details>
  );
}

/* ================= clarifications inbox (§5) ======================================== */

function ClarificationCard({ query }: { query: ClarificationQueryDto }) {
  // The DTO carries no excerpt — one #7 call per visible card (spec §8 Q5).
  const parentText = useStore(
    (s) => s.activity.feedback.find((f) => f.id === query.feedbackEntryId)?.text,
  );
  const hours = hoursUntil(query.expiresAt);

  return (
    <div className="mp-card feedback-card" id={query.id}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, justifyContent: "space-between" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <span className="advisor-dot" />
          <span className="mp-label" style={{ color: "var(--mp-terra-dark)" }}>
            {query.status === "PENDING" ? "Needs you" : query.status.toLowerCase()}
          </span>
        </div>
        {query.status === "PENDING" ? (
          <span
            className="route-conf"
            style={hours < 24 ? { color: "var(--mp-amber)", fontWeight: 600 } : undefined}
          >
            {expiresLabel(query.expiresAt)}
          </span>
        ) : query.status === "ANSWERED" ? (
          <TintChip>answered ✓</TintChip>
        ) : (
          <span className="mp-chip muted">expired</span>
        )}
      </div>
      <div style={{ marginTop: 8 }}>
        <span className="mp-serif" style={{ fontSize: 19 }}>
          {query.questionText}
        </span>
      </div>
      {parentText && <div className="clar-context">from: “{parentText}”</div>}
      <div className="history-meta" style={{ marginTop: 4 }}>
        asked {shortWhen(query.createdAt)}
      </div>
      {query.status === "PENDING" && <ClarificationAnswer query={query} />}
      {query.status === "EXPIRED" && (
        <div style={{ marginTop: 10 }}>
          <button
            className="btn btn-small"
            onClick={() => parentText && requestComposePrefill(parentText)}
            disabled={!parentText}
          >
            This conversation expired — re-submit your feedback
          </button>
        </div>
      )}
    </div>
  );
}

/* ================= the page ========================================================= */

const FEEDBACK_PAGE_SIZE = 4;

export function Activity() {
  const pendingChanges = useStore((s) => s.adaptation.pendingChanges);
  const planSuggestions = useStore((s) => s.planner.suggestions);
  const feedback = useStore((s) => s.activity.feedback);
  const clarifications = useStore((s) => s.activity.clarifications);

  const [feedbackShown, setFeedbackShown] = useState(FEEDBACK_PAGE_SIZE);
  const [inboxFilter, setInboxFilter] = useState<ClarificationStatus>("PENDING");

  const filteredClarifications = clarifications.filter((c) => c.status === inboxFilter);
  const pendingCount = clarifications.filter((c) => c.status === "PENDING").length;
  const hasNonTerminal = feedback.some(
    (f) => f.submissionStatus === "RECEIVED" || f.submissionStatus === "CLASSIFYING",
  );

  return (
    <div>
      <PageHeader
        title="Activity"
        meta="Pending advisor changes, your feedback and how it was routed, and open questions"
      />

      <section aria-label="Pending changes">
        {planSuggestions.length > 0 && (
          <div className="inline-note" style={{ marginTop: 4 }}>
            A plan re-optimisation is also waiting —{" "}
            <Link to="/plan">review it on the plan page</Link> (planner surface,
            not an adaptation pending change).
          </div>
        )}
        {pendingChanges.length === 0 ? (
          <div className="page-loading">
            No pending changes — the advisor will raise suggestions here.
          </div>
        ) : (
          <>
            {/* Top-3, server-ranked; the cap is a ceiling, not a floor (§3a). */}
            {pendingChanges.slice(0, 3).map((change) => (
              <PendingChangeCard key={change.id} listItem={change} />
            ))}
            <div className="grocery-footnote" style={{ marginTop: 10 }}>
              Ranked by impact × confidence · proposals expire after 14 days ·
              a newer same-dimension proposal supersedes the old one.
            </div>
          </>
        )}
      </section>

      <div className="activity-layout">
        <section aria-label="Feedback history">
          <div className="group-head">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Feedback history
            </span>
            {hasNonTerminal && (
              <span className="route-conf">polling while classification runs…</span>
            )}
          </div>
          <div style={{ display: "grid", gap: 14, marginTop: 14 }}>
            {feedback.length === 0 ? (
              <div className="page-loading" style={{ padding: "20px 0" }}>
                Nothing yet — the ✎ button on any page files feedback here.
              </div>
            ) : (
              feedback
                .slice(0, feedbackShown)
                .map((entry) => <FeedbackCard key={entry.id} entry={entry} />)
            )}
          </div>
          {feedback.length > feedbackShown && (
            <div style={{ marginTop: 12 }}>
              <button
                className="btn btn-small"
                onClick={() => setFeedbackShown((n) => n + FEEDBACK_PAGE_SIZE)}
              >
                Earlier feedback ({feedback.length - feedbackShown} more)
              </button>
            </div>
          )}
          <CorrectionsLog />
          <div className="grocery-footnote" style={{ marginTop: 14 }}>
            ✓ routed silently · ? routed with a check-me · … needed you. Tier
            marks come from the server's routing decision, not re-derived
            confidence. Corrections teach the classifier.
          </div>
        </section>

        <section aria-label="Clarifications inbox">
          <div className="group-head">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Clarifications · {pendingCount} open
            </span>
          </div>
          <div className="filter-row" style={{ marginTop: 10 }}>
            {(["PENDING", "ANSWERED", "EXPIRED"] as const).map((f) => (
              <button
                key={f}
                className={`filter-chip${inboxFilter === f ? " active" : ""}`}
                onClick={() => setInboxFilter(f)}
              >
                {f.toLowerCase()} ·{" "}
                {clarifications.filter((c) => c.status === f).length}
              </button>
            ))}
          </div>
          <div style={{ display: "grid", gap: 14, marginTop: 14 }}>
            {filteredClarifications.length === 0 ? (
              <div className="page-loading" style={{ padding: "20px 0" }}>
                {inboxFilter === "PENDING"
                  ? "Nothing waiting on you."
                  : `No ${inboxFilter.toLowerCase()} questions.`}
              </div>
            ) : (
              filteredClarifications.map((query) => (
                <ClarificationCard key={query.id} query={query} />
              ))
            )}
          </div>
          {inboxFilter === "PENDING" && filteredClarifications.length > 0 && (
            <div className="grocery-footnote" style={{ marginTop: 12 }}>
              Answering queues a re-classification — the entry re-enters the
              feed as “working…” until it routes.
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
