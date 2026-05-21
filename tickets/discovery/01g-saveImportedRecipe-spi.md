# Ticket: discovery — 01g `saveImportedRecipe` SPI + Real `CandidateAiFilter` (wires discovery end-to-end)

## Summary

Add the `RecipeWriteApi.saveImportedRecipe` SPI method so the discovery module's `DiscoveryJobRunner` can persist scraped recipes into the system catalogue. Per roadmap §B4 in [`design/audits/2026-05-21-frontend-readiness-roadmap.md`](../../design/audits/2026-05-21-frontend-readiness-roadmap.md) and gap-audit **C-C-030** (Online recipe discovery — search + AI filter).

Currently, per the audit, `DiscoveryJobRunner:621-651` writes `EXTRACTION_FAILED` rows because the SPI doesn't exist:
- The discovery pipeline scrapes a candidate URL.
- An extractor parses the recipe (HTML import logic, already wired).
- The runner attempts to hand off to `RecipeWriteApi.saveImportedRecipe(ImportedRecipeData)` — but the method doesn't exist.
- Instead, the runner writes a `discovery_scrape_log` row with `status = EXTRACTION_FAILED`, abandoning the work.

This ticket:

1. **Adds the SPI method** `saveImportedRecipe(ImportedRecipeData)` to `recipe.spi.RecipeWriteApi`.
2. **Implements it** in `RecipeServiceImpl`: creates a `Recipe` with `catalogue=SYSTEM`, `data_quality=WEB_DISCOVERED`, the standard branch+version-1 shape per `recipe-01a`.
3. **Replaces `NoopCandidateAiFilterConfiguration`** with a real `CandidateAiFilter` implementation that calls the existing AI dispatcher with a "is this a relevant recipe candidate?" prompt and rejects low-confidence candidates.
4. **Replaces the EXTRACTION_FAILED branch in `DiscoveryJobRunner`** to call the new SPI and write `status = SUCCESS` + `recipe_id` on success.

After this ticket: discovery jobs **grow the system catalogue end-to-end** — scrape → AI filter → extract → persist via the SPI.

Closes: **C-C-030** (Online recipe discovery — search + AI filter). Closes the audit's "Discovery is deliberate skeleton" cluster.

## Behavioural spec

### `ImportedRecipeData` shape

1. **`ImportedRecipeData`** record at `recipe.spi.ImportedRecipeData`. The carrier shape passed across the SPI from discovery to recipe:
   ```java
   public record ImportedRecipeData(
       String sourceKey,              // discovery_sources.source_key (e.g. "google-cse", "bbcgoodfood-sitemap")
       String canonicalUrl,           // resolved canonical URL of the source page
       String contentFingerprint,     // SHA-256 of the page content (dedup helper)
       String name,                   // recipe name as extracted
       String description,            // nullable
       List<ImportedIngredient> ingredients,
       List<ImportedMethodStep> method,
       ImportedRecipeMetadata metadata,
       ImportedRecipeTags tags,
       String extractionMethod,       // "json-ld", "microdata", "ai-fallback"
       BigDecimal extractionConfidence,
       UUID jobId,                    // discovery_jobs.id — for trace correlation
       UUID traceId
   ) {
     public record ImportedIngredient(
         int lineOrder, String displayName, BigDecimal quantity, String unit,
         String preparation, boolean optional) {}
     public record ImportedMethodStep(int stepNumber, String instruction, Integer durationMinutes) {}
     public record ImportedRecipeMetadata(
         int servings, int prepTimeMins, int cookTimeMins, int totalTimeMins,
         List<String> equipmentRequired, Integer fridgeDays, Integer freezerWeeks,
         boolean packable, String cuisine, List<String> mealTypes) {}
     public record ImportedRecipeTags(
         String protein, String cookingMethod, String complexity,
         List<String> flavourProfile, List<String> dietaryFlags) {}
   }
   ```

### `RecipeWriteApi` SPI extension

