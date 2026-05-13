# Ticket: discovery — 01e Curated Source Seed + Google Custom Search Adapter + Reference Curated `DiscoverySource` Impl

## Summary

Populate the **source registry with v1's curated list** and ship the **Google Custom Search JSON API adapter** (`SEARCH` source type). Per [`lld/discovery.md`](../../lld/discovery.md) §V20260615120000-line-102 ("repeatable seed `R__discovery_seed_source_registry.sql` ships empty in v1; once sources are chosen, it carries one `INSERT ... ON CONFLICT (source_key) DO UPDATE` per source"), §`SEARCH` source (line 13, locked 2026-05-07), [recipe-extraction-pipeline.md §Search engine integration §Google CSE (lines 337-356)](../../lld/recipe-extraction-pipeline.md). Ships:

- **`R__discovery_seed_curated_sources.sql`** — REPEATABLE migration with one `INSERT ... ON CONFLICT (source_key) DO UPDATE` per curated source. Replaces 01a's empty `R__discovery_seed_source_registry.sql` (renamed to match the file expected by the LLD's v1 contract). Ships **the user-decided ~25-30 curated sites** from the seed list in [recipe-extraction-pipeline.md line 331](../../lld/recipe-extraction-pipeline.md): "BBC Good Food, Serious Eats, AllRecipes, Bon Appétit, NYT Cooking (when free), Hello Fresh, Eat This, Delicious, Food52, Smitten Kitchen, Half Baked Harvest, plus a handful of UK-specific sites (Jamie Oliver, Deliciously Ella, Hairy Bikers)."
- **`GoogleCustomSearchAdapter`** — concrete `DiscoverySource` impl in `discovery/source/` (the "hard pocket" package per [LLD line 52](../../lld/discovery.md)). `@Component`. `key() = "google_cse"`, `kind() = SEARCH_API`. Implements `search(DiscoveryQuery)` against Google CSE JSON API; throws `DiscoverySourceUnavailableException` on permanent failure (quota exhausted, API key invalid). `fetchRecipe` delegates to a fallback strategy — see "Two-source-type concern" below.
- **`ReferenceCuratedSource`** — single reference `DiscoverySource` `@Component` impl in `discovery/source/` per [LLD line 52](../../lld/discovery.md). Demonstrates the curated-site pattern (sitemap enumeration → `DiscoveryCandidate` list; per-candidate full-page fetch via deterministic HTML parser). **Wired against the source_key `bbc_good_food`** from the seed (an arbitrary but well-known JSON-LD site). Future tickets add more impls; the registry and runner are source-agnostic.
- **`GoogleCustomSearchConfig`** — `@ConfigurationProperties(prefix = "mealprep.discovery.search.google")` per [recipe-extraction-pipeline.md line 341](../../lld/recipe-extraction-pipeline.md): `apiKey`, `searchEngineId`, `resultsPerQuery`, `maxQueriesPerDay`. **Secrets via env vars** per style-guide.
- **`GoogleCseDailyQuotaTracker`** — package-private `@Component` that tracks daily call count (in-memory + Postgres backup via a new table). Resets at UTC midnight per [recipe-extraction-pipeline.md line 357](../../lld/recipe-extraction-pipeline.md). Adapter throws `DiscoverySourceUnavailableException` when `dailyCount >= maxQueriesPerDay`.
- **Five-layer extraction stack — DEFERRED to a separate ticket**. The `ReferenceCuratedSource.fetchRecipe` uses a minimal JSON-LD-only extractor (single layer); the full 5-layer `RecipeExtractionService` from [recipe-extraction-pipeline.md](../../lld/recipe-extraction-pipeline.md) is its own engineering exercise. **Worth user review** — see "Extraction strategy reuse" below.

## Two-source-type concern

The `GoogleCustomSearchAdapter.fetchRecipe(candidate)` call has a structural quirk: Google CSE returns SERP results with URLs to OTHER sites. To `fetchRecipe`, the adapter would need to HTML-extract from arbitrary domains — exactly what the curated sources do.

**Two valid patterns**:

