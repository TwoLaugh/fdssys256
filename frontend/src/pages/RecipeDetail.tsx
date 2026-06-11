import { Link, useParams } from "react-router-dom";
import { AdvisorCard } from "../components/AdvisorCard";
import { SegmentBar } from "../components/SegmentBar";
import { SwapLine } from "../components/SwapLine";
import { TintChip } from "../components/TintChip";
import {
  acceptRecipeChange,
  rejectRecipeChange,
  useStore,
} from "../mock/store";

export function RecipeDetail() {
  const { id } = useParams<{ id: string }>();
  const recipe = useStore((s) => s.recipes.find((r) => r.id === id));

  if (!recipe) {
    return (
      <div>
        <Link to="/recipes" className="back-link">
          ← Recipes
        </Link>
        <div className="page-loading">Recipe not found.</div>
      </div>
    );
  }

  return (
    <div>
      <div className="detail-topbar">
        <Link to="/recipes" className="back-link">
          ← Recipes
        </Link>
        <div style={{ display: "flex", gap: 10 }}>
          <button className="btn" disabled title="Coming with feedback wiring">
            Give feedback
          </button>
          <button className="btn" disabled title="Coming with live wiring">
            Edit
          </button>
        </div>
      </div>

      <div className="detail-hero">
        <div className="detail-photo">
          <img
            src={recipe.img}
            alt={recipe.name}
            onError={(e) => {
              e.currentTarget.style.display = "none";
            }}
          />
        </div>
        <div>
          <h1 className="page-title" style={{ fontSize: 30 }}>
            {recipe.name}
          </h1>
          <div className="page-meta">{recipe.source}</div>
          <div className="detail-chips">
            <span className="detail-chip">{recipe.timeMin} min</span>
            <span className="detail-chip">Serves {recipe.serves}</span>
            <span className="detail-chip">{recipe.cuisine}</span>
            {recipe.tier === "user verified" ? (
              <TintChip>User verified</TintChip>
            ) : (
              <span className="tier-badge">{recipe.tier}</span>
            )}
          </div>
          <div className="detail-ratings">
            {recipe.ratings.map((r) => (
              <div key={r.label}>
                <span className="mp-label">{r.label}</span>
                <div style={{ margin: "7px 0 8px" }}>
                  <span className="mp-num" style={{ fontSize: 26 }}>
                    {r.val}
                  </span>
                </div>
                <SegmentBar pct={r.val / 100} width={110} />
              </div>
            ))}
          </div>
          <div className="detail-pills">
            {recipe.nutrition.map((n) => (
              <span key={n} className="nutrition-pill">
                {n}
              </span>
            ))}
            <span className="nutrition-pill-note">per serving</span>
          </div>
        </div>
      </div>

      {recipe.pendingChange && (
        <AdvisorCard
          label="Suggested change · from your feedback"
          title={recipe.pendingChange.title}
          titleSize={20}
          sub={recipe.pendingChange.sub}
          actions={
            <>
              <button
                className="btn"
                onClick={() => rejectRecipeChange(recipe.id)}
              >
                Reject
              </button>
              <button
                className="btn btn-primary"
                onClick={() => acceptRecipeChange(recipe.id)}
              >
                Accept
              </button>
            </>
          }
        >
          <div style={{ marginTop: 6 }}>
            <SwapLine
              from={recipe.pendingChange.from}
              to={recipe.pendingChange.to}
            />
          </div>
        </AdvisorCard>
      )}

      <div className="detail-columns">
        <div className="mp-card detail-card">
          <div className="detail-card-head">
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Ingredients
            </span>
          </div>
          {recipe.ingredients.map((it) => (
            <div key={it.n} className="ingredient-row">
              <span style={{ fontSize: 14, minWidth: 0 }}>
                {it.n}
                {it.swap && (
                  <span style={{ marginLeft: 9 }}>
                    <TintChip>{it.swap}</TintChip>
                  </span>
                )}
              </span>
              <span className="ingredient-qty">{it.q}</span>
            </div>
          ))}
          {recipe.moreIngredients && (
            <div className="detail-more">{recipe.moreIngredients}</div>
          )}
        </div>
        <div className="mp-card detail-card">
          <div className="detail-card-head" style={{ marginBottom: 12 }}>
            <span className="mp-label" style={{ color: "var(--mp-ink)" }}>
              Method
            </span>
          </div>
          <div style={{ display: "grid", gap: 12 }}>
            {recipe.steps.map((step, i) => (
              <div key={step} className="method-step">
                <span
                  className="mp-num"
                  style={{ fontSize: 14, color: "var(--mp-terra)" }}
                >
                  {String(i + 1).padStart(2, "0")}
                </span>
                <span style={{ minWidth: 0 }}>{step}</span>
              </div>
            ))}
          </div>
          {recipe.moreSteps && (
            <div className="detail-more">{recipe.moreSteps}</div>
          )}
        </div>
      </div>

      <div className="versions-strip">
        <span className="mp-label">Versions</span>
        {recipe.versions.map((v, i) =>
          i === 0 ? (
            <span key={v} className="version-current">
              {v}
            </span>
          ) : (
            <span key={v} style={{ color: "var(--mp-muted)" }}>
              {v}
            </span>
          ),
        )}
      </div>
    </div>
  );
}
