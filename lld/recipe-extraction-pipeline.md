# Recipe Extraction Pipeline — LLD

*Shared engineering for turning a URL (or an HTML blob) into a structured `ParsedRecipe`. Powers both user-driven import (Paprika-style) and autonomous discovery.*

## Scope

The extraction pipeline is the engineering core that two consumers share:

- **User-driven import** (recipe module's `importFromUrl` / `importFromHtml`) — one URL at a time, user reviews and edits before save, lands in user catalogue.
- **Autonomous discovery** (discovery module's `DiscoveryJobRunner`) — many URLs from search or curated sources, no per-recipe review, lands in system catalogue, hard-constraint filtering applied before save.

Both call into the **same `RecipeExtractionService`** with the same input contract and get the same `ParsedRecipe` shape back. This LLD specifies that pipeline.

The pipeline lives in **`com.example.mealprep.recipe.extraction`** (sub-package of the recipe module). The recipe module's facade re-exports `RecipeExtractionService` so the discovery module can inject it without depending on the recipe module's catalogue internals.

This doc does **not** cover:
- The user-facing import UX (Paprika-style in-app browser, paste-URL flow) — Figma phase
- The discovery scheduling and source-rotation logic ([discovery.md](discovery.md))
- The recipe catalogue / write-API ([recipe.md](recipe.md))

---

## Two input modes

```java
public sealed interface ExtractionInput {
    UUID traceId();

    record FromUrl(String url, UUID traceId) implements ExtractionInput {}
    record FromHtml(String url, String html, UUID traceId) implements ExtractionInput {}
}
```

**`FromUrl`** — server fetches the page itself. The standard path for autonomous discovery and for user-driven imports where the frontend just hands us a URL (e.g. user pasted a link from their phone's browser).

**`FromHtml`** — frontend supplies pre-fetched HTML alongside the source URL (used for provenance / audit / dedup). The Paprika-style in-app-browser path: when the user hits "Save Recipe" on a page, the frontend extracts `document.documentElement.outerHTML` from its webview and POSTs both the URL and the HTML. This lets us extract from JS-rendered SPAs, paywalled pages the user has logged into, and pages that block our user-agent server-side.

Both modes feed the same five-layer extraction stack starting at Layer 1.

---

## The five-layer extraction stack

```
                    ┌────────────────────┐
   ExtractionInput  │   Layer 1: JSON-LD │  ~80% of major recipe sites
                    │   schema.org/Recipe│  (BBC Food, Serious Eats, NYT, AllRecipes, ...)
                    └─────────┬──────────┘
                              │ miss
                              ▼
                    ┌────────────────────┐
                    │ Layer 2: h-recipe  │  microformats — older food blogs
                    │ microformats        │
                    └─────────┬──────────┘
                              │ miss
                              ▼
                    ┌────────────────────┐
                    │ Layer 3: per-site  │  registered post-processor or extractor
                    │ extractor registry │  per domain
                    └─────────┬──────────┘
                              │ miss / ambiguous
                              ▼
                    ┌────────────────────┐
                    │ Layer 4: AI HTML   │  cheap-tier LLM with prompt caching
                    │ extraction          │
                    └─────────┬──────────┘
                              │
                              ▼
                    ┌────────────────────┐
                    │ Layer 5: Validator │  sanity check; route success/failure
                    └─────────┬──────────┘
                              │
                              ▼
                       ParsedRecipe
                  + ExtractionProvenance
                       (which layer won)
```

The first layer that succeeds wins — subsequent layers are not run. Each layer that's tried is logged in `ExtractionProvenance` for debugging and per-domain reliability tracking.

Layers 1-3 are pure code — no AI cost. Layer 4 is the only AI call, and only when needed. Most extractions complete at Layer 1 with zero LLM tokens.

### Layer 1 — JSON-LD (`schema.org/Recipe`)

The dominant format. Sites publish recipe data as `<script type="application/ld+json">{ "@type": "Recipe", ... }</script>` for SEO. Reading this is deterministic.

**Library:** `com.apicatalog:titanium-json-ld` for JSON-LD parsing; Jackson for the object mapping. Wrapped in a small `JsonLdRecipeExtractor` that:
1. Parses every `<script type="application/ld+json">` block
2. Filters to entries with `@type` containing `"Recipe"` (handles `@type: ["Recipe", "NutritionInformation"]` arrays)
3. If multiple recipes on the page (e.g. roundup posts), picks the one whose `mainEntityOfPage.url` matches the input URL or, failing that, the first; logs the ambiguity in provenance
4. Maps schema.org fields onto `ParsedRecipe`:
   - `name` → `name`
   - `recipeIngredient[]` → `ingredients[]` (parsed via `IngredientLineParser` — see below)
   - `recipeInstructions[]` → `methodSteps[]` (handles both string array and `HowToStep` object array)
   - `recipeYield` → `servings` (parses "4 servings", "4-6", "Makes 12 cookies")
   - `prepTime`, `cookTime`, `totalTime` → minutes (ISO 8601 duration parsing)
   - `recipeCuisine`, `recipeCategory`, `keywords` → tag candidates
   - `nutrition.NutritionInformation` → discarded (we recompute internally per [recipe-system.md](../design/recipe-system.md))

**Reliability:** very high. Failures here are typically schema-violation cases (malformed JSON, fields with wrong types) — caught by the validator (Layer 5) which routes back to Layer 4 if Layer 1's output looks broken.

### Layer 2 — h-recipe microformats

The legacy alternative. Some food blogs (often older WordPress installs) use `<div class="hrecipe">` markup with classed sub-elements (`fn`, `ingredient`, `instructions`, `yield`).

**Library:** `org.microformats.parser:microformats2-parser` (or a small in-house parser — the spec is simple).

Reliability: medium. h-recipe is less strictly defined than JSON-LD; in-the-wild markup varies. Validator catches anomalies and routes to Layer 4.

### Layer 3 — Per-site extractor registry

For sites that publish neither JSON-LD nor h-recipe but have stable HTML structure, OR sites whose JSON-LD has known issues that need post-processing.

```java
public interface RecipeSiteExtractor {
    /** The domain (or domain pattern) this extractor handles, e.g. "bbcgoodfood.com". */
    String domainPattern();

    /** Optional pre-extraction: if this returns non-empty, skip Layers 1-2 entirely. */
    Optional<ParsedRecipe> extractRaw(String url, String html);

    /** Optional post-processing of Layer 1/2 output: clean up known issues. */
    ParsedRecipe postProcess(ParsedRecipe extracted, String url, String html);
}
```

Registered as Spring beans; `RecipeExtractionService` indexes them by domain pattern. v1 ships **zero** registered extractors — the registry mechanism is in scope, the population is iterative as user reports surface site-specific bugs.

Examples of what a future extractor might do:
- **BBCGoodFoodPostProcessor**: strip "BBC Good Food" prefixes from recipe names; normalise their `recipeYield` "Serves 4" pattern
- **NytCookingPreFetch**: NYT Cooking has paywall-aware JSON-LD that returns abbreviated content for non-subscribers; if HTML-direct mode supplies authenticated content, use the full version preferentially
- **GenericWordPressFoodBlog**: handle common `wp-recipe-maker` plugin output that has malformed JSON-LD

### Layer 4 — AI HTML extraction

When all structured-data paths miss, fall back to LLM extraction.

**`AiTask` type:** `RecipeHtmlExtractionTask` (cheap tier; prompt content deferred to the prompt-engineering track).

**Input:** the page HTML, truncated to ~30 KB (typical recipe-page article-content size after stripping nav/ads). Stripping uses a `BoilerplateStripper` — Readability-style algorithm to remove `<nav>`, `<aside>`, `<footer>`, ad iframes, comment sections.

**Output structure:** the same `ParsedRecipe` record, returned via tool-use structured output.

**Cost:** ~$0.001-0.005 per extraction (~10-30k input tokens at cheap-tier rates). Bounded by:
- Per-extraction token cap (32k input)
- Daily cap shared with the rest of the AI cost budget
- `AiUnavailable` → extraction fails, returns `ExtractionFailure(reason = AI_UNAVAILABLE, retry_after = midnight_utc)` → autonomous discovery skips and retries tomorrow; user-driven import surfaces "we couldn't extract this recipe automatically — would you like to enter it manually?"

**Prompt caching:** the system message + extraction instructions are cached at the Anthropic API level (`cache_control` breakpoint). Repeated calls within 5 minutes hit cache. Cache discipline reduces cost ~40% in batch discovery runs.

### Layer 5 — Validator

Runs on whatever Layer 1-4 produced. Pure code; no AI.

**Validation rules:**
- `name` non-empty, ≤ 200 chars
- `ingredients` non-empty (≥ 1 ingredient)
- Each ingredient line parsed by `IngredientLineParser` (see below) into `(quantity, unit, name)`. Lines that fail to parse are kept as text but flagged as `unparseable`.
- `methodSteps` non-empty (≥ 1 step)
- Time fields (`prepTime`, `cookTime`, `totalTime`) sensible: 0 < t < 24h
- `servings` parses to a positive integer or range
- Cumulative times consistent: `prepTime + cookTime ≈ totalTime` within 25% slack (allows for "active vs passive" time differences)

**Failure modes:**
- **Hard fail** (no name, no ingredients, no method): `ExtractionFailure(reason = INSUFFICIENT_CONTENT)`. Routes back: if Layer 1-3 produced this, retry once at Layer 4. If Layer 4 produced this, terminal failure.
- **Soft fail** (unparseable ingredient lines, time inconsistency): `ParsedRecipe` returned with `validationWarnings` populated. The user-driven path surfaces these in the preview UI; the autonomous path either accepts (with provenance flag) or rejects per `discovery_sources.minQualityThreshold`.

---

## `ParsedRecipe` data shape

```java
public record ParsedRecipe(
    // Source identity
    String sourceUrl,
    String sourceDomain,
    String contentHash,                         // SHA-256 of normalised raw text — for dedup

    // Recipe content
    String name,
    String description,                          // optional summary line
    int servings,
    Integer prepTimeMinutes,                     // null if unknown
    Integer cookTimeMinutes,
    int totalTimeMinutes,                        // computed if not provided
    List<ParsedIngredient> ingredients,
    List<ParsedMethodStep> methodSteps,

    // Tag candidates (raw text — recipe module's tagger normalises onto our tag vocabulary)
    List<String> rawCuisineTags,
    List<String> rawCategoryTags,
    List<String> rawKeywords,

    // Provenance
    ExtractionProvenance provenance,
    List<ValidationWarning> validationWarnings
) {}

public record ParsedIngredient(
    String rawText,                              // original line from source
    BigDecimal quantity,                         // null if unparseable
    String unit,                                  // "g", "ml", "tbsp", null
    String name,                                  // "chicken thighs"
    String preparation,                           // "diced", null
    boolean optional,                             // parsed from "(optional)" markers
    boolean unparseable                           // true → only rawText is reliable
) {}

public record ParsedMethodStep(int stepNumber, String instruction) {}

public record ExtractionProvenance(
    Layer winningLayer,                          // JSON_LD | H_RECIPE | PER_SITE | AI_HTML
    List<Layer> layersTried,                     // order
    String aiModelId,                            // null unless AI_HTML won
    Optional<String> perSiteExtractorKey,        // bean name if PER_SITE won or post-processed
    Duration extractionDuration,
    Instant extractedAt
) {}

public record ValidationWarning(String code, String message, String fieldPath) {}
```

The recipe module's `RecipeWriteApi` consumes `ParsedRecipe` and resolves it onto its own catalogue shapes (recipe → version → ingredients → method steps), running tag normalisation, ingredient-mapping-key resolution (via Nutrition's `IngredientMappingService`), and the deterministic hard-constraint filter on the result.

---

## `IngredientLineParser`

Sub-component used at Layer 5 (and as a helper at Layer 1 for JSON-LD's `recipeIngredient[]` strings, which are typically free-text like `"2 cups all-purpose flour"`).

**Strategy:**
1. Try the structured forms first: `<qty> <unit> <name>` with regex-driven recognition of common units (g, kg, ml, l, tbsp, tsp, cup, oz, lb, etc.) and number formats (`1`, `1.5`, `1/2`, `1 1/2`, `1-2`).
2. Handle parenthetical amounts: `"flour (240g)"` → quantity=240, unit=g, name="flour".
3. Fall back to whole-string-as-name with `unparseable = true`. The `rawText` is preserved verbatim either way.

Pluggable per-locale (UK metric vs US imperial). v1 supports both; lifestyle config drives the canonical conversion target during recipe storage.

---

## `RecipeExtractionService` interface

```java
public interface RecipeExtractionService {

    /** Synchronous extraction. Used by user-driven import (caller waits to show preview).
     *  Throws ExtractionFailureException on hard failure; returns ParsedRecipe with
     *  validationWarnings on soft failure. */
    ParsedRecipe extract(ExtractionInput input);

    /** Async variant — used by autonomous discovery. Returns a CompletableFuture so
     *  per-source rate-limited orchestration can compose calls. */
    CompletableFuture<ExtractionResult> extractAsync(ExtractionInput input);
}

public sealed interface ExtractionResult {
    record Success(ParsedRecipe recipe) implements ExtractionResult {}
    record Failure(FailureReason reason, String message, Optional<Instant> retryAfter)
        implements ExtractionResult {}
}

public enum FailureReason {
    URL_FETCH_FAILED, ROBOTS_DISALLOWED, EXTRACTION_INSUFFICIENT,
    AI_UNAVAILABLE, RATE_LIMITED, MALFORMED_HTML, INTERNAL_ERROR
}
```

Both methods first check the **extraction cache** (see below) before invoking the layer stack. Cache hit → return immediately, no network or AI cost.

---

## Extraction cache

Repeated requests for the same URL within a TTL window return the cached `ParsedRecipe` rather than re-fetching and re-extracting. Particularly valuable for:
- Discovery: a popular recipe URL may surface in multiple search runs in a week
- User-driven retry: user pasted a URL, hit save, edited, resubmitted — same URL, same extraction

```sql
CREATE TABLE recipe_extraction_cache (
    cache_key       varchar(64) PRIMARY KEY,    -- SHA-256 of normalised URL (lower-cased, fragment-stripped)
    source_url      text NOT NULL,
    parsed_recipe   jsonb NOT NULL,             -- ParsedRecipe serialised
    provenance      jsonb NOT NULL,
    cached_at       timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    hit_count       integer NOT NULL DEFAULT 0
);
CREATE INDEX idx_extraction_cache_expiry ON recipe_extraction_cache (expires_at);
```

**TTL: 7 days** by default (recipes don't change often; sites occasionally tweak their JSON-LD; weekly refresh is enough). Cache is bypassed when:
- The caller passes `forceRefresh = true` (e.g. user reports "this extraction is wrong, retry")
- The cached entry is stale (past `expires_at`)
- The cached entry's `provenance.winningLayer` was `AI_HTML` and a higher layer might now succeed (AI extraction of a site's content may be obsoleted by the site adding JSON-LD; check periodically)

**Storage cost**: ~5KB per cached entry × ~500 unique URLs/year per active user ≈ 2.5MB/year. Negligible. Daily sweep prunes expired rows.

---

## Rate limiting and politeness (URL-fetch path only)

`FromHtml` mode bypasses these — the frontend already fetched the page. `FromUrl` mode applies them.

**Per-domain limits**:
- 1 request per 2 seconds per domain (token bucket via Resilience4j `@RateLimiter` keyed on domain)
- Configurable per-source via `discovery_sources.rate_limit_rps` for trusted sources we have agreements with

**`robots.txt`**:
- Fetched at first contact with each domain, cached for 24h
- Hard-coded respect: any URL the site disallows is rejected with `FailureReason.ROBOTS_DISALLOWED`
- Library: `com.panforge:crawler-commons` (or `crawler-commons` direct from common-crawl) for parsing
- Politeness: any `Crawl-delay` directive is honoured (overrides the default 2s rate)

**User agent**: `MealPrepAI/1.0 (+https://mealprep.example.com/bot)` — honest identification with a contact URL.

**Adaptive backoff**:
- 429 / 503 from a domain → exponential backoff per Resilience4j circuit breaker, max 1 hour
- 5 consecutive 5xx from a domain → 24-hour circuit-break

**Off-hours scheduling**: autonomous discovery runs default to 02:00-06:00 UTC (configurable per `mealprep.discovery.scheduling.allowed_hours`). User-driven imports run any time.

---

## Search engine integration

Two source types feed the discovery pipeline:

### `CURATED` source

A registered domain in `discovery_sources` with type `CURATED`. The discovery job queries the source directly — the source either has a search/filter API, an RSS feed, or a sitemap we crawl periodically. Each curated source has its own `DiscoverySourceFetcher` impl.

**v1 seed list (committed in `R__discovery_seed_curated_sources.sql`):** 25-30 sites including BBC Good Food, Serious Eats, AllRecipes, Bon Appétit, NYT Cooking (when free), Hello Fresh, Eat This, Delicious, Food52, Smitten Kitchen, Half Baked Harvest, plus a handful of UK-specific sites (Jamie Oliver, Deliciously Ella, Hairy Bikers).

The list is starter data, not gospel — users can disable any source they don't like via Settings. The list is also extensible without redeploy: a Spring `@ConfigurationProperties` reload re-reads any new entries from the DB seed table.

### `SEARCH` source

A registered source of type `SEARCH` with a search-engine API integration. **v1: Google Custom Search JSON API.**

```java
@ConfigurationProperties(prefix = "mealprep.discovery.search.google")
public record GoogleCustomSearchConfig(
    @NotBlank String apiKey,
    @NotBlank String searchEngineId,           // CSE configured to recipe sites
    @Min(1) @Max(10) int resultsPerQuery,
    @Min(1) int maxQueriesPerDay
) {}
```

The Google CSE is configured (out-of-band, via Google's admin UI) to search across a curated set of recipe sites — broader than the `CURATED` list, narrower than the open web. This balances quality (the CSE limits the corpus) with reach (Google's index is bigger and fresher than our crawls).

**Cost**:
- Free tier: 100 queries/day
- Paid: $5 per 1000 queries above the free tier
- Default cap: 50 queries/day (well within free tier; covers ~50 discovery runs/week × 1 query each, or ~7 queries/day with planner-time cold-start use)

**Fallback**: when Google CSE returns no results or errors, the autonomous discovery job falls back to a CURATED source for the same query terms.

### Source rotation

Each discovery job specifies a `sourceTypes: Set<SourceType>` (default: `{CURATED, SEARCH}`). Within those types, the runner round-robins by `last_used_at` to spread load across sources. Per-source rate limits cap how often any one source is hit.

---

## Data shape for `discovery_sources` (additions)

The discovery LLD already has `discovery_sources`. Additions for this spec:

```sql
ALTER TABLE discovery_sources ADD COLUMN
    source_type varchar(16) NOT NULL DEFAULT 'CURATED',  -- CURATED | SEARCH
    rate_limit_rps numeric(4,2) NOT NULL DEFAULT 0.5,    -- 0.5 = 1 request per 2 seconds
    crawl_strategy varchar(32),                          -- 'sitemap' | 'rss' | 'search_api' | 'category_index'
    crawl_config jsonb,                                  -- strategy-specific params
    user_disabled boolean NOT NULL DEFAULT false,
    quality_score numeric(4,3),                          -- rolling: fraction of extractions reaching reconciled status
    last_used_at timestamptz;
```

`quality_score` updates on every extraction outcome — if a source's extractions consistently fail or are user-rejected, the score drops; the runner deprioritises low-score sources. Pure observability — no automatic disable; user reviews the "low-quality sources" list periodically.

---

## Failure modes (consolidated)

| Failure | Where | Response |
|---|---|---|
| URL doesn't resolve / 404 | Layer 0 (fetch) | `FailureReason.URL_FETCH_FAILED`; user-driven path shows "we couldn't reach that page"; autonomous path logs and skips. |
| `robots.txt` disallows | Layer 0 | `ROBOTS_DISALLOWED`; never retried for that URL pattern. |
| All five layers fail | Layer 5 | `EXTRACTION_INSUFFICIENT`; user-driven path offers manual entry; autonomous path logs and skips. |
| AI cost cap reached | Layer 4 | `AI_UNAVAILABLE`; same fallback as above. Discovery jobs auto-pause Layer 4 work for the rest of the day. |
| Rate limited per-domain | Layer 0 | `RATE_LIMITED` with `retryAfter`; queued to retry. |
| Cache poisoning (corrupted entry) | Cache | `MALFORMED_HTML`; entry purged, re-extract. |
| Layer 1 succeeds but validator finds inconsistency | Layer 5 | Soft-fail with `validationWarnings`; result returned with warnings; consumer decides. |

---

## Test plan

| Class | Verifies |
|---|---|
| `JsonLdRecipeExtractorTest` | Parses 10 fixture pages from major recipe sites; handles `@type` arrays; picks the right recipe on multi-recipe pages; survives malformed JSON. |
| `HRecipeExtractorTest` | Parses h-recipe fixtures; falls through cleanly when not present. |
| `IngredientLineParserTest` | `"2 cups flour"`, `"1/2 tbsp salt"`, `"1-2 onions"`, `"2 (14oz) cans tomatoes"`, `"a pinch of pepper"` (unparseable, name preserved). UK and US units. |
| `BoilerplateStripperTest` | Strips nav, ads, comments; preserves recipe content even when nested in non-semantic divs. |
| `RecipeExtractionServiceIT` | Real fixtures from disk for FromHtml; mocked fetcher for FromUrl. Cache hit + miss paths. AI fallback uses `TestAiService`. |
| `ExtractionRateLimiterIT` | Token bucket per domain; honours `Crawl-delay`; circuit-break after consecutive 5xx. |
| `ExtractionCacheIT` | Cache hit returns stored ParsedRecipe; expired entries re-extract; `forceRefresh` bypasses; daily sweep prunes. |
| `GoogleCustomSearchProviderIT` | WireMock-backed Google CSE; daily cap enforcement; fallback to CURATED on error. |

---

## Out of Scope (deferred to other tracks)

- **Recipe extraction LLM prompt content** — system message, user template, tool-use schema for `RecipeHtmlExtractionTask`. Lives with the prompt-engineering track.
- **Specific per-site extractor implementations** — registry mechanism is in scope; populating it is iterative.
- **In-app browser frontend** — webview component, "Save Recipe" button overlay, HTML extraction client-side. Figma + frontend phases. Backend supports it via `FromHtml` mode.
- **Login-aware fetching** for paywalled sites server-side — out of scope for v1; user uses the in-app browser path for paywalled content they have a subscription to.
- **Image extraction** — recipe photos. Not part of v1's recipe data model.
- **Video recipe extraction** (TikTok, YouTube cooking videos). Future.
- **Browser-based rendering server-side** (Puppeteer / Playwright) for JS-heavy sites. v1 relies on the `FromHtml` path or AI fallback. Future enhancement if too many sites fail at JS-rendering.
- **User-supplied per-site extractor extensions** (community plugins) — interesting future feature.
