import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { PageHeader } from "../components/PageHeader";
import { TintChip } from "../components/TintChip";
import { useStore } from "../mock/store";
import type { QualityTier, Recipe } from "../mock/types";

const TIME_FILTERS = [20, 30, 45] as const;
const TIERS: QualityTier[] = [
  "user verified",
  "imported",
  "ai generated",
  "web discovered",
];

function TierBadge({ tier }: { tier: QualityTier }) {
  if (tier === "user verified") return <TintChip>User verified</TintChip>;
  return <span className="tier-badge">{tier}</span>;
}

function RecipeCard({ recipe }: { recipe: Recipe }) {
  return (
    <Link to={`/recipes/${recipe.id}`} className="recipe-card mp-card">
      <div className="recipe-photo">
        <img
          src={recipe.img}
          alt=""
          loading="lazy"
          onError={(e) => {
            e.currentTarget.style.display = "none";
          }}
        />
      </div>
      <div className="recipe-card-body">
        <div className="recipe-card-name">{recipe.name}</div>
        <div className="recipe-card-meta">
          {recipe.timeMin} min · serves {recipe.serves} · {recipe.cuisine}
        </div>
        <div className="recipe-card-foot">
          <TierBadge tier={recipe.tier} />
          <span className="recipe-card-taste">
            <span className="mp-num" style={{ fontSize: 22 }}>
              {recipe.taste}
            </span>
            <span className="recipe-card-taste-label">taste</span>
          </span>
        </div>
      </div>
    </Link>
  );
}

export function Recipes() {
  const recipes = useStore((s) => s.recipes);
  const [query, setQuery] = useState("");
  const [cuisine, setCuisine] = useState<string | null>(null);
  const [maxTime, setMaxTime] = useState<number | null>(null);
  const [tier, setTier] = useState<QualityTier | null>(null);

  const cuisines = useMemo(
    () => [...new Set(recipes.map((r) => r.cuisine))].sort(),
    [recipes],
  );

  const visible = recipes.filter((r) => {
    if (query && !r.name.toLowerCase().includes(query.trim().toLowerCase()))
      return false;
    if (cuisine && r.cuisine !== cuisine) return false;
    if (maxTime !== null && r.timeMin > maxTime) return false;
    if (tier && r.tier !== tier) return false;
    return true;
  });

  return (
    <div>
      <PageHeader
        title="Recipes"
        meta={`${recipes.length} in your catalogue · user and system recipes`}
      />

      <div className="recipe-filters">
        <input
          type="search"
          className="recipe-search"
          placeholder="Search recipes"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search recipes"
        />
        <div className="filter-row">
          <span className="mp-label">Cuisine</span>
          {cuisines.map((c) => (
            <button
              key={c}
              className={`filter-chip${cuisine === c ? " active" : ""}`}
              onClick={() => setCuisine(cuisine === c ? null : c)}
            >
              {c}
            </button>
          ))}
        </div>
        <div className="filter-row">
          <span className="mp-label">Max time</span>
          {TIME_FILTERS.map((t) => (
            <button
              key={t}
              className={`filter-chip${maxTime === t ? " active" : ""}`}
              onClick={() => setMaxTime(maxTime === t ? null : t)}
            >
              ≤ {t} min
            </button>
          ))}
          <span className="mp-label" style={{ marginLeft: 14 }}>
            Quality
          </span>
          {TIERS.map((t) => (
            <button
              key={t}
              className={`filter-chip${tier === t ? " active" : ""}`}
              onClick={() => setTier(tier === t ? null : t)}
            >
              {t}
            </button>
          ))}
        </div>
      </div>

      {visible.length === 0 ? (
        <div className="page-loading">
          No recipes match — try clearing a filter.
        </div>
      ) : (
        <div className="recipe-grid">
          {visible.map((r) => (
            <RecipeCard key={r.id} recipe={r} />
          ))}
        </div>
      )}
    </div>
  );
}