2. **`RecipeWriteApi`** at `recipe.spi.RecipeWriteApi`. Existing public SPI (per `recipe-01f`). Add one method:
   ```java
   /**
    * Persists a recipe scraped by the discovery pipeline.
    * - Creates a Recipe with catalogue=SYSTEM, data_quality=WEB_DISCOVERED.
    * - Creates a single 'main' branch + RecipeVersion v1 with trigger=IMPORT.
    * - Persists ingredients + method + metadata + tags.
    * - Idempotency: if a Recipe already exists with the same content_fingerprint
    *   (looked up via the recipe_imports table from recipe-01b), returns the existing
    *   Recipe's id without creating a duplicate.
    * - Returns the saved recipe's id + a flag indicating new-vs-existing.
    *
    * Called by discovery.runner.DiscoveryJobRunner only (enforced by ArchUnit).
    */
   ImportedRecipeResult saveImportedRecipe(ImportedRecipeData data);
   ```
3. **`ImportedRecipeResult`** record at `recipe.spi`:
   ```java
   public record ImportedRecipeResult(
       UUID recipeId, UUID versionId,
       boolean newlyCreated,           // false if dedup-by-fingerprint hit
       String dedupReason              // e.g. "fingerprint-match-with-recipe-<uuid>" — populated when newlyCreated=false
   ) {}
   ```

### `RecipeServiceImpl.saveImportedRecipe` implementation

4. **Add to `RecipeServiceImpl`** (the existing single-impl per `recipe-01a`). New `@Transactional` method:
   - **Step 1**: dedup check via `recipe_imports.content_fingerprint` (table from `recipe-01b`). If existing → return `ImportedRecipeResult(recipeId, versionId, false, "fingerprint-match-...")`.
   - **Step 2**: create the `Recipe` with `catalogue=SYSTEM, dataQuality=WEB_DISCOVERED, nutritionStatus=PENDING, currentVersion=1`.
   - **Step 3**: create the 'main' branch with `createdByActor = "system-discovery:" + jobId`, `adapterTraceId = jobId`.
   - **Step 4**: create the `RecipeVersion v1` with `trigger=IMPORT, changeDiff={}, embeddingStatus='pending', createdByActor = "system-discovery:" + jobId, adapterTraceId = traceId`.
   - **Step 5**: persist ingredients, method steps, metadata, tags.
   - **Step 6**: insert a `recipe_imports` row capturing `sourceKey, canonicalUrl, contentFingerprint, extractionMethod, extractionConfidence, jobId` (per the recipe-01b table).
   - **Step 7**: publish `RecipeCreatedEvent` (catalogue=SYSTEM) and `RecipeVersionCreatedEvent`. The future embedding listener (deferred from `recipe-01h`) picks these up.
   - **Step 8**: return `ImportedRecipeResult(recipeId, versionId, true, null)`.
5. **Origin-tracking**: per `tickets/core/02b`, the recipe module's audit-log writer (if applicable) records `actor_type = SYSTEM` with `origin_trace = "system-discovery:" + jobId`. The `DiscoveryJobRunner` populates `OriginContext` before calling the SPI (Pattern A inheritance from the runner's thread context). **Worth implementer review** — confirm `OriginContext` propagation works for `@Scheduled` / `@Async` paths.

### Real `CandidateAiFilter`

6. **`CandidateAiFilter`** SPI at `discovery.spi.CandidateAiFilter` — interface from `discovery-01c`. Currently `NoopCandidateAiFilter` returns "always accept".
7. **`AiCandidateAiFilter`** at `discovery.spi.internal.AiCandidateAiFilter`. Real impl:
   ```java
   @Component
   @Primary       // overrides the Noop bean
   public class AiCandidateAiFilter implements CandidateAiFilter {
     private final AiDispatcher aiDispatcher;

     @Override
     public FilterResult filter(DiscoveryCandidate candidate, DiscoveryConstraints constraints) {
       AiTask<CandidateFilterResult> task = new CandidateFilterTask(candidate, constraints);
       CandidateFilterResult result = aiDispatcher.run(task);
       return new FilterResult(result.relevant(), result.confidence(), result.reason());
     }
   }
   ```
8. **`CandidateFilterTask`** at `discovery.spi.internal.CandidateFilterTask`. Implements the existing `AiTask` interface from the `ai` module. Prompt template (lives at `src/main/resources/prompts/discovery-candidate-filter.v1.txt`):
   ```
   You are filtering recipe candidates discovered from the web. Given a candidate URL,
   title, and snippet, decide whether this is a relevant recipe to add to the catalogue
   given the user's constraints.

   Candidate:
   URL: {candidate.canonicalUrl}
   Title: {candidate.title}
   Snippet: {candidate.snippet}

   Constraints:
   Cuisines preferred: {constraints.cuisines}
   Dietary flags required: {constraints.dietaryFlags}
   Max prep time (mins): {constraints.maxPrepMins}

   Respond with JSON:
   {
     "relevant": true | false,
     "confidence": 0.00 - 1.00,
     "reason": "<one-sentence explanation>"
   }

   Reject candidates that are:
   - Not actually recipes (blog posts, listicles without recipe content)
   - Obvious duplicates of common recipes (vague titles like "easy chicken")
   - Violations of the constraints

   Be conservative; when uncertain, reject.
   ```
