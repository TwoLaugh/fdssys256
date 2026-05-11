# Ticket: nutrition — 01d Ingredient Mapping Cache + USDA / OFF Clients + Lookup Endpoints

## Summary

Layer the **ingredient-mapping cache + external lookup pipeline** on top of the 01a/01b/01c nutrition module: `IngredientMapping` aggregate root (`nutrition_ingredient_mapping` table with JSONB `nutrition_per_100g`), the two HTTP clients `UsdaApiClient` + `OpenFoodFactsClient` in `nutrition/config/` (per the ArchUnit `springWebStaysInApi` rule), the `IngredientMappingPipeline` orchestrator implementing cache-check → AI-parse-skip → USDA-search → OFF-fallback → AI-match → persist (per [LLD §Flow 6 lines 970-982](../../lld/nutrition.md)), and the four `IngredientLookupController` endpoints under `/api/v1/nutrition/ingredients`: `GET /lookup?term=`, `POST /search` (paginated UI search of the cache only), `PUT /{searchTerm}/correction` (user correction with optimistic-lock), `GET /needs-review?page=&size=` (admin / feedback paging of `needs_review = true` rows). Plus the cross-module read helpers `lookupIngredient(searchTerm)`, `lookupIngredients(searchTerms)`, `getMappingsNeedingReview(Pageable)` on `NutritionQueryService` (verbatim from LLD lines 687-690). Per [`lld/nutrition.md`](../../lld/nutrition.md) §V20260502120600 (migration lines 269-286), §`IngredientNutritionDto` / `IngredientNutritionDocument` (lines 479-490), §`IngredientMappingMapper` (lines 586-589), §`IngredientMappingRepository` (lines 638-644), §`NutritionUpdateService.correctIngredientMapping` (line 729), §`IngredientLookupController` (lines 822-831), §Flow 6 (lines 970-982).

**Defers** (still out of scope after 01d):

- AI-parse step (`IngredientParseTask`) and AI-match step (`IngredientMatchTask`) — owned by the **AI module's** task catalogue (LLD line 976 — "call `IngredientParseTask` to produce `{ingredient, quantity, unit, gramsEstimate, usdaSearchTerm, isCooked}`"). **01d stubs the AI calls** — see "LLD divergence note: AI steps stubbed" below.
- `NutritionCalculationService` (recipe save-time + `RecipeEvolvedEvent` listener) → **nutrition-01f**
- Snack-log integration (calling `IngredientMappingPipeline.resolve` from `logSnack`) → **nutrition-01l** (LLD line 716, Flow 7)
- Free-text override parsing on `overrideIntakeFromFreeText` (which calls `IngredientParseTask` then the pipeline) → **nutrition-01k** (LLD Flow 5)
- Health directives queue → **nutrition-01e**
- DRI seed data (`R__nutrition_seed_dri_defaults.sql`) — moved here per 01c's deferral list. **01d ships the seed** (single repeatable migration).
- `NutritionFloorGateService` → **nutrition-01g**
- Weekly aggregates + `DivergenceDetector` → **nutrition-01h**

01d unblocks the **ingredient-lookup primitive** for the recipe-save flow (01f) and the snack-log flow (01l). Without 01d, both flows have nothing to call: every recipe save would store `nutritionStatus = pending` indefinitely, and snacks would have no macro/micro nutrition derivation.

**LLD divergence note** — **AI steps stubbed in 01d**: LLD Flow 6 step 3 (line 976) invokes `IngredientParseTask` to AI-parse free text into `{ingredient, quantity, unit, gramsEstimate, usdaSearchTerm, isCooked}`, and step 5 (line 978) invokes `IngredientMatchTask` to AI-pick the best USDA/OFF entry. **The AI module's task catalogue is not yet built** (the wider AI module is `lld/ai.md`). 01d's pipeline:
- **Step 3 (parse)** — when the request lacks `usdaSearchTerm` (i.e. the public `/lookup?term=` endpoint), **use the input `term` as the `usdaSearchTerm` verbatim**. Skip the AI parse. This works for the common case where the user types "chicken breast" rather than "1 cup of cubed boneless skinless chicken breast"; the precise free-text → structured-form parser is the AI task's job, deferred. Internal callers (snack log in 01l) supply pre-structured input — they already have an `ingredientMappingKey` and skip the pipeline entirely (LLD line 989).
- **Step 5 (match)** — when USDA returns top-5 candidates, **pick the first** (highest USDA score per their relevance ranking). Confidence is set to `min(usda.score, 0.85)` (cap at 0.85 because without AI re-ranking we don't trust USDA's score above that ceiling; `needsReview = (confidence < 0.7)` per LLD line 979). When the AI module's `IngredientMatchTask` lands, 01d's `MatchSelector` `@Component` interface is wired to delegate; today it's a `FirstMatchSelector` implementation.
- **Worth user review**: the alternative is to defer the entire pipeline to a later ticket and ship only the cache + endpoints in 01d. Rejected because the cache + endpoints with no pipeline behind them are useless — every cache miss would 404 on `/lookup` and the only ingestion path would be `correctIngredientMapping` (manual data entry). With the stubbed pipeline, USDA cache misses populate cleanly; precision improves later when AI re-ranking lands.

**LLD divergence note** — **`searchIngredientsForUi` scope**: LLD §`NutritionQueryService` line 689 declares `searchIngredientsForUi(IngredientLookupRequest)` returning `IngredientLookupResultDto { hits: List<IngredientNutritionDto>, cacheOnly: boolean }`. **01d implements `cacheOnly = true` only** — `POST /search` returns cached rows whose `search_term` matches LIKE `%q%`. The `cacheOnly = false` path (live USDA/OFF search exposed to the UI for "discover new ingredients") is **deferred to nutrition-01m** because it requires the live AI tasks and would lift the per-request latency past acceptable UI bounds. The DTO field is left in place so the contract doesn't break later.

**LLD divergence note** — **HTTP client placement**: per the agent-prompt-template gotcha list ("HTTP-client adapters must live in `..api..` or `..config..`"), `UsdaApiClient` and `OpenFoodFactsClient` are `@Component` classes in `com.example.mealprep.nutrition.config` (NOT `domain.service.internal`). The pipeline orchestrator (`IngredientMappingPipeline`) lives in `domain/service/internal/` — it depends on the clients via constructor injection, not on Spring Web types.

