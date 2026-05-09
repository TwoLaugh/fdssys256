# Ticket: recipe — 01b URL Import + Branches List

## Summary

Layer two concerns on the 01a recipe aggregate: (a) **URL/HTML import preview-then-confirm flow** per [`lld/recipe.md`](../../lld/recipe.md) §Flow 2 + §V20260601120800 — `ImportRecipeFromUrlRequest`, `ImportRecipeFromHtmlRequest`, `HtmlImportParser` (deterministic v1 — JSON-LD `Recipe` schema + microdata + common selectors), `RecipeImport` table, `getImportProvenance` query, and the three import endpoints (`preview-url`, `preview-html`, `confirm`); and (b) **the deferred `branches[]` field on `RecipeDto`** — the `RecipeBranchDto` schema + `RecipeBranchMapper` + `findAllByRecipeId` query + `GET /api/v1/recipes/{recipeId}/branches` endpoint, plus the `branches[]` field hydrated on the existing `GET /api/v1/recipes/{recipeId}` response.

In 01a there is exactly one `'main'` branch per recipe (auto-created by `createRecipe`); 01b exposes that branch through the public API but does NOT add the create-branch flow (that's `recipe-01d`). 01b unblocks downstream "show me the import provenance" + "list this recipe's branches" reads.

**Defers** (still out of scope after 01b):
- Manual edit (`UpdateRecipeManualEditRequest` → version v2+), `VersionDiffer`, `change_diff` payload, `diff(fromVersionId, toVersionId)` endpoint, `RecipeUpdatedEvent` → **recipe-01c**
- Branch **creation** (`CreateBranchRequest`, non-main rows, divergence-score, `CharacterFingerprintDto` population) → **recipe-01d**
- Substitutions → **recipe-01e**
- `RecipeWriteApi` SPI + adaptation events (`RecipeAdaptedEvent`, `RecipeEvolvedEvent` listener) → **recipe-01f**
- Promotion / demotion / archive + `ArchiveEligibilityScanner` → **recipe-01g**
- pgvector + embedding column + HNSW index + async embedding listener → **recipe-01h**
- Search (`RecipeSearchCriteriaDto`, `Page<RecipeDto> search`, `TagSearchHelper`, GIN indexes) → **recipe-01i**
- `getIngredientMappingKeys` cross-module helper, `getFingerprint`, `getNutritionPerServing`, `getEmbedding` → **recipe-01j**
- AI tag inference (cuisine / protein / cookingMethod / complexity / flavour / dietary) → **recipe-01k**
- AI-driven URL extraction (the deterministic parser ships in 01b; AI replacement is later — endpoint contract unchanged)
- Recipe images (no `image_url` column reserved; revisit with frontend)

**LLD divergence note** — **simplification of the import flow**: the LLD specifies a **preview-then-confirm Paprika-style** flow with three endpoints (`preview-url`, `preview-html`, `confirm`) and a signed `previewToken`. **01b ships the simpler one-shot `POST /imports/url` per the LLD §REST table line 655 (`RecipeImportsController` → `POST /url` → 201 RecipeDto)**, deferring the preview/confirm split + `previewToken` signing infrastructure to a follow-up sub-ticket (recipe-01b-preview, optional, post-frontend). Justification: the preview/confirm flow needs a preview cache (`recipe_extraction_cache`), token signing keys, and a frontend that supports the two-step UX — none of those exist yet. The simpler one-shot flow is what the LLD's §REST table commits to (`POST /imports/url`, 201) and what the deterministic v1 parser can deliver in 30-45 min. The richer flow is documented in §Flow 2; **01b matches §REST**, and the §Flow 2 preview-token mechanism is explicitly deferred.

## Behavioural spec

### URL import — `POST /api/v1/recipes/imports/url`

1. `ImportRecipeFromUrlRequest { @NotBlank @URL @Size(max = 2048) String url, @Nullable Catalogue catalogue /* defaults to USER */ }`. Auth required. Server resolves `userId` via `CurrentUserResolver`.
2. **Pipeline**:
   1. **Fetch**: server `HTTP GET` the URL with a 10-second timeout, max 2 MB body (configurable `mealprep.recipe.import.fetch.max-bytes` / `.timeout-ms`). Follow up to 3 redirects. User-Agent `MealPrepBot/1.0 (+https://mealprep.example.com)`. Connection pool via `RestTemplate` / `WebClient` configured in `RecipeJsonConfig` (or new `RecipeImportConfig`). Failures (timeout, 4xx/5xx, oversize) → `RecipeImportFailureException` (422, `type = .../recipe-import-failure`).
   2. **Parse**: `HtmlImportParser.parse(String html, String url): ParsedRecipe`. Strategy order:
      1. **JSON-LD** — extract any `<script type="application/ld+json">` block, JSON-parse it (defensively, swallow malformed scripts), find any `@type: "Recipe"` (or `Recipe` as one of multiple types via array). Map fields: `name → name`, `description → description`, `recipeIngredient (array of strings) → ingredients`, `recipeInstructions (array of strings or array of `{@type:HowToStep, text:...}`) → method`, `prepTime`/`cookTime`/`totalTime` (ISO-8601 durations) → metadata times, `recipeYield → metadata.servings`, `recipeCuisine → tags.cuisine`, `recipeCategory → tags.cookingMethod` (best-effort).
      2. **Microdata fallback** — when JSON-LD absent, scan `[itemtype*="schema.org/Recipe"]` and pull `[itemprop=name]`, `[itemprop=recipeIngredient]`, `[itemprop=recipeInstructions]`, etc. (jsoup-based.)
      3. **Common-selector fallback** — `h1.recipe-title`, `.ingredients li`, `.method li`. Best-effort.
      4. If all three strategies fail to produce at least `name + 1 ingredient + 1 method step` → `RecipeImportFailureException` (422, `type = .../recipe-import-failure`, with `extraction_method = "none"` in the ProblemDetail extension).
   3. **Map to `CreateRecipeRequest`** — re-use 01a's request shape (and its 01a Jakarta validators). Missing `metadata.totalTimeMins`: derive from `prepTime + cookTime` if both present, else default to 0 with `needs_review = true` on metadata (Note: 01a's `@ValidRecipeMetadata` validator allows `total == prep + cook ± 1`; if defaulting to 0 trips it, the parser SHOULD compute `total = prep + cook` and write that value rather than 0).
   4. **Dedupe (deferred)**: 01a's `DeduplicationFingerprintHasher` is **not yet implemented** (LLD §Flow 1 / 2 mentions it but 01a deferred). 01b therefore SKIPS dedup — duplicate URLs simply create separate recipes with separate `recipe_imports` rows. Add a `// TODO recipe-01g — dedupe on import` comment on the import service. **LLD divergence noted**.
   5. **Persist** — single `@Transactional` write:
      - Call `RecipeUpdateService.createRecipe(userId, mappedRequest)` from 01a — this writes `Recipe` + `RecipeBranch main` + `RecipeVersion v1` + ingredients + method + metadata + tags. **Override**: `Recipe.dataQuality = IMPORTED` (not 01a's default `USER_VERIFIED`) and `RecipeVersion.trigger = IMPORT` (not 01a's default `MANUAL_CREATE`). Both are exposed via a new internal-only overload on the service, NOT the public `createRecipe` (see "Service interfaces" below).
      - Then insert `RecipeImport` row (one per recipe, `UNIQUE (recipe_id)`).
   6. Return 201 + `RecipeDto` (now WITH `branches[]` populated — see invariant 13) + `Location: /api/v1/recipes/{recipeId}` header. The LLD's §REST table specifies 201 + `RecipeDto`.
