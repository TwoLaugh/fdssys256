/**
 * Recipes library — rebuilt against the contract-complete page spec
 * (design/frontend/pages/recipes.md): browse/search both catalogues, get
 * recipes in (manual create · preview→confirm import), card-level catalogue
 * moves (promote / demote / archive / unarchive), and the §4c dedup dialog.
 *
 * NOTE: the shipped contract has NO GET /recipes list endpoint — the grid
 * renders from the mock catalogue and footnotes the gap (spec §8 Q1).
 */

import { useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Modal } from "../components/Modal";
import { PageHeader } from "../components/PageHeader";
import { computeRatingSummary } from "../mock/recipeLogic";
import { DEDUP_DEMO_URL } from "../mock/recipeSeed";
import {
  archiveRecipe,
  confirmImport,
  createRecipeManual,
  createVariantBranch,
  demoteRecipe,
  previewImportFromHtml,
  previewImportFromUrl,
  promoteRecipe,
  pushToast,
  unarchiveRecipe,
  useStore,
} from "../mock/store";
import type {
  CreateRecipeRequest,
  DataQuality,
  RecipeDto,
  RecipeImportPreview,
} from "../mock/types";
import {
  emptyRecipeRequest,
  metaLine,
  needsReviewCountOf,
  NutritionStatusNote,
  QualityBadge,
  QUALITY_LABEL,
  QUALITY_ORDER,
  RecipeForm,
} from "./recipes/shared";

const TIME_FILTERS = [20, 30, 45] as const;
const PAGE_SIZE = 20; // Spring page conventions (spec §3b)

type CatalogueFilter = "mine" | "pool" | "all";

/* ---- card ----------------------------------------------------------------------- */

function RecipeCard({
  recipe,
  taste,
  ratingCount,
  menuOpen,
  onToggleMenu,
  onAskDemote,
}: {
  recipe: RecipeDto;
  taste: number | null;
  ratingCount: number;
  menuOpen: boolean;
  onToggleMenu: () => void;
  onAskDemote: () => void;
}) {
  const archived = recipe.archivedAt != null;
  return (
    <div className={`recipe-card mp-card${archived ? " archived" : ""}`}>
      <Link to={`/recipes/${recipe.id}`} className="recipe-card-link">
        <div className="recipe-photo">
          {recipe.imageUrl && (
            <img
              src={recipe.imageUrl}
              alt=""
              loading="lazy"
              onError={(e) => {
                e.currentTarget.style.display = "none";
              }}
            />
          )}
        </div>
        <div className="recipe-card-body">
          <div className="recipe-card-name">{recipe.name}</div>
          <div className="recipe-card-meta">{metaLine(recipe)}</div>
          <div className="recipe-card-captions">
            {recipe.catalogue === "SYSTEM" && (
              <span className="pool-caption">from the pool</span>
            )}
            {archived && <span className="mp-chip muted">archived</span>}
            {recipe.forkedFromRecipeId && (
              <span className="pool-caption">forked</span>
            )}
            <NutritionStatusNote
              status={recipe.nutritionStatus}
              needsReview={needsReviewCountOf(recipe)}
            />
          </div>
          <div className="recipe-card-foot">
            <span style={{ display: "inline-flex", gap: 8, alignItems: "center" }}>
              <QualityBadge quality={recipe.dataQuality} />
              <span className="version-tag">v{recipe.currentVersion}</span>
            </span>
            <span
              className="recipe-card-taste"
              title={
                ratingCount > 0
                  ? `${ratingCount} rating${ratingCount === 1 ? "" : "s"}`
                  : "not rated yet"
              }
            >
              <span className="mp-num" style={{ fontSize: 22 }}>
                {taste == null ? "—" : Math.round(taste)}
              </span>
              <span className="recipe-card-taste-label">taste</span>
            </span>
          </div>
        </div>
      </Link>
      <div className="recipe-card-actions">
        {recipe.catalogue === "SYSTEM" && !archived && (
          <button
            className="btn btn-small btn-primary"
            onClick={() => promoteRecipe(recipe.id)}
          >
            Add to my library
          </button>
        )}
        {archived ? (
          <button className="btn btn-small" onClick={() => unarchiveRecipe(recipe.id)}>
            Unarchive
          </button>
        ) : (
          recipe.catalogue === "USER" && (
            <button className="btn btn-small" onClick={() => archiveRecipe(recipe.id)}>
              Archive
            </button>
          )
        )}
        <span className="suggest-anchor">
          <button
            className="btn btn-small"
            aria-label={`More actions for ${recipe.name}`}
            onClick={onToggleMenu}
          >
            ⋯
          </button>
          {menuOpen && (
            <div className="suggest-pop mp-card" role="menu" style={{ width: 230, left: "auto", right: 0 }}>
              {recipe.catalogue === "USER" ? (
                <button className="suggest-row" onClick={onAskDemote}>
                  Remove from my library
                </button>
              ) : (
                <button className="suggest-row" onClick={() => promoteRecipe(recipe.id)}>
                  Add to my library
                </button>
              )}
              {archived ? (
                <button className="suggest-row" onClick={() => unarchiveRecipe(recipe.id)}>
                  Unarchive
                </button>
              ) : (
                <button className="suggest-row" onClick={() => archiveRecipe(recipe.id)}>
                  Archive (idempotent)
                </button>
              )}
            </div>
          )}
        </span>
      </div>
    </div>
  );
}