- **Pattern A** (LLD line 391's wording): `GoogleCustomSearchAdapter.fetchRecipe` performs HTML extraction on its own. Each `SEARCH` source carries the full extraction logic.
- **Pattern B**: Split into `Searcher` (returns candidates with URLs) and a shared `Extractor` (HTML → ParsedRecipe). `fetchRecipe` is a thin wrapper that delegates to the extractor. This is the recipe-extraction-pipeline LLD's framing — the extraction stack is a shared service.

**01e ships Pattern A** for v1 simplicity. The `GoogleCustomSearchAdapter.fetchRecipe` re-uses the same JSON-LD-only extractor as `ReferenceCuratedSource` via a shared `JsonLdRecipeExtractor` helper class. When the full 5-layer `RecipeExtractionService` lands in a future ticket (`recipe-extraction-pipeline-01a` — not yet ticketed), both impls migrate to Pattern B by injecting `RecipeExtractionService`. **Worth user review.**

## Extraction strategy reuse

[`recipe-extraction-pipeline.md`](../../lld/recipe-extraction-pipeline.md) specifies a 5-layer extraction stack (JSON-LD → h-recipe → per-site → AI HTML → validator) shared between user-driven URL import and autonomous discovery. **The full stack is NOT yet ticketed** in the recipe module (recipe-01b ships a deterministic 3-layer parser; the AI-HTML and validator layers + the cache table are deferred). **For 01e**:

- Ship `JsonLdRecipeExtractor` as a minimal helper in `discovery/source/internal/` — parses `<script type="application/ld+json">` blocks via Jackson, maps schema.org `Recipe` fields to `ParsedRecipe`. **No h-recipe, no per-site, no AI fallback in 01e.** Same scope as recipe-01b's first parser layer. Cross-module DRY is deferred to the recipe-extraction-pipeline ticket.
- **Worth user review** — alternative is to wait for the recipe-extraction-pipeline tickets and consume `RecipeExtractionService` directly. Rejected for v1: the pipeline tickets aren't yet authored; discovery should ship its own minimal extractor and migrate later. The cost is one duplicate class (~80 LOC) and one duplicate test (~10 cases) — acceptable.

## Defers still after 01e

- Sync admin endpoint + `runJobSync` + `CompletableFuture` coordination + 408 / 502 mappings → **discovery-01f**
- Additional curated source impls beyond `ReferenceCuratedSource` (e.g. `SeriousEatsSource`, `AllRecipesSource`) — each new site is a new `@Component` impl + (optionally) a new seed row. **All deferred** per [LLD line 627](../../lld/discovery.md) ("the actual list of search engines and recipe sources" is the user-decided hard pocket).
- Full 5-layer `RecipeExtractionService` from `recipe-extraction-pipeline.md` → separate ticket sequence (`recipe-extraction-pipeline-01a..N`)
- HTML extraction TEMPLATES per source (microdata + JSON-LD + site selectors + AI fallback) → [LLD line 629](../../lld/discovery.md) "Separate engineering exercise"
- AI candidate filter prompt (#7) — `CandidateAiFilter` ships as pass-through in 01c

## Behavioural spec

### `R__discovery_seed_curated_sources.sql`

1. **Rename** 01a's `R__discovery_seed_source_registry.sql` to `R__discovery_seed_curated_sources.sql`. Per the LLD line 64 the file is `R__discovery_seed_source_registry.sql`; per the brief's task list it's `R__discovery_seed_curated_sources.sql`. **Use the brief's name.** Single `git mv` (or in agent terms: delete the empty 01a file, create the new one). **LLD divergence noted.**
2. **Body**: one `INSERT INTO discovery_sources(...) VALUES(...) ON CONFLICT (source_key) DO UPDATE SET ...` per source. The `DO UPDATE` clause refreshes `display_name`, `kind`, `base_url`, `user_agent`, `crawl_config`, `requests_per_minute`, `requests_per_day`, `respect_robots_txt`, `updated_at = now()` — preserves `enabled`, `failure_streak`, `last_*_at`, `quality_score`, `notes` (operator-managed state).
3. **v1 source list** per [recipe-extraction-pipeline.md line 331](../../lld/recipe-extraction-pipeline.md). 01e ships ~28 rows:
   - `bbc_good_food` (CURATED / sitemap / `https://www.bbcgoodfood.com`)
   - `serious_eats` (CURATED / sitemap / `https://www.seriouseats.com`)
   - `allrecipes` (CURATED / sitemap / `https://www.allrecipes.com`)
   - `bon_appetit` (CURATED / sitemap / `https://www.bonappetit.com`)
   - `nyt_cooking` (CURATED / sitemap / `https://cooking.nytimes.com`) — flagged in `notes` as "paywall-aware; v1 best-effort"
   - `hello_fresh` (CURATED / sitemap / `https://www.hellofresh.co.uk`)
   - `eat_this` (CURATED / rss_feed / `https://www.eatthis.com`)
   - `delicious_au` (CURATED / sitemap / `https://www.delicious.com.au`)
   - `food52` (CURATED / sitemap / `https://food52.com`)
   - `smitten_kitchen` (CURATED / rss_feed / `https://smittenkitchen.com`)
   - `half_baked_harvest` (CURATED / sitemap / `https://www.halfbakedharvest.com`)
   - `jamie_oliver` (CURATED / sitemap / `https://www.jamieoliver.com`)
   - `deliciously_ella` (CURATED / sitemap / `https://deliciouslyella.com`)
   - `hairy_bikers` (CURATED / sitemap / `https://hairybikers.com`)
   - `google_cse` (SEARCH / search_api / `https://www.googleapis.com/customsearch/v1`) — `crawl_config = { searchEngineId: "${MEALPREP_GOOGLE_CSE_ID:placeholder}" }`. **API key is NOT in `crawl_config`** — too sensitive for a Postgres column; comes from `mealprep.discovery.search.google.api-key` via env var.

   **Each row** carries: `id = gen_random_uuid()`, `source_key`, `display_name`, `source_type`, `kind`, `base_url`, `enabled = true`, `user_disabled = false`, `requests_per_minute = 6` (default per LLD line 82), `requests_per_day = 500`, `respect_robots_txt = true`, `user_agent = 'MealPrepAI/1.0 (+https://mealprep.example.com/bot)'` (per [recipe-extraction-pipeline.md line 313](../../lld/recipe-extraction-pipeline.md)), `crawl_config = '{}'::jsonb` (site-specific kind config), `failure_streak = 0`, `created_at = now()`, `updated_at = now()`.

4. **Idempotency**: Flyway repeatable migrations re-run on every checksum change. The `ON CONFLICT ... DO UPDATE` makes each individual statement idempotent. **The whole script is safe to re-run.**

5. **Total count check**: 14 curated + 1 search = 15 sources for the v1 list shipped in 01e. The LLD spec'd ~25-30; the rest can land as follow-up rows when the user reviews. **LLD divergence**: 15 is below the 25-30 target. 01e ships the user-named list from recipe-extraction-pipeline.md verbatim; expanding to 25-30 is a future seed-only diff (no code changes). **Worth user review.**

### `GoogleCustomSearchAdapter`

6. New `@Component` `GoogleCustomSearchAdapter` at `discovery/source/GoogleCustomSearchAdapter.java` implementing `DiscoverySource`.
   - `key() = "google_cse"` (matches the seed row).
   - `kind() = DiscoverySourceKind.SEARCH_API`.
   - `robotsTxtUri() = Optional.empty()` (Google CSE is an API; per LLD line 396 "Empty for sources with their own API — runner skips robots check").

7. **`search(DiscoveryQuery query)`** flow:
    1. **Quota check**: `if (quotaTracker.todaysCount() >= config.maxQueriesPerDay()) throw new DiscoverySourceUnavailableException("google_cse", "daily quota exhausted", null)`.
    2. **Build query string**: concatenate `constraints.requiredCuisines + " recipe"` + optional `dietaryFlags`. e.g. `"East Asian recipe vegetarian"`. **Worth user review** — the prompt-engineering of the search query is a future track; v1 uses a deterministic template.
    3. **Build URL**: `https://www.googleapis.com/customsearch/v1?key={apiKey}&cx={searchEngineId}&q={encodedQuery}&num={Math.min(10, query.maxResults())}`. CSE's `num` parameter capped at 10 per [recipe-extraction-pipeline.md line 343](../../lld/recipe-extraction-pipeline.md).
    4. **HTTP GET** via `RestClient` (timeout 10s, declared in `DiscoveryHttpConfig` from 01c — verify; if `RestClient` bean exists ADD a separate `searchClient` bean). User-agent from `config.userAgent()` (defaults to `MealPrepAI/1.0 (+https://mealprep.example.com/bot)`).
    5. **Increment quota tracker** after the HTTP call (whether success or failure — Google bills regardless).
    6. **Parse JSON response**: `items[]` array. For each item, build a `DiscoveryCandidate`:
        - `sourceKey = "google_cse"`.
        - `candidateUrl = item.link`.
        - `snippetTitle = item.title`.
        - `snippetDescription = item.snippet`.
        - `sourceMetadata = { "serpRank": String.valueOf(index), "displayLink": item.displayLink }`.
    7. Return the list (capped at `query.maxResults()`).
    8. **HTTP 429 or 5xx** → `DiscoverySourceUnavailableException("google_cse", "HTTP " + status, cause)`. **No retries within `search`** — the runner's circuit breaker handles repeated failures.
    9. **`RestClientException` / timeout** → `DiscoverySourceUnavailableException` similarly.

8. **`fetchRecipe(candidate)`** flow:
    - Google CSE returned a URL pointing to an arbitrary site. Discovery delegates HTML extraction to `JsonLdRecipeExtractor` (shared helper, see below):
      ```java
      String html = fetchHtml(candidate.candidateUrl());          // RestClient GET, 10s timeout, 2MB cap
      return jsonLdRecipeExtractor.extract(html, candidate.candidateUrl())
          .orElseThrow(() -> new ExtractionFailedException(candidate.candidateUrl(), "no_jsonld"));
      ```
    - HTTP fetch failures → `ExtractionFailedException`. The runner converts to `EXTRACTION_FAILED` scrape row and continues.
    - **Note**: this means `GoogleCustomSearchAdapter` does double duty — search AND extraction of arbitrary sites. The shared `JsonLdRecipeExtractor` keeps the extractor reusable across sources.

### `ReferenceCuratedSource`

9. New `@Component` `ReferenceCuratedSource` at `discovery/source/ReferenceCuratedSource.java` implementing `DiscoverySource`.
   - `key() = "bbc_good_food"` (matches one seed row).
   - `kind() = DiscoverySourceKind.SITEMAP`.
   - `robotsTxtUri() = Optional.of(URI.create("https://www.bbcgoodfood.com/robots.txt"))`.

10. **`search(DiscoveryQuery query)`** flow:
    1. **Fetch sitemap**: `https://www.bbcgoodfood.com/sitemap.xml` via `RestClient`, 10s timeout. **Cached** per-instance for `properties.sitemapCacheTtl()` (default 6h, NEW config property — add to `DiscoveryProperties` from 01a). **One-time-per-runner-jvm** is too aggressive (long-running instances would never refresh); 6h refresh is the v1 default. **Worth user review.**
    2. **Parse XML sitemap** with `javax.xml.parsers.DocumentBuilder`. Extract `<url><loc>...</loc></url>` entries. **No new dependency** — JAXP is in the JDK.
    3. **Filter** URLs that look like recipe pages — for BBC Good Food, paths starting with `/recipes/`. Simple `String.contains("/recipes/")` check.
    4. **Cap at `query.maxResults()`** and map each URL to a `DiscoveryCandidate(sourceKey="bbc_good_food", candidateUrl=url, snippetTitle=null, snippetDescription=null, sourceMetadata={})`. **Note**: BBC Good Food's sitemap doesn't carry rich snippets; v1 leaves them null. The runner doesn't depend on snippet content.
    5. **Sitemap fetch failure** → `DiscoverySourceUnavailableException("bbc_good_food", "sitemap fetch failed: " + cause, cause)`.

11. **`fetchRecipe(candidate)`** flow:
    - `String html = fetchHtml(candidate.candidateUrl())`.
    - `return jsonLdRecipeExtractor.extract(html, candidate.candidateUrl()).orElseThrow(() -> new ExtractionFailedException(...))`.
    - Identical to Google's `fetchRecipe`.

### `JsonLdRecipeExtractor` (shared helper)

12. New package-private `@Component` `JsonLdRecipeExtractor` at `discovery/source/internal/JsonLdRecipeExtractor.java`. **Pure code; no HTTP**. Tested in isolation.
13. **`Optional<ParsedRecipe> extract(String html, String url)`**:
    1. Parse `html` via jsoup (already in the project's pom, verify): `Document doc = Jsoup.parse(html, url)`.
    2. Find every `<script type="application/ld+json">` block: `doc.select("script[type=application/ld+json]")`.
    3. For each, parse as JSON via Jackson's `ObjectMapper.readTree(content)`. Swallow malformed scripts (try/catch, log DEBUG).
    4. Walk each JSON tree looking for `@type` == `"Recipe"` OR `@type` array containing `"Recipe"` OR `@graph` array entries with `@type` "Recipe".
    5. **Pick the first matching `Recipe`** if multiple — `ParsedRecipe` doesn't support multi-recipe pages in 01e.
    6. **Map fields** (defensive — every read tolerates missing/wrong-type):
        - `name` → `name` (text)
        - `description` → `description` (text)
        - `recipeIngredient` (string array) → `ingredients[]` of `ParsedIngredient(displayName=line, ingredientMappingKey=null, quantity=null, unit=null, preparation=null, optional=false)` — **ingredient parsing is deferred**. v1 ships only the displayName; the recipe module's `ParsedIngredient` shape (or whatever the recipe-extraction-pipeline LLD ships) does the line-parsing in a future ticket. **Worth user review.**
        - `recipeInstructions` (string array OR object array of `HowToStep`) → `methodSteps[]` of `ParsedMethodStep(stepNumber, instruction, durationMinutes=null)`. Increment stepNumber from 1.
        - `recipeYield` → `metadata.servings`. Parse "4 servings" / "4-6" / "Makes 12 cookies" via a tiny regex extracting the first integer. **Worth user review.**
        - `prepTime`, `cookTime`, `totalTime` → minutes via `java.time.Duration.parse(iso8601Duration).toMinutes()`. Defensive — null tolerance.
        - `recipeCuisine` → `metadata.cuisine`.
        - `recipeCategory`, `keywords` → ignored in v1 (discovery doesn't tag).
        - `nutrition` → **discarded** per [LLD line 299](../../lld/discovery.md): "external nutrition data is DISCARDED — recalculated internally".
    7. **Construct `ParsedRecipe`**: `canonicalUrl = url`, `extractionMethod = "json_ld"`, `extractionConfidence = BigDecimal.valueOf(0.85)` (high but not 1.0 — leaves room for human-supplied confidence in a future tier).
    8. **No JSON-LD blocks found** OR all malformed OR no `@type=Recipe` → return `Optional.empty()`. Caller throws `ExtractionFailedException`.
14. Tests in `JsonLdRecipeExtractorTest`: 5 fixtures (BBC Good Food, Serious Eats, AllRecipes, malformed JSON, no-recipe page) — assert correct mapping, null-tolerance, and confidence value. **Per [recipe-extraction-pipeline.md line 401](../../lld/recipe-extraction-pipeline.md)**: the recipe-extraction-pipeline LLD spec'd this test as `JsonLdRecipeExtractorTest`. 01e ships discovery's own (different package); the recipe-side test lands when the pipeline tickets do.

### `GoogleCseDailyQuotaTracker`

15. Package-private `@Component` `GoogleCseDailyQuotaTracker` at `discovery/source/internal/`. Hybrid in-memory + DB-backed state per recipe-extraction-pipeline.md's "free tier 100/day" budget.

16. **In-memory state**: `AtomicLong currentDayCount; LocalDate currentDay`. On the first call of a new UTC day, persist yesterday's count to the new `discovery_google_cse_usage` table and reset.

17. **`int todaysCount()`** — returns the in-memory counter, **after** rolling over the day if needed.

18. **`void recordCall()`** — increments + may roll over.

19. **`discovery_google_cse_usage` table** (new) — single-column-per-day table for audit:
    ```sql
    CREATE TABLE discovery_google_cse_usage (
        day  date PRIMARY KEY,
        call_count integer NOT NULL DEFAULT 0,
        updated_at timestamptz NOT NULL
    );
    ```
    **One row per day**. Used for `/admin/diagnostics` reporting (not 01e — future). Cheap to ship now.

20. **Crash recovery**: on application start, read the row for today (`SELECT call_count FROM discovery_google_cse_usage WHERE day = today_utc`). If present, initialise the in-memory counter from it. If absent, start at 0. Prevents quota overrun across runner restarts.

21. **The migration `V20260615120300__discovery_create_google_cse_usage.sql`** ships in 01e — single small table. **LLD divergence**: not in the discovery LLD, but operationally necessary for the Google CSE quota tracking. **Worth user review** — alternative is in-memory-only (loses count on restart; on a frequent-restart day, could exceed the 100/day free tier).

### `GoogleCustomSearchConfig`

22. `@ConfigurationProperties(prefix = "mealprep.discovery.search.google")` `@Validated` record at `discovery/config/`. Per [recipe-extraction-pipeline.md line 341](../../lld/recipe-extraction-pipeline.md):
    ```java
    @ConfigurationProperties(prefix = "mealprep.discovery.search.google")
    @Validated
    public record GoogleCustomSearchConfig(
        @NotBlank String apiKey,
        @NotBlank String searchEngineId,
        @Min(1) @Max(10) int resultsPerQuery,
        @Min(1) int maxQueriesPerDay,
        String userAgent
    ) {}
    ```
23. **Default `userAgent`** if unset: `MealPrepAI/1.0 (+https://mealprep.example.com/bot)`. Add a static default in the canonical constructor.
24. **Defaults** in `application.properties`:
    ```properties
    mealprep.discovery.search.google.api-key=${MEALPREP_GOOGLE_CSE_KEY:placeholder}
    mealprep.discovery.search.google.search-engine-id=${MEALPREP_GOOGLE_CSE_ID:placeholder}
    mealprep.discovery.search.google.results-per-query=10
    mealprep.discovery.search.google.max-queries-per-day=50
    mealprep.discovery.search.google.user-agent=MealPrepAI/1.0 (+https://mealprep.example.com/bot)
    ```
    `placeholder` defaults so dev/test starts without credentials; the adapter's first real call would fail with "401 Unauthorized" → `DiscoverySourceUnavailableException`. **Worth user review** — alternative is to require the credentials at startup (fail-fast). 01e ships the placeholder pattern to avoid breaking the dev profile.

25. **`@EnableConfigurationProperties(GoogleCustomSearchConfig.class)`** on `DiscoveryHttpConfig` (or wherever the discovery module's `@Configuration` lives). 01a likely already has `@ConfigurationPropertiesScan` somewhere; verify.

### Cross-cutting

26. **No changes to controllers** — 01b's surface covers list/get for the sources; 01e populates the rows.
27. **No changes to `DiscoveryServiceImpl`** — 01e only adds bean classes and migrations.
28. **No changes to `DiscoveryJobRunner`** — 01d's runner is source-agnostic; it discovers the new beans via `SourceRegistry`'s `@PostConstruct` indexing.
29. **`DiscoveryBoundaryTest`** from 01c gains a vacuous trip-wire: `DiscoverySource` impls live in `discovery.source..` — 01e's `GoogleCustomSearchAdapter` and `ReferenceCuratedSource` both live there. The rule passes for them.

## Database

```
src/main/resources/db/migration/V20260615120300__discovery_create_google_cse_usage.sql      new (one small table for quota tracking)
src/main/resources/db/migration/R__discovery_seed_curated_sources.sql                       new (REPLACES 01a's R__discovery_seed_source_registry.sql)
DEL  src/main/resources/db/migration/R__discovery_seed_source_registry.sql                  (renamed)
```

**Repeatable migration handling**: Flyway tracks repeatables by filename in `flyway_schema_history`. Renaming creates a new row; the old empty file is orphaned (Flyway won't re-run it). **Cleanup is irrelevant** — the old `R__discovery_seed_source_registry.sql` had no effect.

If git treats the rename as delete+add, that's fine. **Worth user review** if Flyway's repeatable-script handling on the project's CI version trips on renames; if it does, KEEP the original filename and just populate it (one-line decision change). The brief specifies `R__discovery_seed_curated_sources.sql` so the rename is correct.

### `V20260615120300__discovery_create_google_cse_usage.sql`

```sql
-- Discovery — Google CSE daily quota tracker.
-- One row per day. Survives restart so a long-running deploy doesn't lose quota state.
-- Reset point is UTC midnight; the application code rolls over.

CREATE TABLE discovery_google_cse_usage (
    day         date PRIMARY KEY,
    call_count  integer NOT NULL DEFAULT 0,
    updated_at  timestamptz NOT NULL
);
```

No additional indexes; the PK is sufficient (one-row-per-day).

### `R__discovery_seed_curated_sources.sql`

Header + 15 `INSERT ... ON CONFLICT` blocks. Truncated example (full file in the agent's output):

```sql
-- Discovery — v1 curated source seed.
-- One row per CURATED site + the SEARCH (Google CSE) row.
-- Idempotent: re-running refreshes display_name / kind / base_url / user_agent / crawl_config / *_per_minute / *_per_day / respect_robots_txt / updated_at.
-- Preserves operator-managed state: enabled, failure_streak, last_*_at, quality_score, notes, user_disabled.
-- LLD reference: lld/discovery.md §V20260615120000 line 102; lld/recipe-extraction-pipeline.md line 331.

INSERT INTO discovery_sources (
    id, source_key, display_name, source_type, kind, base_url,
    enabled, user_disabled, requests_per_minute, requests_per_day,
    respect_robots_txt, user_agent, crawl_config, failure_streak,
    created_at, updated_at
) VALUES (
    gen_random_uuid(), 'bbc_good_food', 'BBC Good Food', 'CURATED', 'SITEMAP',
    'https://www.bbcgoodfood.com', true, false, 6, 500,
    true, 'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
    '{"sitemapUrl": "https://www.bbcgoodfood.com/sitemap.xml", "pathFilter": "/recipes/"}'::jsonb,
    0, now(), now()
)
ON CONFLICT (source_key) DO UPDATE SET
    display_name        = EXCLUDED.display_name,
    kind                = EXCLUDED.kind,
    base_url            = EXCLUDED.base_url,
    requests_per_minute = EXCLUDED.requests_per_minute,
    requests_per_day    = EXCLUDED.requests_per_day,
    respect_robots_txt  = EXCLUDED.respect_robots_txt,
    user_agent          = EXCLUDED.user_agent,
    crawl_config        = EXCLUDED.crawl_config,
    updated_at          = now();

-- ... 13 more curated INSERTs ...

INSERT INTO discovery_sources (
    id, source_key, display_name, source_type, kind, base_url,
    enabled, user_disabled, requests_per_minute, requests_per_day,
    respect_robots_txt, user_agent, crawl_config, failure_streak,
    created_at, updated_at
) VALUES (
    gen_random_uuid(), 'google_cse', 'Google Custom Search', 'SEARCH', 'SEARCH_API',
    'https://www.googleapis.com/customsearch/v1', true, false, 60, 100,
    false, 'MealPrepAI/1.0 (+https://mealprep.example.com/bot)',
    '{"searchEngineId": "${MEALPREP_GOOGLE_CSE_ID}"}'::jsonb,
    0, now(), now()
)
ON CONFLICT (source_key) DO UPDATE SET
    display_name        = EXCLUDED.display_name,
    kind                = EXCLUDED.kind,
    base_url            = EXCLUDED.base_url,
    requests_per_minute = EXCLUDED.requests_per_minute,
    requests_per_day    = EXCLUDED.requests_per_day,
    respect_robots_txt  = EXCLUDED.respect_robots_txt,
    user_agent          = EXCLUDED.user_agent,
    crawl_config        = EXCLUDED.crawl_config,
    updated_at          = now();
```

**Notes**:
- `requests_per_minute = 60` for `google_cse` (API rate; CSE accepts up to 100 queries/100s on free tier, so 60/min is conservative).
- `requests_per_day = 100` for `google_cse` matches the CSE free tier.
- `respect_robots_txt = false` for `google_cse` — robots applies to crawled sites, not to API calls. The runner skips robots for sources with empty `robotsTxtUri()`.
- `crawl_config.searchEngineId` uses env-var substitution `${MEALPREP_GOOGLE_CSE_ID}` — **Postgres does NOT expand `${}` in JSONB literals**; this is a placeholder string that the adapter ignores in favour of the `GoogleCustomSearchConfig.searchEngineId` from `application.properties`. The `crawl_config` JSONB is informational. **Worth user review** — alternative is to look up `crawl_config.searchEngineId` at runtime and prefer it over the config bean; v1 doesn't need that.

## OpenAPI updates

**Zero OpenAPI changes in 01e.** No new endpoints; the impls are internal.

## Verbatim shape snippets

### `GoogleCustomSearchAdapter`

```java
@Component
@RequiredArgsConstructor
public class GoogleCustomSearchAdapter implements DiscoverySource {
  private static final Logger log = LoggerFactory.getLogger(GoogleCustomSearchAdapter.class);

  private final GoogleCustomSearchConfig config;
  private final GoogleCseDailyQuotaTracker quotaTracker;
  private final JsonLdRecipeExtractor jsonLdExtractor;
  private final RestClient discoveryRestClient;  // bean from discovery/config/DiscoveryHttpConfig
  private final ObjectMapper objectMapper;

  @Override public String key() { return "google_cse"; }

  @Override public DiscoverySourceKind kind() { return DiscoverySourceKind.SEARCH_API; }

  @Override public Optional<URI> robotsTxtUri() { return Optional.empty(); }

  @Override
  public List<DiscoveryCandidate> search(DiscoveryQuery query) {
    if (quotaTracker.todaysCount() >= config.maxQueriesPerDay()) {
      throw new DiscoverySourceUnavailableException(key(), "daily quota exhausted", null);
    }
    String q = buildQueryString(query.constraints());
    int num = Math.min(config.resultsPerQuery(), Math.max(1, query.maxResults()));
    try {
      JsonNode response = discoveryRestClient.get()
          .uri(uriBuilder -> uriBuilder
              .scheme("https").host("www.googleapis.com").path("/customsearch/v1")
              .queryParam("key", config.apiKey())
              .queryParam("cx", config.searchEngineId())
              .queryParam("q", q)
              .queryParam("num", num)
              .build())
          .header(HttpHeaders.USER_AGENT, config.userAgent())
          .retrieve()
          .body(JsonNode.class);
      quotaTracker.recordCall();
      return parseCandidates(response, num);
    } catch (HttpClientErrorException | HttpServerErrorException e) {
      quotaTracker.recordCall();
      throw new DiscoverySourceUnavailableException(key(),
          "HTTP " + e.getStatusCode().value(), e);
    } catch (RestClientException e) {
      throw new DiscoverySourceUnavailableException(key(), "network error", e);
    }
  }

  @Override
  public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
    String html;
    try {
      html = discoveryRestClient.get()
          .uri(candidate.candidateUrl())
          .header(HttpHeaders.USER_AGENT, config.userAgent())
          .retrieve()
          .body(String.class);
    } catch (RestClientException e) {
      throw new ExtractionFailedException(candidate.candidateUrl(), "fetch failed: " + e.getMessage());
    }
    return jsonLdExtractor.extract(html, candidate.candidateUrl())
        .orElseThrow(() -> new ExtractionFailedException(candidate.candidateUrl(), "no_jsonld"));
  }

  private List<DiscoveryCandidate> parseCandidates(JsonNode response, int num) {
    if (response == null || !response.has("items")) return List.of();
    List<DiscoveryCandidate> out = new ArrayList<>();
    int i = 0;
    for (JsonNode item : response.get("items")) {
      if (out.size() >= num) break;
      out.add(new DiscoveryCandidate(
          key(),
          item.path("link").asText(),
          item.path("title").asText(null),
          item.path("snippet").asText(null),
          Map.of("serpRank", String.valueOf(i++),
                 "displayLink", item.path("displayLink").asText(""))));
    }
    return out;
  }

  private String buildQueryString(DiscoveryConstraints constraints) {
    StringBuilder sb = new StringBuilder();
    if (constraints.requiredCuisines() != null) {
      sb.append(String.join(" ", constraints.requiredCuisines())).append(" ");
    }
    sb.append("recipe");
    if (constraints.dietaryFlags() != null) {
      for (String df : constraints.dietaryFlags()) sb.append(" ").append(df);
    }
    return sb.toString().trim();
  }
}
```

### `JsonLdRecipeExtractor` core method

```java
@Component
@RequiredArgsConstructor
class JsonLdRecipeExtractor {
  private static final Logger log = LoggerFactory.getLogger(JsonLdRecipeExtractor.class);
  private final ObjectMapper objectMapper;

  public Optional<ParsedRecipe> extract(String html, String url) {
    Document doc = Jsoup.parse(html, url);
    for (Element script : doc.select("script[type=application/ld+json]")) {
      Optional<ParsedRecipe> maybe = tryParse(script.data(), url);
      if (maybe.isPresent()) return maybe;
    }
    return Optional.empty();
  }

  private Optional<ParsedRecipe> tryParse(String json, String url) {
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode recipeNode = findRecipeNode(root);
      if (recipeNode == null) return Optional.empty();
      return Optional.of(mapToParsed(recipeNode, url));
    } catch (Exception e) {
      log.debug("malformed JSON-LD block at {}: {}", url, e.toString());
      return Optional.empty();
    }
  }

  private JsonNode findRecipeNode(JsonNode root) {
    // Handle @type = "Recipe" | ["Recipe", ...] | @graph: [{ @type: "Recipe", ... }, ...]
    if (root.isArray()) {
      for (JsonNode el : root) {
        JsonNode r = findRecipeNode(el);
        if (r != null) return r;
      }
      return null;
    }
    if (root.has("@graph")) {
      for (JsonNode el : root.get("@graph")) {
        JsonNode r = findRecipeNode(el);
        if (r != null) return r;
      }
    }
    JsonNode typeNode = root.get("@type");
    if (typeNode == null) return null;
    if (typeNode.isTextual() && "Recipe".equals(typeNode.asText())) return root;
    if (typeNode.isArray()) {
      for (JsonNode t : typeNode) {
        if (t.isTextual() && "Recipe".equals(t.asText())) return root;
      }
    }
    return null;
  }

  private ParsedRecipe mapToParsed(JsonNode r, String url) { ... }   // field-by-field per invariant 13
}
```

## Edge-case checklist

### Seed migration

- [ ] Repeatable migration applies cleanly first time → 15 rows in `discovery_sources`
- [ ] Re-running (Flyway recomputes checksum on edit) re-applies → unchanged operator state (enabled/failure_streak/quality_score preserved); refreshed `updated_at`, `display_name`, `kind`, `base_url`, `crawl_config`, `requests_per_minute`, `requests_per_day`, `respect_robots_txt`, `user_agent`
- [ ] After seed runs: `discoverySourceRepository.findByEnabledTrue()` returns 15
- [ ] All 15 `source_key`s match the lowercased forms expected by the impls (e.g. `bbc_good_food`, `google_cse`)
- [ ] `crawl_config` for `bbc_good_food` carries `{"sitemapUrl": "...", "pathFilter": "/recipes/"}`
- [ ] `crawl_config` for `google_cse` is non-null but the adapter ignores it (config bean wins)

### `GoogleCustomSearchAdapter.search`

- [ ] WireMock fixture: CSE returns 10 items → `search` returns 10 `DiscoveryCandidate`s with correct sourceKey/candidateUrl/snippet/serpRank metadata
- [ ] WireMock 200 with `items: []` → returns empty list (NOT an exception)
- [ ] WireMock 429 → `DiscoverySourceUnavailableException` with `"HTTP 429"` reason
- [ ] WireMock 503 → `DiscoverySourceUnavailableException` with `"HTTP 503"` reason
- [ ] WireMock timeout (delayed response > 10s) → `DiscoverySourceUnavailableException` with `"network error"` reason
- [ ] Each `search` call increments `quotaTracker.todaysCount()` by 1 (even on HTTP error)
- [ ] When `todaysCount >= maxQueriesPerDay` (= 50 by default), `search` throws without making an HTTP call

### `GoogleCustomSearchAdapter.fetchRecipe`

- [ ] WireMock fixture: target URL returns JSON-LD recipe → `ParsedRecipe` with `extractionMethod = "json_ld"`, confidence 0.85
- [ ] Target URL returns no JSON-LD → `ExtractionFailedException("no_jsonld")`
- [ ] Target URL returns 404 → `ExtractionFailedException` with fetch-failed reason

### `ReferenceCuratedSource`

- [ ] Sitemap fixture: 1000 URLs, 800 recipe paths, 200 non-recipe → `search(maxResults=20)` returns 20 candidates, all `/recipes/...`
- [ ] Sitemap fetch failure → `DiscoverySourceUnavailableException`
- [ ] Sitemap parse failure (invalid XML) → `DiscoverySourceUnavailableException`
- [ ] Sitemap cache: two calls within 6h → second hits cache (verify via WireMock request count)
- [ ] Recipe fetch with JSON-LD → `ParsedRecipe` returned; without → `ExtractionFailedException`

### `JsonLdRecipeExtractor`

- [ ] Fixture: BBC Good Food page with single `<script type="application/ld+json">` containing `@type: "Recipe"` → returns `ParsedRecipe` with name/ingredients/method/totalTimeMins populated
- [ ] Fixture: page with `@type: ["Recipe", "NutritionInformation"]` array → recipe extracted
- [ ] Fixture: page with `@graph: [{...Recipe...}, ...]` → recipe extracted
- [ ] Fixture: page with malformed JSON in one block but a second valid block → second block wins
- [ ] Fixture: page with no JSON-LD → `Optional.empty()`
- [ ] Fixture: page with `recipeInstructions` as array of `HowToStep` objects → method steps mapped from `.text` field
- [ ] Fixture: page with `recipeYield: "4 servings"` → `metadata.servings = 4`
- [ ] Fixture: page with `recipeYield: "Makes 12 cookies"` → `metadata.servings = 12`
- [ ] Fixture: page with `prepTime: "PT15M"` → `metadata.prepTimeMins = 15`
- [ ] Confidence always 0.85 for json_ld extraction

### Quota tracker

- [ ] Application start with no row for today → counter = 0
- [ ] Application start with row for today (call_count=23) → counter = 23
- [ ] `recordCall()` after midnight UTC → rolls over; previous day's count persisted; new day starts at 1
- [ ] DB row updated after every `recordCall()` (or batched? — v1 ships eager update; one row UPDATE per call; cheap)

### Migration

- [ ] `V20260615120300` applies cleanly; `discovery_google_cse_usage` table exists with PK on `day`
- [ ] `FlywayMigrationIT` (project-wide) green

### Cross-cutting

- [ ] `SourceRegistry` from 01c picks up `GoogleCustomSearchAdapter` and `ReferenceCuratedSource` at `@PostConstruct` time — registry size = 2
- [ ] `DiscoveryBoundaryTest`'s `discoverySourceImplsLiveInSourcePackage` rule passes (both impls in `discovery.source..`)
- [ ] No regressions on 01a / 01b / 01c / 01d tests
- [ ] No `pom.xml` adds (jsoup, Jackson, RestClient all already in pom)
- [ ] `RestClient` adapter location: the two impls + the JsonLdRecipeExtractor live in `discovery/source/` which is OUTSIDE `domain.service.internal` — the `springWebStaysInApi` rule allows this. **Worth user review** — the project's rule might require `api/internal/` specifically; the agent should grep the rule and relocate if needed.

## Files this ticket touches

```
DEL   src/main/resources/db/migration/R__discovery_seed_source_registry.sql                              (01a placeholder; renamed)
NEW   src/main/resources/db/migration/R__discovery_seed_curated_sources.sql                              (15 sources)
NEW   src/main/resources/db/migration/V20260615120300__discovery_create_google_cse_usage.sql

NEW   src/main/java/com/example/mealprep/discovery/source/GoogleCustomSearchAdapter.java
NEW   src/main/java/com/example/mealprep/discovery/source/ReferenceCuratedSource.java
NEW   src/main/java/com/example/mealprep/discovery/source/internal/JsonLdRecipeExtractor.java
NEW   src/main/java/com/example/mealprep/discovery/source/internal/GoogleCseDailyQuotaTracker.java

NEW   src/main/java/com/example/mealprep/discovery/domain/entity/DiscoveryGoogleCseUsage.java             (single-row-per-day table; @Entity)
NEW   src/main/java/com/example/mealprep/discovery/domain/repository/DiscoveryGoogleCseUsageRepository.java

NEW   src/main/java/com/example/mealprep/discovery/config/GoogleCustomSearchConfig.java                   (@ConfigurationProperties record)

MOD   src/main/java/com/example/mealprep/discovery/config/DiscoveryHttpConfig.java                        (add @EnableConfigurationProperties(GoogleCustomSearchConfig.class); add a discoveryRestClient bean with 10s timeout + 2MB max response — separate from robotsRestClient from 01c)
MOD   src/main/java/com/example/mealprep/discovery/config/DiscoveryProperties.java                        (add Duration sitemapCacheTtl with default PT6H)

MOD   src/main/resources/application.properties                                                           (5 lines for mealprep.discovery.search.google.* defaults)

NEW   src/test/resources/discovery/fixtures/bbc-good-food-recipe.html                                     (JSON-LD recipe fixture)
NEW   src/test/resources/discovery/fixtures/serious-eats-recipe.html                                      (JSON-LD recipe fixture)
NEW   src/test/resources/discovery/fixtures/no-jsonld.html
NEW   src/test/resources/discovery/fixtures/malformed-jsonld.html
NEW   src/test/resources/discovery/fixtures/bbc-sitemap.xml
NEW   src/test/resources/discovery/fixtures/google-cse-response.json

NEW   src/test/java/com/example/mealprep/discovery/JsonLdRecipeExtractorTest.java                         (unit: 5+ fixtures; mapping; tolerance to malformed)
NEW   src/test/java/com/example/mealprep/discovery/GoogleCustomSearchAdapterIT.java                       (WireMock: search 200 / 429 / 503 / timeout / quota-exhausted; fetchRecipe via JSON-LD)
NEW   src/test/java/com/example/mealprep/discovery/ReferenceCuratedSourceIT.java                          (WireMock: sitemap fetch + path filter; cache TTL)
NEW   src/test/java/com/example/mealprep/discovery/GoogleCseDailyQuotaTrackerTest.java                    (unit + IT: count persistence + rollover at UTC midnight via injected Clock)
NEW   src/test/java/com/example/mealprep/discovery/DiscoverySourceSeedIT.java                            (IT: post-migration row count == 15; source_key uniqueness; idempotent re-run)
```

Count: ~17 NEW + 3 MOD + 1 DEL + 6 test fixtures = ~27 files. The fixtures + WireMock setup are the time sink. Estimated agent runtime **50-60 min**.

**Files this ticket does NOT modify**:
- `pom.xml` (jsoup, Jackson, RestClient already in pom)
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- Other modules' files
- Discovery 01a entities/migrations (the new `discovery_google_cse_usage` table is in 01e's V…120300; old migrations not edited)
- Discovery 01b service impl + controllers
- Discovery 01c registries + helpers (consumed only)
- Discovery 01d runner (consumed only — runner discovers the new beans automatically)
- `src/main/resources/openapi/openapi.yaml`

## Dependencies

- **Hard dependency**: `discovery-01a` (merged) — `DiscoveryProperties`, async config, the placeholder `R__discovery_seed_source_registry.sql` (renamed here), the `discovery_sources` table.
- **Hard dependency**: `discovery-01b` (merged) — `DiscoveryServiceImpl` (consumed via REST for IT verification of the seed).
- **Hard dependency**: `discovery-01c` (merged) — `DiscoverySource` SPI, `SourceRegistry`, `RestClient` config infra, `JsonLdRecipeExtractor` is NEW in 01e but mirrors recipe-01b's parser shape, `DiscoverySourceUnavailableException`, `ExtractionFailedException`.
- **Soft dependency**: `discovery-01d` (merged) — without the runner, the new sources aren't exercised end-to-end. 01e's tests stub the runner; 01d's `DiscoveryRunnerIT` is what exercises end-to-end. **01e can ship before 01d** as long as the agent's IT verifies the beans wire and `SourceRegistry.resolveEnabled()` returns 15+ sources.
- **No cross-module dependencies** — discovery's two impls are self-contained. They DO call out to external HTTP, but no Java code in other modules.
- **Sibling tickets running in parallel** (Wave 3 round 5): `planner-01e`, `feedback-01e`, `adaptation-pipeline-01e`. None should touch discovery files. Shared cross-cutting: `application.properties` — 01e adds 5 lines under `mealprep.discovery.search.google.*`; if a sibling also touches `application.properties` for its own config keys, the diff is in different sections.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `DiscoverySourceSeedIT` passes — 15 sources after migration
- [ ] `JsonLdRecipeExtractorTest` exercises 5+ fixtures including malformed JSON tolerance
- [ ] WireMock-backed IT covers Google CSE error matrix (200/429/503/timeout/quota)
- [ ] `DiscoveryBoundaryTest`'s `discoverySourceImplsLiveInSourcePackage` rule trips on a deliberately-placed `@DisabledArchTestCase` impl outside `discovery.source..` if added (optional verification)
- [ ] No `pom.xml` adds
- [ ] No other modules' files touched

## Gotchas embedded (apply during implementation)

- **HTTP-client adapter location**: `discovery/source/` houses the `DiscoverySource` impls per the LLD. The project's `springWebStaysInApi` ArchUnit rule restricts Spring Web types (RestClient, WebClient) to `..api..` or `..config..`. **`GoogleCustomSearchAdapter` and `ReferenceCuratedSource` use RestClient inside `discovery.source.*`** — this MAY trip the rule. **Mitigation**: inject a `RestClient` bean (declared in `discovery/config/DiscoveryHttpConfig.java`) rather than constructing one inline. The bean is in `config`; the source-package impl just calls methods on it. **Whether the rule trips on this pattern is project-specific** — the agent should grep `springWebStaysInApi` and verify. If it trips, the fix is either:
  - Relocate the impls to `discovery/api/internal/` (matches the LLD's "hard pocket" relocation),
  - Refactor the rule to exempt `discovery.source.*`, or
  - Move the HTTP code into a `discovery/source/internal/HttpFetcher` collaborator that the rule does exempt.
  **Worth user review.**

- **Repeatable migration rename**: Flyway tracks repeatables by filename. Renaming `R__discovery_seed_source_registry.sql` → `R__discovery_seed_curated_sources.sql` creates a NEW Flyway history row; the old one is orphaned but inert. If the project's Flyway is configured with `outOfOrder=true` or strict rename detection, verify the rename doesn't fail validation. **Most projects** accept this pattern.

- **Empty-body 01a placeholder**: the 01a `R__discovery_seed_source_registry.sql` was empty (one comment header). Flyway's checksum for empty files is stable (it hashes the body). Deleting the file deletes the history row only at clean (NOT incrementally). For dev environments with existing data, the agent may need `flyway repair` or accept a one-time validation noise during the upgrade.

- **`gen_random_uuid()` Postgres function**: requires the `pgcrypto` extension. Verify it's enabled OR replace with `uuid_generate_v4()` (requires `uuid-ossp`). **Most Spring-Boot-Postgres projects** enable `pgcrypto` by default, but the agent should grep migrations for `CREATE EXTENSION` to confirm. **If neither is enabled**, ADD `CREATE EXTENSION IF NOT EXISTS pgcrypto;` as the first statement of the seed migration.

- **JSON-LD `@graph` traversal**: schema.org pages often wrap recipes in `@graph` arrays. The extractor MUST traverse `@graph` AND handle `@type` as both string and array. Edge cases caught in `JsonLdRecipeExtractorTest`.

- **ISO-8601 duration parsing**: `Duration.parse("PT15M")` works for simple cases. **`Duration.parse("PT1H30M")` works**; `Duration.parse("P1D")` works for days. But: malformed strings throw `DateTimeParseException` — wrap in try/catch and treat as null.

- **`recipeYield` parsing**: schema.org's `recipeYield` is highly inconsistent: `"4"`, `"4 servings"`, `"4-6"`, `"Makes 12 cookies"`, `"6 sandwiches"`. v1 strategy: `Matcher m = Pattern.compile("(\\d+)").matcher(yield); if (m.find()) return Integer.parseInt(m.group(1)); else return null;`. Picks the first integer.

- **`crawl_config` JSONB substitution**: Postgres does NOT expand `${ENV_VAR}` in JSONB literals. Any `${...}` in the seed migration is a literal string. The adapter ignores the JSONB and uses `GoogleCustomSearchConfig.searchEngineId` from `application.properties` (which DOES expand env vars). The JSONB is informational/audit only.

- **`application.properties` placeholder defaults**: `${MEALPREP_GOOGLE_CSE_KEY:placeholder}` provides `placeholder` when env var is unset. The first real CSE call fails with 401; the runner converts to `DiscoverySourceUnavailableException`; the job lands `PARTIAL` or `FAILED`. **Worth user review** — alternative is fail-fast at startup (omit the default). 01e ships the placeholder pattern.

- **`@Configuration class + @Bean factory` (NOT `@Component @ConditionalOnMissingBean` on the impl class)**: N/A for the source impls — they're the v1 default, not Noop placeholders. The SPI-with-Noop pattern applies to `CandidateAiFilter` (01c), not here. The source impls use plain `@Component`.

- **Don't trust LLD column widths blindly**: the new `discovery_google_cse_usage.call_count` is `integer` (4 bytes, max ~2 billion). 100/day × 365 days × ~5 years = 182,500 — well within range. No widening needed.

## What's NOT in scope

- Sync admin endpoint + `runJobSync` + 408 / 502 mappings → **discovery-01f**
- Additional curated source impls beyond `ReferenceCuratedSource` (one per remaining seed row — `SeriousEatsSource`, `AllRecipesSource`, etc.) — each is a 50-80 LOC `@Component` + (optionally) a per-site quirks layer. **All deferred** — the registry tolerates orphan seed rows (the `SourceRegistry.resolveEnabled` warn-on-missing-impl path). Add impls as user prioritises.
- Full 5-layer `RecipeExtractionService` from `recipe-extraction-pipeline.md` (h-recipe microformats / per-site extractor registry / AI HTML fallback / validator / extraction cache table) → separate ticket sequence in the recipe-extraction-pipeline module
- BoilerplateStripper, IngredientLineParser, RecipeSiteExtractor registry → all part of the future recipe-extraction-pipeline tickets
- AI-driven candidate filtering — `CandidateAiFilter` pass-through ships in 01c
- Prompt engineering for the Google CSE query construction (current: deterministic concat of cuisines + dietary flags + "recipe") — future track
- Per-source `quality_score` rolling computation — the column exists from 01a but no writer in 01e (or 01d); update path is future work
- Retention sweep on `discovery_google_cse_usage` (>2 years) — separate `@Scheduled` job
- Operator-facing `/admin/diagnostics` view of daily CSE usage → future admin ticket
- Frontend "find me more recipes" button + scrape-log explorer — Figma phase

Squash-merge with: `feat(discovery): 01e — curated source seed + Google CSE adapter + reference curated source + JSON-LD extractor`