## Behavioural spec

### Aggregate shape — `IngredientMapping`

1. `IngredientMapping` is a **standalone aggregate root** per [LLD §Entities line 363](../../lld/nutrition.md): "Reference data with light updates. `@Version` because the user-correction flow mutates and concurrent corrections must collide cleanly. `nutritionPer100g` mapped to `IngredientNutritionDocument` record via JSONB."
2. Fields per [LLD V20260502120600 lines 270-281](../../lld/nutrition.md):
   - `id (UUID, application-set)`
   - `searchTerm (varchar 255 NOT NULL UNIQUE — always lowercase + trimmed via `IntakeKeyNormaliser`)`
   - `source (IngredientMappingSource enum: USDA | OPEN_FOOD_FACTS | MANUAL; varchar(24); lowercase in DB to match the LLD's `'usda' | 'open_food_facts' | 'manual'` literals from line 273)`
   - `externalId (varchar(64) nullable — USDA FDC id; null for `MANUAL`)`
   - `nutritionPer100g (jsonb NOT NULL — mirrored by `IngredientNutritionDocument` record; JSONB via `@Type(JsonType.class)` from `hypersistence-utils-hibernate-63`)`
   - `defaultPieceGrams (Integer nullable — for "1 chicken breast" style)`
   - `confidence (BigDecimal(4,3) NOT NULL — 0.000..1.000 range; LLD line 277 spec)`
   - `needsReview (boolean NOT NULL DEFAULT false; true when `confidence < 0.7` per LLD line 979)`
   - `lastVerifiedAt (Instant nullable — null until the user explicitly confirms via correction or admin review)`
   - `optimisticVersion (@Version Long, column name `version` per LLD pattern; **NOTE**: LLD doesn't pin the column name to `optimistic_version` like `IntakeDay` does — 01d uses `version` matching `Budget` / `Equipment` from 01a/01b)`
   - `createdAt (@CreatedDate)`, `updatedAt (@LastModifiedDate)`
3. **Database constraints / indexes** per [LLD lines 282-285](../../lld/nutrition.md):
   - `UNIQUE (search_term)` (and `CREATE UNIQUE INDEX idx_nutr_ingredient_mapping_search_term` — redundant but explicit per LLD line 283).
   - `CREATE INDEX idx_nutr_ingredient_mapping_needs_review ON ... (needs_review) WHERE needs_review = true` — partial; backs the `/needs-review` admin endpoint efficiently.
4. **`IntakeKeyNormaliser`** is a `@Component` in `nutrition/domain/service/internal/`: single method `String normalise(String raw)` — lowercase + trim + collapse internal whitespace to single space. **All writes** to `search_term` go through it; **all reads** by `search_term` go through it. Idempotent (`normalise(normalise(x)) == normalise(x)`). LLD line 974 — "Same normalisation as Provisions" (the provisions module may share the helper later; 01d puts it in nutrition for now).

### `IngredientNutritionDocument` JSONB record

5. Verbatim from [LLD line 483](../../lld/nutrition.md):
   ```java
   public record IngredientNutritionDocument(
       Integer calories, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG, BigDecimal fibreG,
       BigDecimal saturatedFatG, BigDecimal sugarG,
       Map<String, BigDecimal> micros, Map<String, BigDecimal> vitamins
   ) {}
   ```
   All scalar fields nullable (USDA / OFF returns are sparse). `micros` / `vitamins` keys are normalised by the AI module's catalogue when it lands; 01d accepts whatever USDA emits (raw nutrient names).

### `UsdaApiClient` — `nutrition/config/`

6. `UsdaApiClient` is a `@Component` in `com.example.mealprep.nutrition.config` (NOT `domain.service.internal` — see HTTP-client gotcha above). Uses Spring `RestClient` (Spring Boot 3.2+ idiom) configured via `RestClient.Builder` bean wired from `UsdaApiConfig` (also new in 01d).
7. Single public method `Optional<UsdaSearchResultDto> search(String searchTerm)`. Returns `Optional.empty()` on:
   - HTTP 200 with empty `foods` array
   - HTTP 4xx (other than 429 rate-limit — that propagates `UsdaRateLimitedException`)
   - Network unreachable (caller retry-decorates; see Resilience4j config below)
8. **Resilience4j** decorators per [LLD line 977](../../lld/nutrition.md):
   - `@Retry(name = "usda", fallbackMethod = "searchFallback")` — 2 attempts on 5xx. Configure under `resilience4j.retry.instances.usda.max-attempts=2`, `retry-exceptions=org.springframework.web.client.HttpServerErrorException`.
   - `@RateLimiter(name = "usda")` — 1000 req/h. Configure `resilience4j.ratelimiter.instances.usda.limit-for-period=1000` + `limit-refresh-period=PT1H`.
   - `searchFallback(String term, Throwable t)` returns `Optional.empty()` and logs at WARN.
9. **Configuration**: API key + base URL via `UsdaApiConfig` (`@ConfigurationProperties("mealprep.nutrition.usda")`): `apiKey`, `baseUrl` (default `https://api.nal.usda.gov/fdc/v1`). **No live key in repo** — IT uses WireMock; the `application-test.yml` sets a placeholder key.

### `OpenFoodFactsClient` — `nutrition/config/`

10. `OpenFoodFactsClient` mirrors `UsdaApiClient` — `@Component` in `nutrition/config/`. `Optional<OffSearchResultDto> search(String searchTerm)`. Hits `https://world.openfoodfacts.org/cgi/search.pl?search_terms=...&json=1`. No API key required (public endpoint).
11. **Resilience4j**: `@Retry(name = "off")` (2 attempts on 5xx), `@RateLimiter(name = "off")` (100 req/h — OFF's documented soft limit is lower than USDA's; conservative). Same fallback shape.
12. Configuration: `OpenFoodFactsConfig` (`@ConfigurationProperties("mealprep.nutrition.open-food-facts")`): `baseUrl`.

### `IngredientMappingPipeline` — `domain/service/internal/`

13. `IngredientMappingPipeline` is a `@Component` in `nutrition/domain/service/internal/`. Single public method `IngredientMappingResult resolve(IngredientLookupInput input)` where:
    - `IngredientLookupInput { String rawTerm /* user-provided, pre-normalisation */, BigDecimal gramsEstimate /* nullable for `/lookup?term=` */ }` — internal record.
    - `IngredientMappingResult` is a sealed interface with two variants: `Resolved(IngredientNutritionDto dto)` and `Unmapped(UnmappedIngredientDto unmapped)`.