/* ---- §4c dedup dialog -------------------------------------------------------------- */

export interface DuplicateContext {
  candidateRecipeId: string;
  ingredientOverlap: number;
  /** The body the user tried to save — reused for the variant branch. */
  req: CreateRecipeRequest;
  sourceUrl: string | null;
}

export function DedupDialog({
  ctx,
  onClose,
}: {
  ctx: DuplicateContext;
  onClose: () => void;
}) {
  const navigate = useNavigate();
  const candidate = useStore((s) =>
    s.recipes.find((r) => r.id === ctx.candidateRecipeId),
  );
  const candidateVersionId = candidate?.currentVersionBody?.id ?? null;

  const importAsVariant = () => {
    if (!candidate || !candidateVersionId) return;
    const host = ctx.sourceUrl
      ? new URL(ctx.sourceUrl).hostname.replace(/^www\./, "")
      : "manual";
    const name = host.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
    const branchId = createVariantBranch(candidate.id, {
      name: name || "variant",
      label: ctx.req.name,
      reason: `Imported as a variant of ${candidate.name} (≈${Math.round(ctx.ingredientOverlap * 100)}% ingredient overlap)`,
      branchPointVersionId: candidateVersionId,
      body: {
        ingredients: ctx.req.ingredients,
        method: ctx.req.method,
        metadata: ctx.req.metadata,
        tags: ctx.req.tags ?? null,
      },
      fingerprintOverride: null, // pipeline-only concept — no control (§4a)
    });
    if (branchId) {
      onClose();
      navigate(`/recipes/${candidate.id}`);
    }
  };

  return (
    <Modal label="Duplicate recipe found" onClose={onClose} wide>
      <div className="dialog-title">
        This looks similar to a recipe in your library
      </div>
      <div className="dialog-body">
        422 recipe-import-duplicate — ingredient overlap ≈
        {Math.round(ctx.ingredientOverlap * 100)}%. Merge, import as a variant,
        or import anyway?
      </div>
      {candidate && (
        <div className="mp-card dedup-candidate">
          <div className="recipe-card-name">{candidate.name}</div>
          <div className="recipe-card-meta">{metaLine(candidate)}</div>
          <div style={{ marginTop: 6 }}>
            <QualityBadge quality={candidate.dataQuality} />
          </div>
        </div>
      )}
      <div className="modal-actions" style={{ flexWrap: "wrap" }}>
        <button
          className="btn"
          disabled
          title="No merge endpoint in the shipped contract — v1 renders merge as open-existing-and-edit (spec §8 Q2)"
        >
          Merge
        </button>
        <button
          className="btn"
          disabled
          title="No override flag on ConfirmImportRequest — re-POSTing deterministically 422s again (backend gap, spec §8 Q2)"
        >
          Import anyway
        </button>
        <button className="btn" onClick={importAsVariant} disabled={!candidate}>
          Import as variant
        </button>
        <button
          className="btn btn-primary"
          onClick={() => {
            onClose();
            navigate(`/recipes/${ctx.candidateRecipeId}`);
          }}
        >
          Open existing
        </button>
      </div>
    </Modal>
  );
}