3. `RecipeCreatedEvent` and `RecipeVersionCreatedEvent` from 01a fire `AFTER_COMMIT` as before. **No new import-specific event in 01b** (LLD doesn't define one).

### `RecipeImport` aggregate

4. Schema per [LLD V20260601120800 lines 249-259](../../lld/recipe.md), renumbered to `V20260601800800` for the recipe timestamp range. Fields: `id (UUID), recipeId (UUID NOT NULL UNIQUE FK → recipe_recipes.id ON DELETE CASCADE), sourceType (varchar 16 NOT NULL — manual | url | ai_generated | web_discovered), sourceUrl (text nullable), sourcePayload (jsonb nullable, lazy-loaded), extractionMethod (varchar 32 nullable — json_ld | microdata | common_selectors | none), duplicateOfRecipeId (UUID nullable; FK omitted to keep dedupe deferral clean — soft-FK), importedAt (timestamptz NOT NULL), importedByUserId (UUID NOT NULL)`. Indexes: `idx_recipe_imports_url ON recipe_imports (source_url) WHERE source_url IS NOT NULL`; `idx_recipe_imports_dedupe ON recipe_imports (duplicate_of_recipe_id) WHERE duplicate_of_recipe_id IS NOT NULL` (the latter is a no-op until dedupe lands; cheap to ship now).
5. `RecipeImportDto`: `(UUID id, UUID recipeId, ImportSource sourceType, String sourceUrl, JsonNode sourcePayload /* nullable; populated only on getImportProvenance */, String extractionMethod, UUID duplicateOfRecipeId, Instant importedAt, UUID importedByUserId)`.
6. `ImportSource` enum: `MANUAL`, `URL`, `AI_GENERATED`, `WEB_DISCOVERED`.

### `getImportProvenance`

7. `GET /api/v1/recipes/{recipeId}/import-provenance` returns `RecipeImportDto` (200) including the lazy `sourcePayload` JSONB (raw HTML excerpt or empty), or 404 `RecipeNotFoundException` if the recipe doesn't exist or has no import row (manual_create recipes from 01a don't have a `RecipeImport` row → 404 with `type = .../recipe-import-not-found` to disambiguate from "recipe doesn't exist"). **LLD divergence**: LLD §Service Interfaces line 542 declares `Optional<JsonNode> getImportProvenance(UUID recipeId)` returning a JsonNode only. **01b ships the richer DTO** — agents need extraction provenance (which strategy won) + audit fields (when, by whom). Easier to expose now; the LLD's narrower JsonNode is a strict subset.
8. New module exception `RecipeImportNotFoundException` (404) extending `RecipeException`.