14. **Flow** (LLD Flow 6 lines 974-980):
    1. **Normalise** via `IntakeKeyNormaliser.normalise(input.rawTerm())` → `searchTerm`.
    2. **Cache check** — `ingredientMappingRepository.findBySearchTerm(searchTerm)`.
       - **Hit** → return `Resolved(mapper.toDto(entity))`. **No `lastVerifiedAt` update** — that's correction-only.
       - **Miss** → continue.
    3. **AI parse** — **SKIPPED in 01d** (LLD divergence note above). `usdaSearchTerm = searchTerm`.
    4. **USDA search** — `usdaApiClient.search(usdaSearchTerm)`. Empty → continue to step 6.
    5. **AI match selection** — **SKIPPED in 01d** (LLD divergence note above). `FirstMatchSelector.pick(usdaResults)` returns the first result.
    6. **OFF fallback** — only if USDA returned empty. `openFoodFactsClient.search(searchTerm)`. Empty → `Unmapped(UnmappedIngredientDto(rawTerm, "no source matches", BigDecimal.ZERO))`.
    7. **Persist** — build `IngredientMapping` row with `confidence = min(source.score, 0.85)`, `needsReview = (confidence < 0.7)` (LLD line 979). `searchTerm` is the normalised form. **Idempotency**: `try { repo.saveAndFlush(...) } catch (DataIntegrityViolationException) { repo.findBySearchTerm(searchTerm).orElseThrow() }` — concurrent inserts of the same `search_term` race; the loser re-reads and uses the winner's row (LLD line 979 — "no retry storm").
    8. Return `Resolved(mapper.toDto(savedOrReadEntity))`.
15. **Transactional shape** (LLD line 982 — "in-transaction"): `@Transactional` (default REQUIRED) on `resolve`. Joins the caller's transaction when invoked from `correctIngredientMapping` or future `logSnack`. The HTTP calls happen inside the transaction; long latency is a known trade-off per LLD line 982 ("Worth user review — long latency could degrade recipe-save UX; novel ingredients could later move to a background queue"). **01d does NOT introduce async resolution.**

### `lookupIngredient` (cross-module read)

16. `Optional<IngredientNutritionDto> lookupIngredient(String searchTerm)` — appended to `NutritionQueryService`. **Read-only**: normalises, cache-check via `findBySearchTerm`, returns `Optional<IngredientNutritionDto>`. **NO pipeline invocation** — pure cache read. The pipeline-invoking variant is `IngredientLookupController.lookup` (Flow 16 below); this method is for cross-module callers that already have a `searchTerm` they want to read without triggering an external call.
17. `List<IngredientNutritionDto> lookupIngredients(Collection<String> searchTerms)` — appended to `NutritionQueryService`. Batch sibling. Normalises each, calls `findBySearchTermIn(Collection<String>)` (LLD line 640). Returns only the cache hits; missing terms are silently absent from the result (caller cross-references by `dto.searchTerm()`).

### `GET /api/v1/nutrition/ingredients/lookup?term=`

18. Authenticated. `term` query parameter is required (`@RequestParam @NotBlank @Size(max = 255) String term`). Server-resolved `userId` is NOT used (lookup is non-personalised; the cache is global per LLD line 270 — `nutrition_ingredient_mapping` table is user-agnostic).
19. **Pipeline-invoking** read: server normalises, invokes `IngredientMappingPipeline.resolve(new IngredientLookupInput(term, null))`.
    - `Resolved(dto)` → 200 with `IngredientNutritionDto`.
    - `Unmapped(_)` → 404 `IngredientMappingNotFoundException` with `ProblemDetail` body. **LLD line 853** names the exception.
20. **Caching**: response is **not** cacheable by the client unless `lastVerifiedAt != null` (i.e. a user has confirmed via correction). The HTTP response sets `Cache-Control: no-store` on the unverified path. 01d does **not** ship `ETag`s.

### `POST /api/v1/nutrition/ingredients/search`

21. Authenticated. Body: `IngredientLookupRequest { @NotBlank @Size(max = 255) String query, @Min(1) @Max(20) Integer maxResults /* nullable; default 10 */ }`.
22. **Cache-only**: invokes `ingredientMappingRepository.searchByTerm(query, PageRequest.of(0, maxResults))` (LLD line 642 — `LIKE '%q%'` over `search_term`). Returns `IngredientLookupResultDto { hits: List<IngredientNutritionDto>, cacheOnly: true }`.
23. **Note**: this endpoint does NOT trigger USDA/OFF calls (LLD divergence note above). For "discover new ingredients" UX, the user calls `/lookup?term=` which goes through the pipeline.

### `PUT /api/v1/nutrition/ingredients/{searchTerm}/correction`

24. Authenticated. Server resolves `actorUserId` via `CurrentUserResolver`. Path: `searchTerm` is URL-encoded; the server normalises before lookup (so `Chicken Breast` and `chicken%20breast` both target `chicken breast`).
25. Body: `CorrectIngredientMappingRequest { @NotNull @Valid IngredientNutritionDocument override, long expectedVersion }`.
26. Single `@Transactional` write:
    - `findBySearchTerm(normalised)` → 404 `IngredientMappingNotFoundException` if missing.
    - **Stale `expectedVersion`** → 409 via `OptimisticLockingFailureException` (mapped by `GlobalExceptionHandler`).
    - Update `nutritionPer100g = request.override()`, `source = MANUAL`, `confidence = 1.0` (user-confirmed beats source confidence), `needsReview = false`, `lastVerifiedAt = Instant.now()`.
    - JPA bumps `@Version`. Return 200 with updated `IngredientNutritionDto`.
27. **Event**: publish `IngredientMappingCorrectedEvent(UUID id, String searchTerm, UUID actorUserId, UUID traceId, Instant occurredAt)` `AFTER_COMMIT`. **LLD divergence**: LLD §Events (line 1049) does not declare this event; 01d adds it because the (future) Feedback System wants to subscribe to corrections to update classifier context. Cost is one record class; no listeners in 01d.