/* ---- import sheet (§4a/§4b) ----------------------------------------------------------- */

type ImportStage =
  | { step: "input" }
  | { step: "loading" }
  | { step: "failed"; reason: string; detail: string; url: string }
  | { step: "review"; preview: RecipeImportPreview };

const FAILURE_COPY = (reason: string, detail: string): string => {
  if (reason === "no_extractor_matched")
    return "Couldn't find a recipe on that page — enter it manually?";
  if (reason === "fetch_timeout" || reason === "fetch_io_error")
    return "Couldn't reach that page.";
  if (reason.startsWith("fetch_4xx_"))
    return `The site refused (HTTP ${reason.slice("fetch_4xx_".length)}) — the Browse & save tab usually beats bot-blocking.`;
  if (reason.startsWith("fetch_5xx_")) return "The site had an error.";
  if (reason === "oversize" || reason === "schema_mismatch")
    return "Page too large or malformed.";
  return detail; // open vocabulary — degrade to the detail text
};

function ImportSheet({
  onClose,
  onDuplicate,
  onManual,
}: {
  onClose: () => void;
  onDuplicate: (ctx: DuplicateContext) => void;
  onManual: (prefill?: CreateRecipeRequest) => void;
}) {
  const navigate = useNavigate();
  const [tab, setTab] = useState<"url" | "html">("url");
  const [url, setUrl] = useState("");
  const [html, setHtml] = useState("");
  const [stage, setStage] = useState<ImportStage>({ step: "input" });

  const runPreview = (fn: () => ReturnType<typeof previewImportFromUrl>) => {
    setStage({ step: "loading" });
    window.setTimeout(() => {
      const outcome = fn();
      if (outcome.kind === "failure") {
        setStage({
          step: "failed",
          reason: outcome.failureReason,
          detail: outcome.detail,
          url,
        });
      } else {
        setStage({ step: "review", preview: outcome.preview });
      }
    }, 900); // extraction can take seconds — non-transactional read (§4a)
  };

  const confirm = (preview: RecipeImportPreview) => (req: CreateRecipeRequest) => {
    // previewToken echoed; v1 flow is stateless — the confirm body is
    // authoritative, edits included (§4b).
    const outcome = confirmImport(req, preview.sourceUrl, preview.extractionMethod ?? null);
    if (outcome.kind === "duplicate") {
      onDuplicate({
        candidateRecipeId: outcome.hit.candidateRecipeId,
        ingredientOverlap: outcome.hit.ingredientOverlap,
        req,
        sourceUrl: preview.sourceUrl,
      });
      return;
    }
    onClose();
    navigate(`/recipes/${outcome.recipeId}`);
  };

  return (
    <Modal label="Import a recipe" onClose={onClose} wide>
      <div className="dialog-title">Import a recipe</div>
      {stage.step !== "review" && (
        <>
          <div className="nutri-tabs" role="tablist" aria-label="Import modes" style={{ marginTop: 8 }}>
            <button
              role="tab"
              aria-selected={tab === "url"}
              className={`filter-chip${tab === "url" ? " active" : ""}`}
              onClick={() => setTab("url")}
            >
              Paste a URL
            </button>
            <button
              role="tab"
              aria-selected={tab === "html"}
              className={`filter-chip${tab === "html" ? " active" : ""}`}
              onClick={() => setTab("html")}
            >
              Browse &amp; save
            </button>
          </div>
          {tab === "url" ? (
            <div style={{ marginTop: 14, display: "grid", gap: 10 }}>
              <input
                type="url"
                className="text-input"
                placeholder="https://…"
                value={url}
                maxLength={2048}
                aria-label="Recipe page URL"
                onChange={(e) => setUrl(e.target.value)}
              />
              <div className="inline-note">
                Demo fixtures: paste{" "}
                <button
                  className="link-btn"
                  onClick={() => setUrl(DEDUP_DEMO_URL)}
                >
                  the seeded duplicate URL
                </button>{" "}
                for the dedup dialog; URLs containing “timeout”, “blocked”,
                “broken” or “no-recipe” play the failure map (§4a).
              </div>
              <div className="modal-actions">
                <button className="btn" onClick={() => onManual()}>
                  Start from scratch
                </button>
                <button
                  className="btn btn-primary"
                  disabled={url.trim().length === 0 || stage.step === "loading"}
                  onClick={() => runPreview(() => previewImportFromUrl(url.trim()))}
                >
                  Fetch &amp; preview
                </button>
              </div>
            </div>
          ) : (
            <div style={{ marginTop: 14, display: "grid", gap: 10 }}>
              <div className="inline-note">
                The in-app browser captures{" "}
                <code>document.documentElement.outerHTML</code> on “Save
                recipe” — beats JS-rendered and bot-blocked pages. Paste any
                markup (≥40 chars) to simulate the capture.
              </div>
              <input
                type="url"
                className="text-input"
                placeholder="Page URL (provenance)"
                value={url}
                maxLength={2048}
                aria-label="Source URL for HTML import"
                onChange={(e) => setUrl(e.target.value)}
              />
              <textarea
                className="text-input"
                rows={4}
                placeholder="<html>…captured page markup…</html>"
                value={html}
                aria-label="Captured page HTML"
                onChange={(e) => setHtml(e.target.value)}
              />
              <div className="modal-actions">
                <button className="btn" onClick={() => onManual()}>
                  Start from scratch
                </button>
                <button
                  className="btn btn-primary"
                  disabled={url.trim().length === 0 || stage.step === "loading"}
                  onClick={() => runPreview(() => previewImportFromHtml(url.trim(), html))}
                >
                  Save recipe
                </button>
              </div>
            </div>
          )}
          {stage.step === "loading" && (
            <div className="gen-wait" style={{ padding: "26px 0 10px" }}>
              <span className="mp-serif" style={{ fontSize: 19 }}>
                Reading the page…
              </span>
              <div className="gen-wait-sub">
                extraction runs json-ld → microdata → known selectors
              </div>
            </div>
          )}
          {stage.step === "failed" && (
            <div style={{ marginTop: 16 }}>
              <div className="rf-errors" role="alert">
                {FAILURE_COPY(stage.reason, stage.detail)}
                <div style={{ marginTop: 4, fontWeight: 400 }}>
                  422 recipe-import-failure · {stage.reason} · {stage.detail}
                </div>
              </div>
              <div className="modal-actions">
                {stage.reason === "no_extractor_matched" ? (
                  <button
                    className="btn btn-primary"
                    onClick={() =>
                      onManual({
                        ...emptyRecipeRequest(),
                        description: stage.url,
                      })
                    }
                  >
                    Enter it manually
                  </button>
                ) : (
                  <button
                    className="btn btn-primary"
                    onClick={() => setStage({ step: "input" })}
                  >
                    Retry
                  </button>
                )}
              </div>
            </div>
          )}
        </>
      )}

      {stage.step === "review" && (
        <div style={{ marginTop: 8 }}>
          <div className="import-source-line">
            from {new URL(stage.preview.sourceUrl).hostname.replace(/^www\./, "")}
            {stage.preview.extractionMethod && (
              <span
                className="mp-chip"
                style={{ marginLeft: 10 }}
                title="read automatically from the page's recipe markup"
              >
                {stage.preview.extractionMethod}
              </span>
            )}
          </div>
          {stage.preview.validationWarnings.length > 0 && (
            <div className="import-warnings" role="alert">
              {stage.preview.validationWarnings.map((w) => (
                <div key={w}>⚠ {w}</div>
              ))}
            </div>
          )}
          {stage.preview.dedupCandidate && (
            <DedupPreWarning
              recipeId={stage.preview.dedupCandidate.recipeId}
              overlap={stage.preview.dedupCandidate.ingredientOverlap}
            />
          )}
          <div className="inline-note" style={{ margin: "10px 0 4px" }}>
            Review and edit before saving — your edits are authoritative on
            confirm (preview persisted nothing).
          </div>
          <RecipeForm
            initial={stage.preview.parsedRecipe}
            submitLabel="Save to library"
            onSubmit={confirm(stage.preview)}
            onCancel={onClose}
          />
        </div>
      )}
    </Modal>
  );
}