9. **Model tier**: use the `cheap` tier (Haiku) per the existing `ai` module's tier semantics — candidate filtering is high-volume, low-stakes-per-call.
10. **Confidence floor**: filter at `confidence >= mealprep.discovery.candidate-filter.min-confidence` (default 0.6). Below → rejected (and a scrape-log row written with `skip_reason = AI_FILTER_REJECTED`, `extraction_confidence = result.confidence()`).

### `DiscoveryJobRunner` update

11. **`DiscoveryJobRunner`** (existing per `discovery-01d`). Modify the EXTRACTION_FAILED branch (lines 621-651 per the audit reference):
    - **Before**: catches the missing-SPI condition and writes `discovery_scrape_log(status=EXTRACTION_FAILED, error_message="saveImportedRecipe SPI not implemented")`.
    - **After**: builds an `ImportedRecipeData` from the extractor's output and calls `recipeWriteApi.saveImportedRecipe(data)`. On success: writes `discovery_scrape_log(status=SUCCESS, recipe_id=result.recipeId(), skip_reason=null)`. On `RecipeWriteFailedException` (or any caught exception): writes `discovery_scrape_log(status=EXTRACTION_FAILED, error_class=ex.getClass().getName(), error_message=ex.getMessage())`.
12. **Idempotency**: if `saveImportedRecipe` returns `newlyCreated=false`, the runner writes `discovery_scrape_log(status=DUPLICATE, skip_reason=DUPLICATE, recipe_id=result.recipeId())`.
13. **Real AI filter**: the runner's `filterCandidate` call now invokes the new `AiCandidateAiFilter`. Rejected candidates → `discovery_scrape_log(status=SKIPPED, skip_reason=AI_FILTER_REJECTED)`.
14. **`DiscoveryRecipeIngestedEvent`** (per `discovery-01d`'s declared event) — confirmed published after each successful SPI call. No v1 listeners; reserved for future analytics.

### Replace the Noop config

15. **Delete** `NoopCandidateAiFilterConfiguration` (the audit notes its existence). Replace with `AiCandidateAiFilterConfiguration` that registers the real bean:
    ```java
    @Configuration
    public class AiCandidateAiFilterConfiguration {
      @Bean
      public CandidateAiFilter candidateAiFilter(AiDispatcher aiDispatcher) {
        return new AiCandidateAiFilter(aiDispatcher);
      }
    }
    ```
16. **Configuration property**:
    ```properties
    mealprep.discovery.candidate-filter.min-confidence=0.6
    mealprep.discovery.candidate-filter.prompt-template=discovery-candidate-filter.v1
    ```

### Cross-cutting

17. New exception:
    - `RecipeImportFailedException` (500) — thrown by `RecipeServiceImpl.saveImportedRecipe` on persistence failure. Caught by the runner, logged, written to scrape log.
18. `RecipeExceptionHandler` gains the mapping.
19. **ArchUnit rule** (added to `RecipeBoundaryTest` per `recipe-01a`): the `RecipeWriteApi.saveImportedRecipe` method is callable only from `discovery..` (`@Order` rule). Discovery is the sole consumer.

### Events

20. **Published** (via `recipe-01a`'s existing event publication, now triggered through the new path):
    - `RecipeCreatedEvent` (catalogue=SYSTEM)
    - `RecipeVersionCreatedEvent`
    - `DiscoveryRecipeIngestedEvent` (per `discovery-01d`, fired by the runner)
21. **Consumed**: none.

## Database

**No new migration in this ticket.** The `recipe_imports` table from `recipe-01b` is the dedup home for content fingerprints; if `recipe-01b` deferred that table, this ticket adds it as:

```sql
-- Optional: only if recipe-01b hasn't shipped this table yet.
-- Verify against the codebase at agent start; skip this migration if recipe_imports exists.

CREATE TABLE recipe_imports (
    id                       uuid PRIMARY KEY,
    recipe_id                uuid NOT NULL REFERENCES recipe_recipes(id) ON DELETE CASCADE,
    source_key               varchar(64) NOT NULL,
    canonical_url            varchar(2048) NOT NULL,
    content_fingerprint      varchar(64) NOT NULL,
    extraction_method        varchar(32) NOT NULL,
    extraction_confidence    numeric(4,3) NOT NULL,
    job_id                   uuid,
    imported_at              timestamptz NOT NULL,
    UNIQUE (content_fingerprint)
);
CREATE INDEX idx_recipe_imports_source ON recipe_imports (source_key, imported_at DESC);
```
If shipped here: `V20260615230000__recipe_create_recipe_imports_table.sql`.

**Worth implementer review** at agent start: check whether `recipe_imports` exists. If yes → no migration. If no → ship this one.

## OpenAPI updates

**No OpenAPI changes.** The SPI is in-process; no new HTTP surface. Discovery's existing admin endpoints already cover the job state (per `discovery-01f`).

## Edge-case checklist

- [ ] **SPI method signature**: `RecipeWriteApi.saveImportedRecipe(ImportedRecipeData) → ImportedRecipeResult` compiles and is callable from discovery.
- [ ] **`NoopCandidateAiFilterConfiguration` deleted** (or rewritten as `AiCandidateAiFilterConfiguration`).
- [ ] **Happy path**: discovery scrapes a candidate, AI filter approves at confidence 0.85, extractor produces an `ImportedRecipeData`, SPI persists → new Recipe row with `catalogue=SYSTEM, data_quality=WEB_DISCOVERED`. `RecipeCreatedEvent` and `RecipeVersionCreatedEvent` fire `AFTER_COMMIT`.
- [ ] **AI filter rejects**: confidence 0.3 → runner writes `discovery_scrape_log(status=SKIPPED, skip_reason=AI_FILTER_REJECTED)`. No Recipe created.
- [ ] **Dedup**: a candidate with the same `content_fingerprint` as an existing recipe → SPI returns `newlyCreated=false, dedupReason="..."`. Runner writes `status=DUPLICATE, skip_reason=DUPLICATE, recipe_id=<existing>`.
- [ ] **SPI failure**: a malformed `ImportedRecipeData` (e.g. empty ingredients) → SPI throws `RecipeImportFailedException`; runner writes `status=EXTRACTION_FAILED, error_class=..., error_message=...`.
- [ ] **Origin attribution**: any audit-log row written during the SPI call (e.g. if `recipe_versions` has audit hooks) carries `actor_type=SYSTEM, origin_trace="system-discovery:<jobId>"`.
- [ ] **No EXTRACTION_FAILED-due-to-missing-SPI rows** — verified by inspecting the runner's code path.
- [ ] **Catalogue value**: every imported recipe has `catalogue=SYSTEM`.
- [ ] **Data quality value**: every imported recipe has `data_quality=WEB_DISCOVERED` (per `lld/recipe.md`).
- [ ] **Branch / version shape**: the imported recipe has exactly one branch ('main') and one version (v1) with `trigger=IMPORT`.
- [ ] **`created_by_actor`**: `"system-discovery:<jobId>"` on both branch and version.
- [ ] **`adapterTraceId`**: matches the discovery job's `traceId`.
- [ ] **`recipe_imports` row**: written for each successful import with the source data captured.
- [ ] **Unique fingerprint constraint**: a second import attempt with the same fingerprint → caught by the `UNIQUE` constraint in `recipe_imports` and surfaced as the dedup path.
- [ ] **AI dispatcher integration**: the candidate filter task uses the `cheap` model tier; verified via the AI dispatcher's metrics.
- [ ] **Prompt template loaded**: `discovery-candidate-filter.v1.txt` resolved from classpath; missing → startup failure (not runtime).
- [ ] **Confidence floor configurable**: setting `mealprep.discovery.candidate-filter.min-confidence=0.8` raises the bar.
- [ ] **ArchUnit**: only `discovery..` classes call `RecipeWriteApi.saveImportedRecipe(...)` — verified by AspectJ-style rule.
- [ ] **End-to-end IT** (`DiscoveryEndToEndIT`): full pipeline runs against a mocked AI dispatcher (returning relevance=true, confidence=0.9 for the test candidate) → recipe lands in `recipe_recipes` with the expected `catalogue=SYSTEM` shape.
- [ ] **Trace propagation**: the discovery job's `traceId` flows through the SPI call into the recipe's `adapterTraceId` field.

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/recipe/spi/ImportedRecipeData.java                       (record + nested records)
NEW   src/main/java/com/example/mealprep/recipe/spi/ImportedRecipeResult.java
MOD   src/main/java/com/example/mealprep/recipe/spi/RecipeWriteApi.java                           (add saveImportedRecipe method)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java    (implement the new method)

NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeImportFailedException.java
MOD   src/main/java/com/example/mealprep/recipe/api/RecipeExceptionHandler.java                   (1 new mapping)

(Conditional, if recipe_imports table doesn't yet exist:)
NEW   src/main/resources/db/migration/V20260615230000__recipe_create_recipe_imports_table.sql
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeImport.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeImportRepository.java

DEL   src/main/java/com/example/mealprep/discovery/config/NoopCandidateAiFilterConfiguration.java (replaced)
NEW   src/main/java/com/example/mealprep/discovery/config/AiCandidateAiFilterConfiguration.java
NEW   src/main/java/com/example/mealprep/discovery/spi/internal/AiCandidateAiFilter.java
NEW   src/main/java/com/example/mealprep/discovery/spi/internal/CandidateFilterTask.java
NEW   src/main/java/com/example/mealprep/discovery/spi/internal/CandidateFilterResult.java
NEW   src/main/resources/prompts/discovery-candidate-filter.v1.txt

MOD   src/main/java/com/example/mealprep/discovery/runner/DiscoveryJobRunner.java                 (lines 621-651: SPI handoff + AI filter)
MOD   src/main/resources/application.properties                                                   (candidate-filter properties)
MOD   src/test/java/com/example/mealprep/recipe/RecipeBoundaryTest.java                            (SPI usage rule)

NEW   src/test/java/com/example/mealprep/recipe/SaveImportedRecipeIT.java                          (Testcontainers — happy + dedup + fail)
NEW   src/test/java/com/example/mealprep/discovery/AiCandidateAiFilterTest.java                    (mocks AI dispatcher)
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryEndToEndIT.java                        (full pipeline; mocked AI; real DB)
NEW   src/test/java/com/example/mealprep/discovery/testdata/ImportedRecipeDataTestData.java
```

Total: ~12 new + 5 mods + 1 delete. Estimated agent runtime 4-6 hours.

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe`, `RecipeBranch`, `RecipeVersion` entities.
- **Hard dependency**: `recipe-01f` (merged) — `RecipeWriteApi` SPI interface (the existing one this ticket extends).
- **Hard dependency**: `discovery-01a..01f` (merged) — runner, source SPI, scrape log.
- **Hard dependency**: `ai` module (merged) — `AiDispatcher`, `AiTask` interface, cheap-tier configuration.
- **Hard dependency**: `tickets/core/02b-origin-tracking-foundation.md` — `Origin.SYSTEM_DISCOVERY`, `OriginContext` for audit attribution.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit)
- [ ] All edge-case items above ticked
- [ ] `DiscoveryEndToEndIT` proves the full pipeline runs
- [ ] PR description includes a trace: discovery job started → candidate filtered → recipe ingested → catalogue row visible
- [ ] **The "Discovery is deliberate skeleton" audit finding is closed** — discovery now grows the system catalogue end-to-end

## What's NOT in scope

- **Per-source extractor reliability** (HTML parsing for specific sites) — owned by the existing recipe-extraction-pipeline LLD.
- **Multi-language extraction** — out of scope for v1.
- **Quality scoring of imported recipes** — `discovery_sources.quality_score` is updated by a separate scheduler; not this ticket.
- **User-facing "show me imported recipes" UI** — frontend phase.
- **Cross-tenant visibility of WEB_DISCOVERED recipes** — they're catalogue=SYSTEM, visible to all authenticated users (per `recipe-01a`'s read-by-others contract).
- **AI-driven ingredient mapping** for newly-imported recipes — uses the existing USDA mapping pipeline.
- **`pending_changes` flow** for AI-driven post-import refinements — separate adaptation-pipeline concern.

Squash-merge with: `feat(discovery): 01g — saveImportedRecipe SPI + real CandidateAiFilter (closes C-C-030; wires discovery end-to-end)`
