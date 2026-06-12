import { AdvisorPanel } from "../components/AdvisorPanel";
import { PageHeader } from "../components/PageHeader";
import { SwapLine } from "../components/SwapLine";
import { TierMark, TIER_INFO } from "../components/TierMark";
import { TintChip } from "../components/TintChip";
import {
  acceptRecipeChange,
  acceptSuggestion,
  answerClarification,
  markFeedbackCorrected,
  rejectRecipeChange,
  rejectSuggestion,
  tierFor,
  useStore,
} from "../mock/store";
import type { FeedbackEntry, FeedbackRoute } from "../mock/types";

function RouteLine({ route }: { route: FeedbackRoute }) {
  const tier = tierFor(route.conf);
  const info = TIER_INFO[tier];
  return (
    <div className="route-line">
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
          <div style={{ marginTop: 4 }}>
            <span className="mp-serif" style={{ fontSize: 16.5 }}>
              {route.question}
            </span>
            {route.answered && (
              <div className="route-answered">
                ✓ answered — {route.answered.toLowerCase()}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function FeedbackCard({ entry }: { entry: FeedbackEntry }) {
  return (
    <div className="mp-card feedback-card">
      <div className="feedback-card-head">
        <span className="mp-label">{entry.when}</span>
        {entry.corrected ? (
          <TintChip>correction recorded</TintChip>
        ) : (
          <button
            className="btn btn-small"
            onClick={() => markFeedbackCorrected(entry.id)}
          >
            This isn't right
          </button>
        )}
      </div>
      <div className="feedback-quote">“{entry.text}”</div>
      <div style={{ display: "grid", gap: 10, marginTop: 12 }}>
        {entry.routes.map((route, i) => (
          <RouteLine key={`${route.dest}-${i}`} route={route} />
        ))}
      </div>
    </div>
  );
}

export function Activity() {
  const suggestion = useStore((s) => s.planner.suggestions[0]);
  const recipes = useStore((s) => s.recipes);
  const activity = useStore((s) => s.activity);

  const recipeChanges = recipes.filter((r) => r.pendingChange !== null);
  // Top-3 pending changes: the plan suggestion first, then recipe changes.
  const recipeSlots = suggestion ? 2 : 3;

  return (
    <div>
      <PageHeader
        title="Activity"
        meta="Pending advisor changes, your feedback and how it was routed, and open questions"
      />

      <section aria-label="Pending changes">
        {!suggestion && recipeChanges.length === 0 && (
          <div className="page-loading">
            No pending changes — the advisor will raise suggestions here.
          </div>
        )}
        {suggestion && (
          <AdvisorPanel
            label="Re-optimisation suggested · plan"
            headerRight={`${suggestion.affectedSlotIds.length} future slot${
              suggestion.affectedSlotIds.length === 1 ? "" : "s"
            } affected · eaten and cooked meals stay pinned`}
            title={suggestion.summary}
            impact="accept writes a new draft generation for review on /plan (two-step)"
            acceptLabel="Accept changes"
            onAccept={() => acceptSuggestion(suggestion.id)}
            onDismiss={() => rejectSuggestion(suggestion.id)}
          >
            <div className="inline-note" style={{ marginTop: 10 }}>
              The concrete diff is only returned by the accept response — no
              preview is possible from the list contract (plan.md §8 Q2).
            </div>
          </AdvisorPanel>
        )}
        {recipeChanges.slice(0, recipeSlots).map((recipe) => {
          const change = recipe.pendingChange;
          if (!change) return null;
          return (
            <AdvisorPanel
              key={recipe.id}
              label={`Suggested change · ${recipe.name}`}
              headerRight={change.sub}
              title={change.title}
              acceptLabel="Accept"
              onAccept={() => acceptRecipeChange(recipe.id)}
              onDismiss={() => rejectRecipeChange(recipe.id)}
            >
              <div style={{ marginTop: 12 }}>
                <SwapLine from={change.from} to={change.to} />
              </div>
            </AdvisorPanel>
          );
        })}
      </section>

      <div className="activity-layout">
        <section aria-label="Feedback history">
          <div className="group-head">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Feedback history
            </span>
          </div>
          <div style={{ display: "grid", gap: 14, marginTop: 14 }}>
            {activity.feedback.map((entry) => (
              <FeedbackCard key={entry.id} entry={entry} />
            ))}
          </div>
          <div className="grocery-footnote" style={{ marginTop: 14 }}>
            ✓ routed silently · ? routed with a check-me · … needed you.
            Corrections teach the classifier.
          </div>
        </section>

        <section aria-label="Clarifications inbox">
          <div className="group-head">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Clarifications · {activity.clarifications.length} open
            </span>
          </div>
          <div style={{ display: "grid", gap: 14, marginTop: 14 }}>
            {activity.clarifications.length === 0 ? (
              <div className="page-loading" style={{ padding: "20px 0" }}>
                Nothing waiting on you.
              </div>
            ) : (
              activity.clarifications.map((clar) => (
                <div key={clar.id} className="mp-card feedback-card">
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <span className="advisor-dot" />
                    <span
                      className="mp-label"
                      style={{ color: "var(--mp-terra-dark)" }}
                    >
                      Needs you
                    </span>
                  </div>
                  <div style={{ marginTop: 8 }}>
                    <span className="mp-serif" style={{ fontSize: 19 }}>
                      {clar.question}
                    </span>
                  </div>
                  {clar.context && (
                    <div className="clar-context">from: “{clar.context}”</div>
                  )}
                  <div className="clar-options">
                    {clar.options.map((option) => (
                      <button
                        key={option}
                        className="btn btn-small"
                        onClick={() => answerClarification(clar.id, option)}
                      >
                        {option}
                      </button>
                    ))}
                  </div>
                </div>
              ))
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