function DedupPreWarning({ recipeId, overlap }: { recipeId: string; overlap: number }) {
  const candidate = useStore((s) => s.recipes.find((r) => r.id === recipeId));
  return (
    <div className="dedup-prewarn">
      Looks ≈{Math.round(overlap * 100)}% similar to{" "}
      <Link to={`/recipes/${recipeId}`}>{candidate?.name ?? "a recipe you have"}</Link>{" "}
      — saving will raise the duplicate dialog (merge / variant / open existing).
    </div>
  );
}

/* ---- page ----------------------------------------------------------------------------- */

export function Recipes() {
  const recipes = useStore((s) => s.recipes);
  const ratings = useStore((s) => s.recipeData.ratings);
  const navigate = useNavigate();

  const [query, setQuery] = useState("");
  const [cuisine, setCuisine] = useState<string | null>(null);
  const [maxTime, setMaxTime] = useState<number | null>(null);
  const [minQuality, setMinQuality] = useState<DataQuality | null>(null);
  const [catalogue, setCatalogue] = useState<CatalogueFilter>("all");
  const [includeArchived, setIncludeArchived] = useState(false);
  const [page, setPage] = useState(1);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [demoteFor, setDemoteFor] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const [manualPrefill, setManualPrefill] = useState<CreateRecipeRequest | null>(null);
  const [duplicate, setDuplicate] = useState<DuplicateContext | null>(null);

  const cuisines = useMemo(
    () =>
      [
        ...new Set(
          recipes
            .map((r) => r.currentVersionBody?.metadata?.cuisine)
            .filter((c): c is string => c != null && c !== "—"),
        ),
      ].sort(),
    [recipes],
  );

  // Client-side filtering on RecipeSearchCriteriaDto-shaped controls (§3b);
  // the wire call doesn't exist yet (no GET /recipes — §8 Q1).
  const visible = recipes.filter((r) => {
    if (r.deletedAt) return false;
    if (!includeArchived && r.archivedAt) return false;
    if (catalogue === "mine" && r.catalogue !== "USER") return false;
    if (catalogue === "pool" && r.catalogue !== "SYSTEM") return false;
    if (query && !r.name.toLowerCase().includes(query.trim().toLowerCase()))
      return false;
    const m = r.currentVersionBody?.metadata;
    if (cuisine && m?.cuisine !== cuisine) return false;
    if (maxTime !== null && (m?.totalTimeMins ?? 0) > maxTime) return false;
    if (
      minQuality !== null &&
      QUALITY_ORDER.indexOf(r.dataQuality) < QUALITY_ORDER.indexOf(minQuality)
    )
      return false;
    return true;
  });
  const paged = visible.slice(0, page * PAGE_SIZE);

  const resetPage = () => setPage(1);
  const mineCount = recipes.filter((r) => r.catalogue === "USER" && !r.archivedAt && !r.deletedAt).length;
  const poolCount = recipes.filter((r) => r.catalogue === "SYSTEM" && !r.archivedAt && !r.deletedAt).length;

  const demoteTarget = demoteFor ? recipes.find((r) => r.id === demoteFor) : undefined;

  return (
    <div onClick={() => menuFor && setMenuFor(null)}>
      <PageHeader
        title="Recipes"
        meta={`${mineCount} in your library · ${poolCount} in the recipe pool`}
        actions={
          <>
            <button className="btn" onClick={() => setManualPrefill(emptyRecipeRequest())}>
              New recipe
            </button>
            <button className="btn btn-primary" onClick={() => setImporting(true)}>
              Import
            </button>
          </>
        }
      />

      <div className="recipe-filters">
        <input
          type="search"
          className="recipe-search"
          placeholder="Search recipes (namePattern)"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            resetPage();
          }}
          aria-label="Search recipes"
        />
        <div className="filter-row">
          <span className="mp-label">Catalogue</span>
          {(["mine", "pool", "all"] as const).map((c) => (
            <button
              key={c}
              className={`filter-chip${catalogue === c ? " active" : ""}`}
              onClick={() => {
                setCatalogue(c);
                resetPage();
              }}
            >
              {c === "mine" ? "My library" : c === "pool" ? "Recipe pool" : "All"}
            </button>
          ))}
          <button
            className={`filter-chip${includeArchived ? " active" : ""}`}
            style={{ marginLeft: 14 }}
            onClick={() => {
              setIncludeArchived((v) => !v);
              resetPage();
            }}
          >
            Show archived
          </button>
        </div>
        <div className="filter-row">
          <span className="mp-label">Cuisine</span>
          {cuisines.map((c) => (
            <button
              key={c}
              className={`filter-chip${cuisine === c ? " active" : ""}`}
              onClick={() => {
                setCuisine(cuisine === c ? null : c);
                resetPage();
              }}
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
              onClick={() => {
                setMaxTime(maxTime === t ? null : t);
                resetPage();
              }}
            >
              ≤ {t} min
            </button>
          ))}
          <span className="mp-label" style={{ marginLeft: 14 }} title="ordinal floor, not equality (minDataQuality)">
            Quality at least
          </span>
          {[...QUALITY_ORDER].reverse().map((q) => (
            <button
              key={q}
              className={`filter-chip${minQuality === q ? " active" : ""}`}
              onClick={() => {
                setMinQuality(minQuality === q ? null : q);
                resetPage();
              }}
            >
              {QUALITY_LABEL[q]}
            </button>
          ))}
        </div>
      </div>

      {paged.length === 0 ? (
        <div className="page-loading">No recipes match — try clearing a filter.</div>
      ) : (
        <div className="recipe-grid">
          {paged.map((r) => {
            const summary = computeRatingSummary(ratings[r.id] ?? []);
            return (
              <RecipeCard
                key={r.id}
                recipe={r}
                taste={summary.avgTaste ?? null}
                ratingCount={summary.count}
                menuOpen={menuFor === r.id}
                onToggleMenu={() => setMenuFor(menuFor === r.id ? null : r.id)}
                onAskDemote={() => {
                  setMenuFor(null);
                  setDemoteFor(r.id);
                }}
              />
            );
          })}
        </div>
      )}
      {visible.length > paged.length && (
        <div style={{ display: "flex", justifyContent: "center", marginTop: 16 }}>
          <button className="btn" onClick={() => setPage((p) => p + 1)}>
            Load more ({visible.length - paged.length} more)
          </button>
        </div>
      )}

      <div className="grocery-footnote" style={{ marginTop: 18 }}>
        (library read — backend gap, see spec: no GET /api/v1/recipes
        list/search endpoint shipped; this grid renders the mock catalogue
        against the intended RecipeSearchCriteriaDto controls)
      </div>

      {importing && (
        <ImportSheet
          onClose={() => setImporting(false)}
          onDuplicate={(ctx) => {
            setImporting(false);
            setDuplicate(ctx);
          }}
          onManual={(prefill) => {
            setImporting(false);
            setManualPrefill(prefill ?? emptyRecipeRequest());
          }}
        />
      )}

      {manualPrefill && (
        <Modal label="New recipe" onClose={() => setManualPrefill(null)} wide>
          <div className="dialog-title">New recipe</div>
          <div className="dialog-body">
            Saved as user-verified; nutrition is computed internally (external
            numbers are never trusted). Duplicate detection runs on save.
          </div>
          <RecipeForm
            initial={manualPrefill}
            submitLabel="Save recipe"
            onSubmit={(req) => {
              const outcome = createRecipeManual(req);
              if (outcome.kind === "duplicate") {
                setManualPrefill(null);
                setDuplicate({
                  candidateRecipeId: outcome.hit.candidateRecipeId,
                  ingredientOverlap: outcome.hit.ingredientOverlap,
                  req,
                  sourceUrl: null,
                });
                return;
              }
              setManualPrefill(null);
              navigate(`/recipes/${outcome.recipeId}`);
            }}
            onCancel={() => setManualPrefill(null)}
          />
        </Modal>
      )}

      {duplicate && (
        <DedupDialog ctx={duplicate} onClose={() => setDuplicate(null)} />
      )}

      {demoteTarget && (
        <Modal label="Remove from my library" onClose={() => setDemoteFor(null)}>
          <div className="dialog-title">Remove from my library?</div>
          <div className="dialog-body">
            {demoteTarget.name} stays in the recipe pool — your versions are
            preserved, and the planner can still draw on it.
          </div>
          <div className="modal-actions">
            <button className="btn" onClick={() => setDemoteFor(null)}>
              Cancel
            </button>
            <button
              className="btn btn-primary"
              onClick={() => {
                demoteRecipe(demoteTarget.id);
                setDemoteFor(null);
                pushToast(`${demoteTarget.name} moved to the recipe pool`);
              }}
            >
              Remove
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