### `GET /api/v1/nutrition/ingredients/needs-review?page=&size=`

28. Authenticated. **No admin-role gating in v1** (LLD doesn't specify; treat as "any authenticated user can see what needs review"). Returns `Page<IngredientNutritionDto>` sorted `updatedAt DESC` per LLD line 641. Repository: `findByNeedsReviewTrueOrderByUpdatedAtDesc(Pageable)`. Default size 20, max 100.

### Service interfaces — append-only to existing 01a/01b/01c interfaces

29. Append to `NutritionQueryService` (already declares `getJournalEntriesFor*` from 01c):
    ```java
    Optional<IngredientNutritionDto> lookupIngredient(String searchTerm);
    List<IngredientNutritionDto> lookupIngredients(Collection<String> searchTerms);
    IngredientLookupResultDto searchIngredientsForUi(IngredientLookupRequest request);
    Page<IngredientNutritionDto> getMappingsNeedingReview(Pageable pageable);
    ```
    Verbatim from [LLD lines 687-690](../../lld/nutrition.md).
30. Append to `NutritionUpdateService`:
    ```java
    IngredientNutritionDto correctIngredientMapping(String searchTerm, IngredientNutritionDocument override,
                                                    UUID actorUserId);
    ```
    Verbatim from [LLD line 729](../../lld/nutrition.md). **Note**: 01d uses a `CorrectIngredientMappingRequest` DTO wrapping `override` + `expectedVersion` on the wire; the service-interface signature stays per LLD (the controller maps wire-DTO → `(searchTerm, override, actorUserId)` plus carries `expectedVersion` to the service via a thread-local-free mechanism — **decision: widen the interface to take `expectedVersion` too**: `correctIngredientMapping(String searchTerm, IngredientNutritionDocument override, long expectedVersion, UUID actorUserId)`. **LLD divergence noted**.

### Repository — new

31. ```java
    interface IngredientMappingRepository extends JpaRepository<IngredientMapping, UUID> {
      Optional<IngredientMapping> findBySearchTerm(String searchTerm);
      List<IngredientMapping> findBySearchTermIn(Collection<String> searchTerms);
      Page<IngredientMapping> findByNeedsReviewTrueOrderByUpdatedAtDesc(Pageable p);
      @Query("select im from IngredientMapping im where lower(im.searchTerm) like concat('%', lower(:q), '%')")
      Page<IngredientMapping> searchByTerm(@Param("q") String query, Pageable p);
    }
    ```
    Verbatim from [LLD lines 638-644](../../lld/nutrition.md). Package-private.
32. **Boundary**: existing `NutritionBoundaryTest` from 01a covers the new repo (`domain/repository/`). **No changes to the test**.

### Errors

33. New module exception subclasses extending the existing `NutritionException` from 01a:
    - `IngredientMappingNotFoundException` (404, `type = .../ingredient-mapping-not-found`) — LLD line 853 names it.
    - `IngredientMappingPipelineException` (422, `type = .../ingredient-mapping-pipeline`) — LLD line 855 names it. Thrown for unrecoverable pipeline failures (e.g. both USDA and OFF return malformed JSON repeatedly; out-of-scope for the happy paths above but the exception class ships so it's available for nutrition-01l's snack-log flow).
34. **Append two new `@ExceptionHandler` methods** to the existing `NutritionExceptionHandler` `@RestControllerAdvice` from 01a (already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`.

## Database

```
src/main/resources/db/migration/V20260601601100__nutrition_create_ingredient_mapping.sql   new
src/main/resources/db/migration/R__nutrition_seed_dri_defaults.sql                          new (deferred from 01c per parent instruction; repeatable migration)
```

Schema mirrors [LLD V20260502120600 lines 270-286](../../lld/nutrition.md), renumbered to the nutrition timestamp range (`V20260601601100` is the next free slot after 01c's `V20260601601000__nutrition_create_food_mood_journal.sql`):

```sql
-- V20260601601100
CREATE TABLE nutrition_ingredient_mapping (
    id                  uuid PRIMARY KEY,
    search_term         varchar(255) NOT NULL UNIQUE,        -- always lowercase + trimmed via IntakeKeyNormaliser
    source              varchar(24) NOT NULL,                -- usda | open_food_facts | manual
    external_id         varchar(64),
    nutrition_per_100g  jsonb NOT NULL,
    default_piece_grams integer,
    confidence          numeric(4,3) NOT NULL,
    needs_review        boolean NOT NULL DEFAULT false,
    last_verified_at    timestamptz,
    version             bigint NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_nutr_ingredient_mapping_search_term
    ON nutrition_ingredient_mapping (search_term);
CREATE INDEX idx_nutr_ingredient_mapping_needs_review
    ON nutrition_ingredient_mapping (needs_review)
    WHERE needs_review = true;
```

**DRI defaults seed** (`R__nutrition_seed_dri_defaults.sql`) — repeatable migration loading micronutrient daily-recommended-intake defaults keyed by `(age_group, sex)`. Used at `initialiseTargets` time by 01a's `NutritionTargetsService` (which currently seeds an empty micro-target list because the seed isn't there yet). **01d adds the seed** but does NOT modify 01a's `initialiseTargets` code path — the seed table can sit dormant until a future nutrition-01a-patch ticket wires it. **LLD divergence noted** — 01c's deferral list said "moved to nutrition-01d because that ticket layers ingredient lookups." The DRI seed is unrelated to ingredient lookups but is a natural fit for this slot.

Table for the seed (new in 01d — small, reference-only):

```sql
-- Part of R__nutrition_seed_dri_defaults.sql (CREATE TABLE IF NOT EXISTS + idempotent UPSERT seed data)
CREATE TABLE IF NOT EXISTS nutrition_dri_defaults (
    id              uuid PRIMARY KEY,
    age_group       varchar(16) NOT NULL,                    -- '4-8' | '9-13' | '14-18' | '19-30' | '31-50' | '51-70' | '71+'
    sex             varchar(8) NOT NULL,                     -- 'male' | 'female' | 'unspecified'
    micro_name      varchar(64) NOT NULL,
    rda_value       numeric(10,3) NOT NULL,
    unit            varchar(16) NOT NULL,
    UNIQUE (age_group, sex, micro_name)
);
-- Idempotent UPSERTs follow (iron, calcium, vit C, B12, folate, magnesium, zinc — ~7 rows × 7 age groups × 3 sex = ~147 rows).
-- Specifics left to the agent; values from NIH ODS reference data.
```

**Worth user review** — the DRI seed needs values from an authoritative source (NIH ODS); the agent should populate a minimal viable set (7 most-tracked micros × 3 age groups × 2 sexes = 42 rows) and document the source in the migration's leading comment. Full 25-micro × 7-age × 3-sex coverage can land in a follow-up.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/nutrition.yaml`

(File created by 01a, extended by 01b/01c — append four new path-items below 01c's journal blocks. Do NOT touch existing path-items.)

```yaml
nutritionIngredientLookup:
  get:
    tags: [Nutrition]
    operationId: lookupIngredient
    summary: Look up an ingredient by free-text term; cache-checks then falls through USDA / OFF.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: term
        required: true
        schema: { type: string, minLength: 1, maxLength: 255 }
    responses:
      '200':
        description: Ingredient nutrition resolved.
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/IngredientNutritionDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: No cache hit and no USDA / OFF match, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
nutritionIngredientSearch:
  post:
    tags: [Nutrition]
    operationId: searchIngredients
    summary: Search the cache (LIKE) for ingredient candidates; v1 is cache-only (does NOT trigger USDA / OFF).
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/IngredientLookupRequest' }
    responses:
      '200':
        description: Cache search results.
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/IngredientLookupResultDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
nutritionIngredientCorrection:
  put:
    tags: [Nutrition]
    operationId: correctIngredientMapping
    summary: Correct (or insert via correction-as-upsert is NOT supported; row must exist) the nutrition document on an existing ingredient mapping. Bumps source = MANUAL, confidence = 1.0.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: searchTerm
        required: true
        schema: { type: string, maxLength: 255 }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/nutrition.yaml#/CorrectIngredientMappingRequest' }
    responses:
      '200':
        description: Mapping corrected.
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/IngredientNutritionDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Search term not in cache, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
nutritionIngredientNeedsReview:
  get:
    tags: [Nutrition]
    operationId: getIngredientsNeedingReview
    summary: Paginated mappings with needs_review = true, sorted updatedAt DESC.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: Page of needs-review mappings.
        content:
          application/json:
            schema: { $ref: '../schemas/nutrition.yaml#/IngredientNutritionDtoPage' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/nutrition.yaml`

```yaml
IngredientMappingSource:
  type: string
  enum: [USDA, OPEN_FOOD_FACTS, MANUAL]
IngredientNutritionDocument:
  type: object
  description: JSONB blob describing nutrition per 100g. All scalars nullable (USDA / OFF returns are sparse).
  properties:
    calories: { type: integer, nullable: true }
    proteinG: { type: number, format: double, nullable: true }
    carbsG: { type: number, format: double, nullable: true }
    fatG: { type: number, format: double, nullable: true }
    fibreG: { type: number, format: double, nullable: true }
    saturatedFatG: { type: number, format: double, nullable: true }
    sugarG: { type: number, format: double, nullable: true }
    micros:
      type: object
      additionalProperties: { type: number, format: double }
    vitamins:
      type: object
      additionalProperties: { type: number, format: double }
IngredientNutritionDto:
  type: object
  required: [searchTerm, source, nutritionPer100g, confidence, needsReview, version]
  properties:
    searchTerm: { type: string, maxLength: 255 }
    source: { $ref: '#/IngredientMappingSource' }
    externalId:
      type: string
      maxLength: 64
      nullable: true
    nutritionPer100g: { $ref: '#/IngredientNutritionDocument' }
    defaultPieceGrams:
      type: integer
      nullable: true
    confidence: { type: number, format: double, minimum: 0, maximum: 1 }
    needsReview: { type: boolean }
    lastVerifiedAt:
      type: string
      format: date-time
      nullable: true
    version: { type: integer, format: int64 }
IngredientLookupRequest:
  type: object
  required: [query]
  properties:
    query: { type: string, minLength: 1, maxLength: 255 }
    maxResults: { type: integer, minimum: 1, maximum: 20, default: 10, nullable: true }
IngredientLookupResultDto:
  type: object
  required: [hits, cacheOnly]
  properties:
    hits:
      type: array
      items: { $ref: '#/IngredientNutritionDto' }
    cacheOnly:
      type: boolean
      description: True in v1 (cache-only search). False once nutrition-01m wires live USDA/OFF search.
CorrectIngredientMappingRequest:
  type: object
  required: [override, expectedVersion]
  properties:
    override: { $ref: '#/IngredientNutritionDocument' }
    expectedVersion: { type: integer, format: int64, minimum: 0 }
IngredientNutritionDtoPage:
  type: object
  additionalProperties: true
  required: [content, totalElements, totalPages, number, size]
  properties:
    content:
      type: array
      items: { $ref: '#/IngredientNutritionDto' }
    totalElements: { type: integer, format: int64 }
    totalPages: { type: integer }
    number: { type: integer }
    size: { type: integer }
    first: { type: boolean }
    last: { type: boolean }
    empty: { type: boolean }
    numberOfElements: { type: integer }
UnmappedIngredientDto:
  type: object
  required: [name, reason, confidence]
  properties:
    name: { type: string, maxLength: 255 }
    reason: { type: string, maxLength: 128 }
    confidence: { type: number, format: double, minimum: 0, maximum: 1 }
```

**Gotcha applied**: every nullable scalar uses **inline** `nullable: true` (NOT `$ref + nullable: true`). The `IngredientNutritionDocument` is `$ref`'d from `IngredientNutritionDto.nutritionPer100g` without nullable (the document is required on every row).

**Gotcha applied**: `IngredientNutritionDtoPage` uses the **flat** `Page<T>` shape with `additionalProperties: true` (Spring Boot serialises `Page<T>` flat with extra `pageable` + `sort` fields).

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# nutrition` block in `paths:` (after 01c's journal refs). Append four new path-item refs:

```yaml
  /api/v1/nutrition/ingredients/lookup:
    $ref: 'paths/nutrition.yaml#/nutritionIngredientLookup'
  /api/v1/nutrition/ingredients/search:
    $ref: 'paths/nutrition.yaml#/nutritionIngredientSearch'
  /api/v1/nutrition/ingredients/{searchTerm}/correction:
    $ref: 'paths/nutrition.yaml#/nutritionIngredientCorrection'
  /api/v1/nutrition/ingredients/needs-review:
    $ref: 'paths/nutrition.yaml#/nutritionIngredientNeedsReview'
```

**Location**: under `components.schemas:`, append eight new schema refs in the existing `# nutrition` block (alphabetical):

```yaml
    CorrectIngredientMappingRequest: { $ref: 'schemas/nutrition.yaml#/CorrectIngredientMappingRequest' }
    IngredientLookupRequest: { $ref: 'schemas/nutrition.yaml#/IngredientLookupRequest' }
    IngredientLookupResultDto: { $ref: 'schemas/nutrition.yaml#/IngredientLookupResultDto' }
    IngredientMappingSource: { $ref: 'schemas/nutrition.yaml#/IngredientMappingSource' }
    IngredientNutritionDocument: { $ref: 'schemas/nutrition.yaml#/IngredientNutritionDocument' }
    IngredientNutritionDto: { $ref: 'schemas/nutrition.yaml#/IngredientNutritionDto' }
    IngredientNutritionDtoPage: { $ref: 'schemas/nutrition.yaml#/IngredientNutritionDtoPage' }
    UnmappedIngredientDto: { $ref: 'schemas/nutrition.yaml#/UnmappedIngredientDto' }
```

## Verbatim shape snippets

### Entity

```java
@Entity
@Table(name = "nutrition_ingredient_mapping")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IngredientMapping {

  @Id @Column(name = "id", updatable = false, nullable = false)
  private UUID id;

  @Column(name = "search_term", nullable = false, updatable = false, unique = true, length = 255)
  private String searchTerm;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 24)
  private IngredientMappingSource source;

  @Column(name = "external_id", length = 64)
  private String externalId;

  @Type(JsonType.class)
  @Column(name = "nutrition_per_100g", nullable = false, columnDefinition = "jsonb")
  private IngredientNutritionDocument nutritionPer100g;

  @Column(name = "default_piece_grams")
  private Integer defaultPieceGrams;

  @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
  private BigDecimal confidence;

  @Column(name = "needs_review", nullable = false)
  private boolean needsReview;

  @Column(name = "last_verified_at")
  private Instant lastVerifiedAt;

  @Version @Column(name = "version", nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
```

### `IngredientMappingPipeline` skeleton

```java
@Component
public class IngredientMappingPipeline {
  private final IngredientMappingRepository repo;
  private final IntakeKeyNormaliser normaliser;
  private final UsdaApiClient usdaClient;
  private final OpenFoodFactsClient offClient;
  private final IngredientMappingMapper mapper;

  // constructor omitted

  @Transactional
  public IngredientMappingResult resolve(IngredientLookupInput input) {
    String searchTerm = normaliser.normalise(input.rawTerm());
    Optional<IngredientMapping> hit = repo.findBySearchTerm(searchTerm);
    if (hit.isPresent()) {
      return new IngredientMappingResult.Resolved(mapper.toDto(hit.get()));
    }
    Optional<UsdaSearchResultDto> usda = usdaClient.search(searchTerm);
    if (usda.isPresent() && !usda.get().foods().isEmpty()) {
      var first = usda.get().foods().get(0);
      return new IngredientMappingResult.Resolved(persistOrReread(searchTerm, IngredientMappingSource.USDA,
          first.fdcId(), first.toDocument(), Math.min(first.score(), 0.85)));
    }
    Optional<OffSearchResultDto> off = offClient.search(searchTerm);
    if (off.isPresent() && !off.get().products().isEmpty()) {
      var first = off.get().products().get(0);
      return new IngredientMappingResult.Resolved(persistOrReread(searchTerm, IngredientMappingSource.OPEN_FOOD_FACTS,
          first.code(), first.toDocument(), Math.min(first.score(), 0.85)));
    }
    return new IngredientMappingResult.Unmapped(new UnmappedIngredientDto(
        input.rawTerm(), "no source matches", BigDecimal.ZERO));
  }

  private IngredientNutritionDto persistOrReread(String searchTerm, IngredientMappingSource source,
                                                 String externalId, IngredientNutritionDocument doc,
                                                 double confidence) {
    IngredientMapping toSave = IngredientMapping.builder()
        .id(UUID.randomUUID())
        .searchTerm(searchTerm).source(source).externalId(externalId)
        .nutritionPer100g(doc)
        .confidence(BigDecimal.valueOf(confidence))
        .needsReview(confidence < 0.7)
        .build();
    try {
      return mapper.toDto(repo.saveAndFlush(toSave));
    } catch (DataIntegrityViolationException race) {
      return mapper.toDto(repo.findBySearchTerm(searchTerm).orElseThrow());
    }
  }
}
```

### `UsdaApiClient` skeleton

```java
@Component
public class UsdaApiClient {
  private final RestClient restClient;
  private final String apiKey;

  public UsdaApiClient(RestClient.Builder builder, UsdaApiConfig config) {
    this.restClient = builder.baseUrl(config.baseUrl()).build();
    this.apiKey = config.apiKey();
  }

  @Retry(name = "usda", fallbackMethod = "searchFallback")
  @RateLimiter(name = "usda")
  public Optional<UsdaSearchResultDto> search(String searchTerm) {
    try {
      UsdaSearchResultDto result = restClient.get()
          .uri(b -> b.path("/foods/search")
              .queryParam("query", searchTerm)
              .queryParam("pageSize", 5)
              .queryParam("api_key", apiKey).build())
          .retrieve()
          .body(UsdaSearchResultDto.class);
      return Optional.ofNullable(result);
    } catch (HttpClientErrorException e) {
      return Optional.empty();
    }
  }

  Optional<UsdaSearchResultDto> searchFallback(String searchTerm, Throwable t) {
    log.warn("USDA fallback for term='{}': {}", searchTerm, t.toString());
    return Optional.empty();
  }
}
```

## Edge-case checklist

- [ ] `GET /lookup?term=chicken%20breast` cold cache → pipeline → USDA hit → 200, row persisted with `source = USDA`, `confidence = min(score, 0.85)`, `needsReview = (confidence < 0.7)`
- [ ] `GET /lookup?term=chicken%20breast` warm cache → 200, no external HTTP call (verify via WireMock-counter assertion of zero hits)
- [ ] `GET /lookup?term=` empty / whitespace-only → 400 validation
- [ ] `GET /lookup?term=foo` USDA empty → OFF empty → 404 `ingredient-mapping-not-found`
- [ ] `GET /lookup?term=foo` USDA empty → OFF hit → 200, row persisted with `source = OPEN_FOOD_FACTS`
- [ ] `GET /lookup?term=foo` USDA returns 503 twice → retry succeeds on 3rd → 200 (Resilience4j `@Retry`)
- [ ] `GET /lookup?term=foo` USDA returns 503 5 times → fallback returns Optional.empty → OFF fallback path
- [ ] Concurrent `GET /lookup?term=Foo` and `GET /lookup?term=foo` (same normalised term) → both 200; only ONE row persisted (verified by `JdbcTemplate` count); pipeline re-reads on `DataIntegrityViolationException`
- [ ] `IntakeKeyNormaliser`: `"  Chicken   Breast  "` normalises to `"chicken breast"`; idempotent (re-normalising matches)
- [ ] `POST /search` returns up to `maxResults` cache hits where `lower(search_term) LIKE concat('%', lower(:q), '%')`; default `maxResults = 10`; clamp to 20
- [ ] `POST /search` does NOT trigger external HTTP (verified via WireMock counters)
- [ ] `PUT /{searchTerm}/correction` happy path → 200; `source = MANUAL`, `confidence = 1.0`, `needsReview = false`, `lastVerifiedAt` set, `@Version` bumped; `IngredientMappingCorrectedEvent` published
- [ ] `PUT /{searchTerm}/correction` for unknown term → 404
- [ ] `PUT /{searchTerm}/correction` with stale `expectedVersion` → 409
- [ ] `PUT /{searchTerm}/correction` validation: `override` missing → 400
- [ ] `PUT /{searchTerm}/correction` `searchTerm` normalised before lookup (URL-encoded variants match)
- [ ] `GET /needs-review?page=&size=` returns only rows where `needs_review = true`, sorted `updated_at DESC`, paginated; default size 20, max 100; `IngredientNutritionDtoPage` flat shape validates
- [ ] `IngredientMappingPipeline.resolve` joins caller's `@Transactional` (verified by calling from within a test `@Transactional` boundary and asserting same-tx visibility)
- [ ] `UsdaApiClient` resides in `com.example.mealprep.nutrition.config` (ArchUnit `springWebStaysInApi` rule passes)
- [ ] `OpenFoodFactsClient` likewise in `nutrition.config`
- [ ] `IngredientMappingPipeline` does NOT import Spring Web types (depends only on its two clients + repository + normaliser)
- [ ] `IngredientNutritionDocument` JSONB round-trips: write entity → re-read → fields identical (including null scalars and empty `micros` map)
- [ ] Cross-module helper `lookupIngredient` is a pure cache read (no pipeline invocation, no HTTP calls)
- [ ] Cross-module helper `lookupIngredients` batch round-trips a single SQL `WHERE search_term IN (...)` (verified via Hibernate stats)
- [ ] DRI seed (`R__nutrition_seed_dri_defaults.sql`) loads ≥ 42 rows on startup; re-running on a clean DB is idempotent (UPSERT pattern)
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] `NutritionBoundaryTest` (from 01a) still passes — new repo in `domain/repository/`
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the two new handler methods
- [ ] `application-test.yml` carries placeholder `mealprep.nutrition.usda.api-key=test-key-not-real`; IT mocks via WireMock

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601601100__nutrition_create_ingredient_mapping.sql
NEW   src/main/resources/db/migration/R__nutrition_seed_dri_defaults.sql

NEW   src/main/java/com/example/mealprep/nutrition/api/controller/IngredientLookupController.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IngredientMappingSource.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IngredientNutritionDocument.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IngredientNutritionDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IngredientLookupRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/IngredientLookupResultDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/CorrectIngredientMappingRequest.java
NEW   src/main/java/com/example/mealprep/nutrition/api/dto/UnmappedIngredientDto.java
NEW   src/main/java/com/example/mealprep/nutrition/api/mapper/IngredientMappingMapper.java
NEW   src/main/java/com/example/mealprep/nutrition/config/UsdaApiClient.java                            (Spring RestClient — in config/ per springWebStaysInApi rule)
NEW   src/main/java/com/example/mealprep/nutrition/config/OpenFoodFactsClient.java                     (Spring RestClient — in config/)
NEW   src/main/java/com/example/mealprep/nutrition/config/UsdaApiConfig.java                            (@ConfigurationProperties)
NEW   src/main/java/com/example/mealprep/nutrition/config/OpenFoodFactsConfig.java                     (@ConfigurationProperties)
NEW   src/main/java/com/example/mealprep/nutrition/config/UsdaSearchResultDto.java                      (USDA wire DTO — internal to config/)
NEW   src/main/java/com/example/mealprep/nutrition/config/OffSearchResultDto.java                       (OFF wire DTO — internal to config/)
NEW   src/main/java/com/example/mealprep/nutrition/domain/entity/IngredientMapping.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/repository/IngredientMappingRepository.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/IngredientMappingPipeline.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/IntakeKeyNormaliser.java
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/IngredientMappingResult.java (sealed interface + Resolved / Unmapped variants)
NEW   src/main/java/com/example/mealprep/nutrition/domain/service/internal/IngredientLookupInput.java  (internal record)
NEW   src/main/java/com/example/mealprep/nutrition/event/IngredientMappingCorrectedEvent.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/IngredientMappingNotFoundException.java
NEW   src/main/java/com/example/mealprep/nutrition/exception/IngredientMappingPipelineException.java

MOD   src/main/java/com/example/mealprep/nutrition/api/NutritionExceptionHandler.java                  (append 2 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionQueryService.java          (append lookupIngredient, lookupIngredients, searchIngredientsForUi, getMappingsNeedingReview)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/NutritionUpdateService.java         (append correctIngredientMapping)
MOD   src/main/java/com/example/mealprep/nutrition/domain/service/internal/NutritionServiceImpl.java  (implement the five new methods, wire IngredientMappingPipeline + repo)

MOD   src/main/resources/openapi/paths/nutrition.yaml      (append 4 new path-items below 01c's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/nutrition.yaml    (append 8 new schemas)
MOD   src/main/resources/openapi/openapi.yaml              (4 lines under paths: in the `# nutrition` block; 8 lines under components.schemas: in the `# nutrition` block)

MOD   src/main/resources/application.yml                   (add mealprep.nutrition.usda.base-url + open-food-facts.base-url defaults; resilience4j.retry.instances.usda / off; resilience4j.ratelimiter.instances.usda / off)
MOD   src/test/resources/application-test.yml              (placeholder USDA api-key; WireMock-friendly defaults)

NEW   src/test/java/com/example/mealprep/nutrition/IngredientMappingPipelineTest.java                  (cache hit short-circuits; USDA hit persists; USDA empty + OFF hit; both empty → Unmapped; race resolves via re-read; confidence < 0.7 sets needsReview)
NEW   src/test/java/com/example/mealprep/nutrition/IntakeKeyNormaliserTest.java                        (idempotence; whitespace; case)
NEW   src/test/java/com/example/mealprep/nutrition/IngredientLookupControllerIT.java                  (cache hit; miss + WireMock USDA hit; correction + version bump; needs-review listing; LIKE search)
NEW   src/test/java/com/example/mealprep/nutrition/UsdaApiClientIT.java                               (WireMock fixtures; retry on 5xx; rate-limit headers)
NEW   src/test/java/com/example/mealprep/nutrition/OpenFoodFactsClientIT.java                          (WireMock; retry on 5xx)
NEW   src/test/resources/nutrition/fixtures/usda-chicken-breast.json                                   (canned response for IT)
NEW   src/test/resources/nutrition/fixtures/usda-empty.json                                            (canned empty response)
NEW   src/test/resources/nutrition/fixtures/off-banana.json                                            (canned OFF response)
MOD   src/test/java/com/example/mealprep/nutrition/testdata/NutritionTestData.java                    (append ingredient-mapping builder fixture)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-4 tickets running in parallel must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exceptions go in the existing `NutritionExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule lives in the existing `NutritionBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`, migrations, entities — none touched.
- 01a's `NutritionTargets`, 01b's `IntakeDay`, 01c's `FoodMoodJournalEntry` — all peer aggregates; no cross-aggregate associations.
- `NutritionBoundaryTest` is unchanged.
- `pom.xml` is **modified once** (single root) only if Resilience4j + WireMock aren't already present. The agent must check; if `io.github.resilience4j:resilience4j-spring-boot3` and `com.github.tomakehurst:wiremock-standalone` are absent, add them in `pom.xml` and call out the change in the report. **Sibling tickets do not touch `pom.xml`**, so this is collision-free.

## Dependencies

- **Hard dependency**: `nutrition-01a` (merged) — `NutritionQueryService`, `NutritionUpdateService`, `NutritionExceptionHandler`, `NutritionBoundaryTest`, `NutritionException`, the `hypersistence-utils-hibernate-63` JSONB plumbing.
- **Hard dependency**: `nutrition-01b` (merged) — extends the same two service interfaces; the per-module YAML / advice append-only convention.
- **Hard dependency**: `nutrition-01c` (merged) — `JournalAction` enum location convention; the per-module event package.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver` (used on `correction` endpoint to record `actorUserId`).
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout; the `springWebStaysInApi` ArchUnit rule.
- **Sibling tickets running in parallel** (Wave 2 round 4): `household-01d`, `provisions-01d`, `recipe-01d`. None should touch any nutrition file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# nutrition` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate — including `springWebStaysInApi`)
- [ ] All edge-case items above ticked
- [ ] `NutritionExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after the two new methods are appended
- [ ] **`UsdaApiClient` and `OpenFoodFactsClient` live in `com.example.mealprep.nutrition.config`** (ArchUnit `springWebStaysInApi` rule passes — verified by attempting placement in `domain/service/internal/` and observing the rule fire, then moving back)
- [ ] `IngredientMappingPipeline` does NOT depend on Spring Web (imports only the two client interfaces, repository, normaliser, mapper)
- [ ] `IngredientNutritionDocument` JSONB round-trip verified (write → re-read → equality)
- [ ] Resilience4j retry + rate-limit annotations verified by IT using WireMock 5xx fixtures
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (NOT `$ref + nullable: true`) on every nullable scalar
- [ ] `IngredientNutritionDtoPage` uses the **flat** Page<T> shape with `additionalProperties: true`
- [ ] Concurrent-insert race resolves cleanly — `IngredientMappingPipelineTest` simulates duplicate via mocked repo throwing `DataIntegrityViolationException` once → re-read returns the winning row
- [ ] No N+1 — `lookupIngredients` issues a single SQL with `IN (...)` clause (verified via Hibernate stats)
- [ ] DRI seed migration is idempotent — running twice on the same DB yields the same row count
- [ ] pom.xml changes (Resilience4j, WireMock) are documented in the agent's report if added

## What's NOT in scope

- AI parse step (`IngredientParseTask`) and AI match step (`IngredientMatchTask`) — owned by the AI module (`lld/ai.md`); 01d ships stubs (see top divergence note)
- `NutritionCalculationService` (recipe save-time + `RecipeEvolvedEvent` listener) → **nutrition-01f**
- Snack-log integration (`logSnack` calling `IngredientMappingPipeline.resolve`) → **nutrition-01l**
- Free-text override parsing (`overrideIntakeFromFreeText` → AI parse → pipeline) → **nutrition-01k**
- Health directives queue → **nutrition-01e**
- `NutritionFloorGateService` → **nutrition-01g**
- Weekly aggregates + `DivergenceDetector` → **nutrition-01h**
- `MealCookedEvent` auto-confirm listener → **nutrition-01i**
- `applyFeedback(NutritionFeedbackRequest)` from feedback module → **nutrition-01m**
- Cache-only-OFF (live USDA / OFF search exposed to UI for "discover new ingredients") on `POST /search` — `cacheOnly = true` always in v1; live search ships in **nutrition-01m**
- Async/background ingredient resolution for novel terms (LLD line 982 — "novel ingredients could later move to a background queue")
- 25-micro × 7-age × 3-sex full DRI coverage — 01d ships a minimal-viable subset (≥ 42 rows); full coverage is a follow-up

Squash-merge with: `feat(nutrition): 01d — ingredient mapping cache + USDA/OFF clients + pipeline + lookup endpoints + DRI seed`