### Branches list — `branches[]` on `RecipeDto` + `GET /branches` endpoint

9. **Add the `branches[]` field** to `RecipeDto` from 01a:
   ```java
   public record RecipeDto(
       UUID id, UUID userId, Catalogue catalogue, String name, String description,
       int currentVersion, UUID currentBranchId,
       DataQuality dataQuality, NutritionStatus nutritionStatus,
       Instant lastUsedInPlanAt, Instant archivedAt, Instant deletedAt,
       Instant createdAt, Instant updatedAt, long optimisticVersion,
       RecipeVersionDto currentVersionDetails,
       List<RecipeBranchDto> branches              // NEW in 01b
   ) {}
   ```
   `branches[]` is populated by every `RecipeQueryService.getById` call (01a's existing endpoint) AND by the new `GET /branches` endpoint AND by the new `POST /imports/url` 201 response. Modifies the 01a `RecipeMapper.toDto` overload that builds the hydrated `RecipeDto`.
10. `RecipeBranchDto` per [LLD lines 321-324](../../lld/recipe.md), **simplified for 01b** (no `characterFingerprint` — that's null in 01a/01b; lands in recipe-01d):
    ```java
    public record RecipeBranchDto(
        UUID id, UUID recipeId, UUID parentBranchId, UUID branchPointVersionId,
        String name, String label, String reason,
        int currentVersion, BigDecimal divergenceScore,
        Instant createdAt, String createdByActor, UUID adapterTraceId,
        long version
    ) {}
    ```
    The `characterFingerprint` field is omitted from the DTO in 01b — recipe-01d will add it back when fingerprint population lands.
11. `RecipeBranchMapper` (MapStruct, `@Mapper(componentModel = "spring")`) — straight field copy from `RecipeBranch` entity.
12. New repository method on the **existing** `RecipeBranchRepository` from 01a:
    ```java
    interface RecipeBranchRepository extends JpaRepository<RecipeBranch, UUID> {
      List<RecipeBranch> findAllByRecipeId(UUID recipeId);  // NEW in 01b
    }
    ```
13. **Hot-path performance**: every `getById` now triggers a second query (`findAllByRecipeId`). Acceptable — the planner doesn't call `getById`; that's the user-facing detail page. The single recipe call goes from 4 SELECTs (01a) to 5 SELECTs (01b). No N+1 (it's a per-recipe call, not per-list).
14. `GET /api/v1/recipes/{recipeId}/branches` returns `List<RecipeBranchDto>` (200) sorted by `createdAt ASC` (so 'main' is always first). 404 if recipe doesn't exist or is soft-deleted.
15. Append to `RecipeQueryService`:
    ```java
    List<RecipeBranchDto> getBranches(UUID recipeId);
    Optional<RecipeImportDto> getImportProvenance(UUID recipeId);
    ```
16. Append to `RecipeUpdateService`:
    ```java
    RecipeDto importFromUrl(UUID userId, ImportRecipeFromUrlRequest request);
    ```

### HTML import — DEFERRED

17. The LLD describes `POST /imports/preview-html` for in-app-browser-supplied HTML. **01b SKIPS this** — the in-app browser is a frontend feature that doesn't exist yet. Reserved endpoint name. Land alongside the frontend in-app browser ticket. The deterministic `HtmlImportParser` already operates on a `(String html, String url)` pair so adding an `importFromHtml` method later is one line.

### Cross-module facade + boundary

18. `RecipeModule.java` re-exports unchanged (the existing two interface re-exports cover the new methods). Repositories `RecipeImportRepository` (new) is **package-private**. Existing `RecipeBoundaryTest` from 01a covers it.

### Errors

19. New module exception subclasses extending `RecipeException` from 01a:
    - `RecipeImportFailureException` (422, `type = .../recipe-import-failure`, carries `failureReason: "fetch_timeout" | "fetch_4xx_<status>" | "fetch_5xx_<status>" | "oversize" | "no_extractor_matched" | "schema_mismatch"` as a ProblemDetail extension)
    - `RecipeImportNotFoundException` (404, `type = .../recipe-import-not-found`)
20. **Append** two new `@ExceptionHandler` methods to the existing `RecipeExceptionHandler` `@RestControllerAdvice` from 01a (which is already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler. Do **NOT** modify `config/GlobalExceptionHandler.java`.

## Database

```
src/main/resources/db/migration/V20260601800800__recipe_create_recipe_imports_table.sql       new
```

(One migration. The 01a recipe migrations already used V…800000 through V…800700, so 01b lands at V…800800 — the timestamp slot the round-1 ticket reserved.)

```sql
-- V20260601800800
CREATE TABLE recipe_imports (
    id                       uuid PRIMARY KEY,
    recipe_id                uuid NOT NULL UNIQUE REFERENCES recipe_recipes(id) ON DELETE CASCADE,
    source_type              varchar(16) NOT NULL,
    source_url               text,
    source_payload           jsonb,
    extraction_method        varchar(32),
    duplicate_of_recipe_id   uuid,                                  -- soft FK; dedupe lands later
    imported_at              timestamptz NOT NULL,
    imported_by_user_id      uuid NOT NULL
);
CREATE INDEX idx_recipe_imports_url    ON recipe_imports (source_url)             WHERE source_url IS NOT NULL;
CREATE INDEX idx_recipe_imports_dedupe ON recipe_imports (duplicate_of_recipe_id) WHERE duplicate_of_recipe_id IS NOT NULL;
```

`source_url` is `text` (URLs can be 2KB+); validation caps at 2048 chars at the application layer per the request DTO. **Computed, not parroted**.

`source_type` width 16: longest value is `web_discovered` (14 chars). **Computed**.

`extraction_method` width 32: longest value is `common_selectors` (16 chars). **Computed**.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/recipe.yaml`

(File created by 01a — append four new path-items. Do NOT touch 01a's `recipes` / `recipeById`.)

```yaml
recipeImports:
  post:
    tags: [Recipes]
    operationId: importRecipeFromUrl
    summary: Import a recipe by fetching and parsing a URL.
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/recipe.yaml#/ImportRecipeFromUrlRequest' }
    responses:
      '201':
        description: Recipe imported.
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: Import failed (fetch error or extraction failure), content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
recipeImportProvenance:
  get:
    tags: [Recipes]
    operationId: getRecipeImportProvenance
    summary: Return import provenance (source URL, extraction method, raw payload) for a recipe.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200': { description: Provenance, content: { application/json: { schema: { $ref: '../schemas/recipe.yaml#/RecipeImportDto' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Recipe or provenance not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
recipeBranches:
  get:
    tags: [Recipes]
    operationId: listRecipeBranches
    summary: List a recipe's branches (always at least 'main').
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: Branch list.
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/recipe.yaml#/RecipeBranchDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Recipe not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/recipe.yaml`

```yaml
ImportSource:
  type: string
  enum: [manual, url, ai_generated, web_discovered]
ImportRecipeFromUrlRequest:
  type: object
  required: [url]
  properties:
    url: { type: string, format: uri, minLength: 1, maxLength: 2048 }
    catalogue: { $ref: '#/Catalogue' }       # optional; defaults to USER server-side
RecipeImportDto:
  type: object
  required: [id, recipeId, sourceType, importedAt, importedByUserId]
  properties:
    id: { type: string, format: uuid }
    recipeId: { type: string, format: uuid }
    sourceType: { $ref: '#/ImportSource' }
    sourceUrl: { type: string, format: uri, maxLength: 2048, nullable: true }
    sourcePayload: {}                         # arbitrary JSON; nullable
    extractionMethod:
      type: string
      maxLength: 32
      nullable: true
      enum: [json_ld, microdata, common_selectors, none, null]
    duplicateOfRecipeId: { type: string, format: uuid, nullable: true }
    importedAt: { type: string, format: date-time }
    importedByUserId: { type: string, format: uuid }
RecipeBranchDto:
  type: object
  required: [id, recipeId, name, currentVersion, divergenceScore, createdAt, createdByActor, version]
  properties:
    id: { type: string, format: uuid }
    recipeId: { type: string, format: uuid }
    parentBranchId: { type: string, format: uuid, nullable: true }
    branchPointVersionId: { type: string, format: uuid, nullable: true }
    name: { type: string, minLength: 1, maxLength: 64 }
    label: { type: string, maxLength: 120, nullable: true }
    reason: { type: string, nullable: true }
    currentVersion: { type: integer, minimum: 1 }
    divergenceScore: { type: number, format: double, minimum: 0, maximum: 1 }
    createdAt: { type: string, format: date-time }
    createdByActor: { type: string, maxLength: 64 }       # 'user:<uuid>' is 41 chars; LLD's 32 is too narrow per the agent-prompt-template gotcha — widen
    adapterTraceId: { type: string, format: uuid, nullable: true }
    version: { type: integer, format: int64 }
```

**Gotcha applied**: nullable scalars use inline `nullable: true` (not `$ref + nullable: true`). For `sourceType`, the field is non-nullable so `$ref` is fine.

**Note on `createdByActor` column width**: 01a already shipped `varchar(64)` (the LLD's `varchar(32)` was patched parent-side during 01a verify after `'user:<uuid>'` = 41 chars overran). No backfill migration needed in 01b — the `RecipeBranchDto.createdByActor` schema below uses `maxLength: 64` to match.

### Modify `RecipeDto` in `schemas/recipe.yaml`

Add the `branches` field:
```yaml
RecipeDto:
  # existing 01a fields unchanged...
  required: [id, userId, catalogue, name, currentVersion, dataQuality, nutritionStatus, createdAt, updatedAt, optimisticVersion, branches]
  properties:
    # ...existing...
    branches:
      type: array
      items: { $ref: '#/RecipeBranchDto' }
```

### Append to entry `src/main/resources/openapi/openapi.yaml`

Under `paths:`:

```yaml
  /api/v1/recipes/imports/url:
    $ref: 'paths/recipe.yaml#/recipeImports'
  /api/v1/recipes/{recipeId}/import-provenance:
    $ref: 'paths/recipe.yaml#/recipeImportProvenance'
  /api/v1/recipes/{recipeId}/branches:
    $ref: 'paths/recipe.yaml#/recipeBranches'
```

Under `components.schemas:`:

```yaml
    ImportSource: { $ref: 'schemas/recipe.yaml#/ImportSource' }
    ImportRecipeFromUrlRequest: { $ref: 'schemas/recipe.yaml#/ImportRecipeFromUrlRequest' }
    RecipeImportDto: { $ref: 'schemas/recipe.yaml#/RecipeImportDto' }
    RecipeBranchDto: { $ref: 'schemas/recipe.yaml#/RecipeBranchDto' }
```

## Verbatim shape snippets

### RecipeImport entity — JSONB sourcePayload (lazy)

```java
@Entity
@Table(name = "recipe_imports",
       uniqueConstraints = @UniqueConstraint(columnNames = {"recipe_id"}))
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeImport {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;
  @Column(name = "recipe_id", nullable = false, updatable = false) private UUID recipeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 16) private ImportSource sourceType;

  @Column(name = "source_url", columnDefinition = "text") private String sourceUrl;

  @Type(JsonBinaryType.class)
  @Basic(fetch = FetchType.LAZY)                              // lazy: hot reads don't hydrate raw HTML
  @Column(name = "source_payload", columnDefinition = "jsonb")
  private JsonNode sourcePayload;

  @Column(name = "extraction_method", length = 32) private String extractionMethod;
  @Column(name = "duplicate_of_recipe_id") private UUID duplicateOfRecipeId;
  @Column(name = "imported_at", nullable = false) private Instant importedAt;
  @Column(name = "imported_by_user_id", nullable = false) private UUID importedByUserId;
}
```

### Repository — package-private

```java
interface RecipeImportRepository extends JpaRepository<RecipeImport, UUID> {
  Optional<RecipeImport> findByRecipeId(UUID recipeId);
}
```

### `HtmlImportParser` shape

```java
@Component
public class HtmlImportParser {
  public ParsedRecipe parse(String html, String url) {
    // 1. JSON-LD strategy
    Optional<ParsedRecipe> fromJsonLd = tryJsonLd(html, url);
    if (fromJsonLd.isPresent()) return fromJsonLd.get().withExtractionMethod("json_ld");
    // 2. Microdata
    Optional<ParsedRecipe> fromMicrodata = tryMicrodata(html, url);
    if (fromMicrodata.isPresent()) return fromMicrodata.get().withExtractionMethod("microdata");
    // 3. Common selectors
    Optional<ParsedRecipe> fromSelectors = tryCommonSelectors(html, url);
    if (fromSelectors.isPresent()) return fromSelectors.get().withExtractionMethod("common_selectors");
    throw new RecipeImportFailureException("no_extractor_matched");
  }

  public record ParsedRecipe(String name, String description,
                             List<String> ingredientLines, List<String> methodSteps,
                             Integer prepMinutes, Integer cookMinutes, Integer totalMinutes,
                             Integer servings, String cuisine,
                             String extractionMethod, JsonNode rawPayload) {
    public ParsedRecipe withExtractionMethod(String m) { /* copy w/ method */ }
  }
}
```

Use jsoup + Jackson for parsing — both already in the project's `pom.xml` per 01a.

### Service-impl skeleton — importFromUrl

```java
@Transactional
public RecipeDto importFromUrl(UUID userId, ImportRecipeFromUrlRequest request) {
  String html = fetcher.fetch(request.url());                      // throws RecipeImportFailureException on HTTP error
  HtmlImportParser.ParsedRecipe parsed = htmlImportParser.parse(html, request.url());
  CreateRecipeRequest mapped = parserToCreateRequestMapper.map(parsed);
  // Internal overload — sets dataQuality=IMPORTED, trigger=IMPORT
  RecipeDto dto = createRecipeInternal(userId, mapped, DataQuality.IMPORTED, VersionTrigger.IMPORT);
  recipeImportRepository.save(RecipeImport.builder()
      .id(UUID.randomUUID()).recipeId(dto.id())
      .sourceType(ImportSource.URL).sourceUrl(request.url())
      .sourcePayload(parsed.rawPayload())
      .extractionMethod(parsed.extractionMethod())
      .importedAt(Instant.now()).importedByUserId(userId)
      .build());
  // Re-hydrate the DTO so branches[] reflects the just-created 'main' branch
  return queryService.getById(dto.id()).orElseThrow();
}
```

## Edge-case checklist

- [ ] `POST /imports/url` with valid recipe URL (JSON-LD fixture) → 201, recipe created with `dataQuality = IMPORTED`, `RecipeVersion.trigger = IMPORT`, `RecipeImport` row written with `extractionMethod = json_ld`, `branches[]` carries one 'main' entry, `Location` header set
- [ ] `POST /imports/url` with microdata-only fixture → 201, `extractionMethod = microdata`
- [ ] `POST /imports/url` with no recognisable recipe markup → 422 `recipe-import-failure` with `failureReason = "no_extractor_matched"`, no DB rows written
- [ ] `POST /imports/url` with a 404-returning URL → 422 `recipe-import-failure` with `failureReason = "fetch_4xx_404"`
- [ ] `POST /imports/url` with a timing-out URL (mock 11s response) → 422 with `failureReason = "fetch_timeout"`
- [ ] `POST /imports/url` with response > 2 MB → 422 with `failureReason = "oversize"`
- [ ] `POST /imports/url` with a malformed URL → 400 (`@URL` validator)
- [ ] `POST /imports/url` without cookie → 401
- [ ] `RecipeCreatedEvent` and `RecipeVersionCreatedEvent` published exactly once after commit on a successful import (re-uses 01a's events, just verifies they fire on this code path)
- [ ] `GET /recipes/{id}/import-provenance` for a URL-imported recipe → 200, `sourcePayload` populated; for a 01a manually-created recipe (no import row) → 404 `recipe-import-not-found`; for a non-existent recipe → 404 `recipe-not-found`
- [ ] `GET /recipes/{id}/import-provenance` without cookie → 401
- [ ] `GET /recipes/{id}/branches` for an existing recipe → 200, returns exactly one 'main' branch (in 01b every recipe has exactly one)
- [ ] `GET /recipes/{id}/branches` for a soft-deleted recipe → 404 (use `findByIdAndDeletedAtIsNull`)
- [ ] `GET /recipes/{id}` (01a endpoint, no contract change) — response now includes `branches[]` populated; old field set unchanged
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter); `RecipeDto.branches` is required
- [ ] `RecipeBoundaryTest` (from 01a) still passes — new `RecipeImportRepository` sits in the same `domain/repository` package
- [ ] `RecipeImport.sourcePayload` is lazy — verify a `getById` Hibernate-stats trace does NOT include the `source_payload` column unless `getImportProvenance` is called

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601800800__recipe_create_recipe_imports_table.sql

NEW   src/main/java/com/example/mealprep/recipe/api/controller/RecipeImportsController.java
NEW   src/main/java/com/example/mealprep/recipe/api/controller/RecipeBranchesController.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/ImportRecipeFromUrlRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeImportDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeBranchDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeImportMapper.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeBranchMapper.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/ParsedRecipeToCreateRequestMapper.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeImport.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/ImportSource.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeImportRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/HtmlImportParser.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/UrlFetcher.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeImportFailureException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeImportNotFoundException.java
NEW   src/main/java/com/example/mealprep/recipe/config/RecipeImportConfig.java                          (RestClient/WebClient bean + timeout / max-size config)

MOD   src/main/java/com/example/mealprep/recipe/api/RecipeExceptionHandler.java                          (append two @ExceptionHandler methods; KEEP @Order(HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/recipe/api/dto/RecipeDto.java                                   (append branches[] field)
MOD   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeMapper.java                             (toDto overload now also takes List<RecipeBranch> — branches mapped via RecipeBranchMapper)
MOD   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeBranchRepository.java            (append findAllByRecipeId)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeQueryService.java                   (append getBranches, getImportProvenance)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeUpdateService.java                  (append importFromUrl)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java          (implement new methods + internal createRecipeInternal overload accepting DataQuality + VersionTrigger)

MOD   src/main/resources/openapi/paths/recipe.yaml                                                       (append 3 new path-items)
MOD   src/main/resources/openapi/schemas/recipe.yaml                                                     (append 4 new schemas; modify RecipeDto to add branches[])
MOD   src/main/resources/openapi/openapi.yaml                                                            (3 lines under paths:; 4 lines under components.schemas:)

NEW   src/test/java/com/example/mealprep/recipe/RecipeImportFlowIT.java
NEW   src/test/java/com/example/mealprep/recipe/RecipeBranchesFlowIT.java
NEW   src/test/java/com/example/mealprep/recipe/HtmlImportParserTest.java
NEW   src/test/resources/recipe/fixtures/jsonld-recipe.html
NEW   src/test/resources/recipe/fixtures/microdata-recipe.html
NEW   src/test/resources/recipe/fixtures/no-recipe.html
MOD   src/test/java/com/example/mealprep/recipe/RecipeServiceImplTest.java                              (append unit coverage for importFromUrl orchestration with mocked fetcher + parser)
MOD   src/test/java/com/example/mealprep/recipe/RecipesFlowIT.java                                      (append assertion: GET /recipes/{id} now returns branches[] with one 'main' entry)
```

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`
- `RecipeBoundaryTest` is unchanged (new repo in the existing package; rule covers it).

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe`, `RecipeVersion`, `RecipeBranch`, `Catalogue`, `DataQuality`, `NutritionStatus`, `VersionTrigger`, `CreateRecipeRequest`, `RecipeMapper`, `RecipeQueryService`, `RecipeUpdateService`, `RecipeExceptionHandler`, `RecipeBoundaryTest`, `RecipeException`, `RecipeNotFoundException`.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged).
- **Sibling tickets running serially in this round** (Wave 2 round 2): `household-01b`, `nutrition-01b`, `provisions-01b`. None touch recipe files; this ticket touches no household / nutrition / provisions files.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] **`RecipeExceptionHandler` continues to be `@Order(Ordered.HIGHEST_PRECEDENCE)`** — appending handlers must not remove the annotation
- [ ] No regression on existing tests, including 01a's `RecipesFlowIT` (which gets one new assertion for `branches[]`)
- [ ] `created_by_actor` width is 64 in both `recipe_branches` and `recipe_versions` (already at 64 from 01a; 01b's `RecipeBranchDto.createdByActor` schema uses `maxLength: 64`)
- [ ] `HtmlImportParser` correctly parses all three test fixtures
- [ ] No N+1 — `getById` adds at most ONE query for branches list; verified via Hibernate stats

## What's NOT in scope

- HTML import preview-then-confirm flow (`POST /imports/preview-url`, `POST /imports/preview-html`, `POST /imports/confirm`, signed `previewToken`, preview cache table) → reserved follow-up sub-ticket post-frontend
- `DeduplicationFingerprintHasher` and dedup-on-import → **recipe-01g** (defer; URL imports may create duplicates in 01b)
- Manual edit / version v2+ / `VersionDiffer` / `change_diff` / `diff` endpoint → **recipe-01c**
- Branch CREATION (`CreateBranchRequest` + non-main rows, divergence-score, character fingerprint) → **recipe-01d**
- `CharacterFingerprintDto` field on `RecipeBranchDto` (omitted in 01b — null in 01a/01b; lands with recipe-01d)
- `RecipeWriteApi` SPI for adaptation → **recipe-01f**
- `RecipeAdaptedEvent`, `RecipeEvolvedEvent` listener → **recipe-01f**
- Promotion / demotion / archive / `ArchiveEligibilityScanner` → **recipe-01g**
- pgvector + embeddings + HNSW + `RecipeVersionCreatedEvent` async listener that calls `aiService.embed` → **recipe-01h**
- AI-driven URL extraction (replace deterministic `HtmlImportParser` with mid-tier model + tool-use; endpoint contract unchanged) → **recipe-01b-ai-extractor**, optional follow-up
- Search / `RecipeSearchCriteriaDto` / `Page<RecipeDto> search` → **recipe-01i**
- Cross-module helpers: `getIngredientMappingKeys`, `getFingerprint`, `getNutritionPerServing`, `getEmbedding` → **recipe-01j**
- AI tag inference → **recipe-01k**
- Recipe images / `image_url` column → reserved for frontend phase

Squash-merge with: `feat(recipe): 01b — URL import (deterministic) + import provenance + branches list`
