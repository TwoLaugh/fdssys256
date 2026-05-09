# Ticket: recipe — 01a Recipe Aggregate (create + get-by-id)

## Summary

Implement the `recipe` module's foundation aggregate: `Recipe` (root) + initial main `RecipeBranch` + first `RecipeVersion` v1 + per-version body (`RecipeIngredient` × N, `RecipeMethodStep` × N, `RecipeMetadata`, `RecipeTags`). Repository, `RecipeQueryService.getById(recipeId)` (read-by-others contract: planner, hard-constraint filter, nutrition calculation, household merge — all need to fetch a recipe by id), `RecipeUpdateService.createRecipe(userId, request)` for `manual_create` trigger only, the V…800000 / V…800100 / V…800101 / V…800200 / V…800300 / V…800400 / V…800500 / V…800600 migrations, and the endpoint pair: `POST /api/v1/recipes` + `GET /api/v1/recipes/{recipeId}`. Per [`lld/recipe.md`](../../lld/recipe.md) §V20260601120000 / 120100 / 120200 / 120201 / 120300 / 120400 / 120500 / 120600 / 120700, §Service Interfaces.

**Defers** (this LLD has 843 lines covering catalogue + branching + substitutions + imports + embeddings; only the linear write path lands here):
- HTML extraction / `ImportRecipeFromUrlRequest` / `HtmlImportParser` / URL-based import flow → **separate `recipe-extraction-pipeline.md` LLD**, lands as **recipe-01b**
- Manual edit (`UpdateRecipeManualEditRequest`) writing version v2+ on the same branch → **recipe-01c**
- Branch creation (`CreateBranchRequest`, `RecipeBranch` lookup endpoints) → **recipe-01d**
- Substitutions (`RecipeSubstitution` table V…120300, `CreateSubstitutionRequest`, all substitution endpoints) → **recipe-01e**
- `RecipeWriteApi` SPI for the adaptation pipeline → **recipe-01f** (depends on `ai` module + adaptation pipeline LLD)
- Adaptation events (`RecipeAdaptedEvent`, `RecipeEvolvedEvent` listener for nutrition recalc) → **recipe-01f**
- Promotion (system → user catalogue), demotion, archive (`archived_at`, `last_used_in_plan_at`, `ArchiveEligibilityScanner` daily scan) → **recipe-01g**
- `RecipePromotedEvent`, `RecipeArchivedEvent` → **recipe-01g**
- pgvector extension + `embedding` column population (async listener on `RecipeVersionCreatedEvent` → `aiService.embed`) → **recipe-01h**. **Schema-side** — the `embedding` `vector(1536)` column AND the partial HNSW index AND the `embedding_status` column ship in 01a's V…800100 migration so 01h adds zero schema; the `CREATE EXTENSION IF NOT EXISTS vector` statement is in V…800800 (last) so 01h enables the extension first, then 01h adds the index. **OR**: defer the entire vector column too — it makes the migration smaller. **Decision: defer the vector column**. Add `embedding_status varchar(16) NOT NULL DEFAULT 'pending'` only — that's a cheap scalar column that 01h's listener writes; the `vector(1536)` column itself comes in 01h's migration alongside the extension and index. Saves provisioning pgvector in 01a's Testcontainers test path.
- `RecipeDiffDto` + `VersionDiffer` + `diff(fromVersionId, toVersionId)` endpoint → **recipe-01c** (alongside manual edit which produces v2)
- Search (`RecipeSearchCriteriaDto`, `TagSearchHelper`, `Page<RecipeDto> search`) → **recipe-01i**
- `getIngredientMappingKeys` cross-module helper for `HardConstraintFilterService` → **recipe-01j** (preference 01b consumer; small wrapper around the existing `recipe_ingredients` table)
- `RecipeImport` table + `getImportProvenance` → **recipe-01b** (with URL import)
- `forkedFromRecipeId`, `archivedAt`, `deletedAt`, `lastUsedInPlanAt`, `currentBranchId`, `current_branch_id` FK in V…800201 → **partially in scope**: **the columns ship in 01a's migration** (cheap; the LLD already has them committed); the *FK* in V…120201 ships in 01a too; only the *flows* that mutate them defer. This avoids future migration churn.

01a unblocks downstream callers needing "store a user-created recipe + fetch by id" — that's the read-by-others contract. The HTML import pipeline (much bigger work) layers on next.

This is the first recipe ticket. Module package is currently empty.

**LLD divergence note**: the LLD bundles the embedding column, partial HNSW index, and `CREATE EXTENSION vector` into V…120100 / V…120900. Per the deferral above, 01a ships only the `embedding_status` scalar column (default `'pending'`); 01h ships the `embedding vector(1536)` column + extension + index. Migration `V20260601800600__recipe_create_recipe_imports_table.sql` is **deferred to recipe-01b** (URL import) — there's no import provenance to record in 01a since `manual_create` is the only trigger.

## Behavioural spec

### Aggregate shape — write path for `manual_create`

1. `Recipe` is the aggregate root per [LLD V20260601120000 lines 67-95](../../lld/recipe.md). Fields: `id, userId, catalogue (Catalogue: USER|SYSTEM — default USER for `POST /recipes`), name (varchar 160), description (text nullable), currentVersion (int default 1), currentBranchId (UUID — populated atomically when the main branch lands), dataQuality (DataQuality: USER_VERIFIED|IMPORTED|AI_GENERATED|WEB_DISCOVERED — default USER_VERIFIED for manual_create), nutritionStatus (NutritionStatus: CALCULATED|PENDING|PARTIAL — default PENDING since 01a doesn't compute), forkedFromRecipeId (UUID nullable), lastUsedInPlanAt (Instant nullable), archivedAt (Instant nullable), deletedAt (Instant nullable), optimisticVersion (@Version Long), createdAt, updatedAt`. **No `@OneToMany` to versions/branches** — queried separately to keep the root small (LLD line 277).
2. `RecipeBranch` per [LLD V20260601120200 lines 137-152](../../lld/recipe.md). Fields: `id, recipeId (via @ManyToOne(fetch=LAZY) Recipe + @JoinColumn name="recipe_id", on delete cascade), parentBranchId (UUID nullable; null only for 'main'), branchPointVersionId (UUID nullable; null for v1 of the main branch), name (varchar 64; 'main' for the auto-created branch), label (varchar 120 nullable), reason (text nullable), currentVersion (int default 1), divergenceScore (BigDecimal 4,3 default 0.000), createdAt, createdByActor (varchar 32: 'user:<uuid>' for manual_create), adapterTraceId (UUID nullable; null for manual_create), version (@Version Long)`. UNIQUE `(recipe_id, name)`.
3. `RecipeVersion` per [LLD V20260601120100 lines 102-127](../../lld/recipe.md). Append-only. Fields: `id, recipeId, branchId, versionNumber (int — 1 for the initial create), parentVersionId (UUID nullable; null for v1 of any branch), changeDiff (JsonNode JSONB; for v1 manual_create, write the empty object `{}`), changeReason (text nullable), trigger (VersionTrigger: MANUAL_CREATE|MANUAL_EDIT|IMPORT|ADAPTATION_PIPELINE|SUBSTITUTION_PROMOTION|BRANCH_CREATION|REVERT — for 01a always MANUAL_CREATE), characterFingerprint (JsonNode JSONB nullable; null in 01a — refreshed only on branch creation per LLD), nutritionPerServing (JsonNode JSONB nullable; null in 01a — written by NutritionCalculationService later), embeddingStatus (varchar 16, default 'pending'), createdAt, createdByActor (varchar 32: 'user:<uuid>'), adapterTraceId (UUID nullable)`. UNIQUE `(recipe_id, branch_id, version_number)`. Owns `@OneToMany(cascade=ALL, orphanRemoval=true)` to `RecipeIngredient` and `RecipeMethodStep`. Owns `@OneToOne(cascade=ALL, orphanRemoval=true)` to `RecipeMetadata` and `RecipeTags`.
4. `RecipeIngredient` per LLD V20260601120400 lines 183-199. Fields: `id, versionId, lineOrder (int — preserves UI order), ingredientMappingKey (varchar 160), displayName (varchar 160), quantity (BigDecimal 10,3 nullable — for "to taste" items), unit (varchar 16 nullable), preparation (varchar 80 nullable), optional (boolean default false), needsReview (boolean default false), mappingConfidence (BigDecimal 4,3 nullable — null in 01a, populated by USDA mapping pipeline later)`. UNIQUE `(version_id, line_order)`.
5. `RecipeMethodStep` per LLD V20260601120500 lines 209-215. Fields: `id, versionId, stepNumber (int), instruction (text), durationMinutes (Integer nullable)`. UNIQUE `(version_id, step_number)`.
6. `RecipeMetadata` per LLD V20260601120600 lines 218-229. One row per version (`@OneToOne`). Fields: `id, versionId UNIQUE, servings (int), prepTimeMins (int), cookTimeMins (int), totalTimeMins (int), equipmentRequired (List<String> JSONB — same `text[]`-→-JSONB workaround as preference/nutrition), fridgeDays (Integer nullable), freezerWeeks (Integer nullable), packable (boolean default false), cuisine (varchar 64 nullable), mealTypes (List<String> JSONB)`.
7. `RecipeTags` per LLD V20260601120700 lines 232-243. One row per version. Fields: `id, versionId UNIQUE, protein (varchar 64 nullable), cookingMethod (varchar 64 nullable), complexity (Complexity enum: MINIMAL|MODERATE|INVOLVED nullable), flavourProfile (List<String> JSONB), dietaryFlags (List<String> JSONB)`. **In 01a these fields are accepted from the request as-is — no AI inference**. The LLD says "AI inference fills if absent"; 01a treats all tag fields as user-supplied or null.

**`text[]` → `jsonb List<String>` workaround**: same as preference / nutrition. Hibernate's `text[]` mapping is brittle on Spring Boot 3.2.5 / hypersistence-utils-63. Use `@Type(JsonBinaryType.class)` with `List<String>`. Update the migrations: `equipment_required jsonb NOT NULL DEFAULT '[]'::jsonb`, `meal_types jsonb NOT NULL DEFAULT '[]'::jsonb`, `flavour_profile jsonb NOT NULL DEFAULT '[]'::jsonb`, `dietary_flags jsonb NOT NULL DEFAULT '[]'::jsonb`. The GIN indexes from the LLD on these columns become **GIN indexes on the JSONB** (`USING gin (column jsonb_path_ops)`) — same query patterns work, syntax differs slightly. **Defer the GIN indexes to recipe-01i** (search ticket); 01a has no filtering on these fields.

### `createRecipe` flow

8. `POST /api/v1/recipes` accepts `CreateRecipeRequest { name, description, ingredients[], method[], metadata, tags? }`. Auth required. Server resolves `userId` via `CurrentUserResolver`.
9. Single `@Transactional` write: insert `Recipe` (uuid; catalogue=USER; dataQuality=USER_VERIFIED; nutritionStatus=PENDING; currentVersion=1); insert `RecipeBranch` (name='main', parentBranchId=null, branchPointVersionId=null, currentVersion=1, divergenceScore=0.000, createdByActor=`'user:<uuid>'`); insert `RecipeVersion` (versionNumber=1, parentVersionId=null, trigger=MANUAL_CREATE, changeDiff=`{}`, embeddingStatus='pending', createdByActor=`'user:<uuid>'`); insert ingredients (line_order = index in request list); insert method steps; insert metadata; insert tags (with all-null fields if no `tags` block in the request). After the version is persisted, set `Recipe.currentBranchId = branch.id` and `Recipe.currentVersion = 1`. Return `RecipeDto` (201) + `Location: /api/v1/recipes/{recipeId}`.
10. `RecipeCreatedEvent(UUID recipeId, Catalogue catalogue, UUID traceId, Instant occurredAt)` published `AFTER_COMMIT`. No listeners in 01a — emitted for downstream consumers.
11. `RecipeVersionCreatedEvent(UUID versionId, UUID recipeId, UUID branchId, int versionNumber, UUID traceId, Instant occurredAt)` published `AFTER_COMMIT` for v1. **Required by 01h's async embedding listener**, but no listener in 01a.

### `getById` (read-by-others)

12. `GET /api/v1/recipes/{recipeId}` returns hydrated `RecipeDto` with the current version's full body (ingredients + method + metadata + tags). 200 or 404 `RecipeNotFoundException`. **No userId ownership check** — recipes are visible to any authenticated caller (the LLD frames system + user catalogues as both readable; user-private filtering belongs in search/list endpoints later, NOT here for `getById` since the planner needs to read recipes from any user's catalogue). **Branches list (`RecipeBranchDto[]`) deferred to recipe-01b** — internally there's exactly one 'main' branch per recipe in 01a, so exposing the list adds no information for callers; 01b layers it on alongside branch creation.
13. Repository: `recipeRepository.findByIdAndDeletedAtIsNull(id)` → `recipeVersionRepository.findById(currentVersionId)` (where `currentVersionId` is derived from `Recipe.currentVersion + Recipe.currentBranchId` via a separate query). **Do NOT use a multi-attribute `@EntityGraph` on the version body** — `ingredients` and `methodSteps` are both `@OneToMany List<>` and that throws `MultipleBagFetchException`. Instead: load the version plain, then inside `@Transactional(readOnly = true)` touch each collection via getters to force lazy load. Cost: 4 extra SELECTs per call (ingredients + methodSteps + metadata + tags). Mapper applies explicit `Comparator` ordering (`lineOrder` for ingredients; `stepNumber` for method steps) when building the DTO list. **Do NOT use `Set<>`** — entity equals/hashCode pitfalls outweigh the saving.
14. `RecipeQueryService.getById(UUID recipeId): Optional<RecipeDto>` is the cross-module read-by-others method. Used by future planner / nutrition / hard-constraint-filter callers.

### Validation

16. Standard Jakarta annotations on `CreateRecipeRequest`: `@NotBlank @Size(max=160) String name`; `@Size(max=2000) String description`; `@NotEmpty @Valid List<...> ingredients`; `@NotEmpty @Valid List<...> method`; `@NotNull @Valid CreateRecipeMetadataRequest metadata`; `@Valid CreateRecipeTagsRequest tags` (optional). **Custom validators in scope for 01a**:
    - `@ValidIngredientList` (class-level on `List<CreateIngredientRequest>`): all `lineOrder`s unique and contiguous (0…N-1) — relax to "unique" if the agent assigns line-order server-side.
    - `@ValidMethodSteps` (class-level): step numbers unique and contiguous starting at 1.
    - `@ValidRecipeMetadata` (class-level on metadata): `totalTimeMins == prepTimeMins + cookTimeMins` (or within ±1 to allow rounding).
17. **`@URL` validation** (used by `ImportRecipeFromUrlRequest` per LLD line 394) is out of scope — that request shape lands with 01b.

### Cross-module facade + boundary

18. `RecipeModule.java` facade re-exports `RecipeQueryService` and `RecipeUpdateService` (interfaces only; partial — 01a methods only).
19. Repositories package-private. `RecipeBoundaryTest` at `src/test/java/com/example/mealprep/recipe/RecipeBoundaryTest.java`: classes outside `com.example.mealprep.recipe..` must not depend on `com.example.mealprep.recipe..domain.repository..`.

### Errors

20. New module-root `RecipeException extends RuntimeException`. Subclass for 01a: `RecipeNotFoundException` (404). New `RecipeExceptionHandler` `@RestControllerAdvice` at `com.example.mealprep.recipe.api`, **annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`**. Maps to ProblemDetail with `type = .../recipe-not-found`.
21. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler`. Validation failures via `MethodArgumentNotValidException` likewise.

## Database

```
src/main/resources/db/migration/V20260601800000__recipe_create_recipes.sql                  new
src/main/resources/db/migration/V20260601800100__recipe_create_recipe_versions.sql          new (NO vector column; NO HNSW index — both deferred to recipe-01h)
src/main/resources/db/migration/V20260601800200__recipe_create_recipe_branches.sql          new
src/main/resources/db/migration/V20260601800201__recipe_add_branch_fks.sql                  new (FK from recipes.current_branch_id, recipe_versions.branch_id)
src/main/resources/db/migration/V20260601800400__recipe_create_ingredients.sql              new
src/main/resources/db/migration/V20260601800500__recipe_create_method_steps.sql             new
src/main/resources/db/migration/V20260601800600__recipe_create_recipe_metadata.sql          new
src/main/resources/db/migration/V20260601800700__recipe_create_recipe_tags.sql              new
```

Schema mirrors LLD V20260601120000 / 120100 / 120200 / 120201 / 120400 / 120500 / 120600 / 120700. **Renumber to V20260601800xxx**. **Skip**: `V20260601120300__recipe_create_recipe_substitutions.sql` (deferred to 01e), `V20260601120800__recipe_create_recipe_imports.sql` (deferred to 01b), `V20260601120900__recipe_create_recipe_embeddings.sql` (deferred to 01h), `R__recipe_seed_complexity_tier_lookup.sql` (used by tag inference / validation; 01a treats complexity as user-supplied free enum so the lookup table isn't needed yet — defer to 01i).

**Adjustments to the LLD migration text** for 01a:
- `recipe_versions` migration: omit the `embedding vector(1536)` column. Keep `embedding_status varchar(16) NOT NULL DEFAULT 'pending'`. Omit the `embedding_model_id` and `embedded_at` columns (they're written only after a successful embed; add them in 01h).
- `recipe_metadata`, `recipe_tags`: replace `text[]` columns with `jsonb` (see "text[] workaround" above).
- `recipe_metadata`, `recipe_tags`: skip the GIN indexes (`idx_recipe_metadata_equipment_gin`, `idx_recipe_tags_flavour_gin`, etc.) — defer to 01i alongside search.
- Keep: `idx_recipe_recipes_catalogue_active`, `idx_recipe_recipes_user_catalogue`, `idx_recipe_recipes_system_last_used`, `idx_recipe_recipes_name_lower`, `idx_recipe_recipes_forked_from`, `idx_recipe_versions_recipe_branch_ver`, `idx_recipe_versions_trace_id`, `idx_recipe_branches_recipe`, `idx_recipe_branches_divergence`, `idx_recipe_ingredients_version`, `idx_recipe_ingredients_mapping_key`, `idx_recipe_ingredients_version_key`, `idx_recipe_method_steps_version`, `idx_recipe_metadata_total_time`, `idx_recipe_metadata_cuisine`, `idx_recipe_tags_protein`, `idx_recipe_tags_cooking_method`, `idx_recipe_tags_complexity`. These index hot-path scalar columns; skipping them now means re-indexing later, which on a populated table is much more expensive. Cheap up front.

## OpenAPI updates

### New `src/main/resources/openapi/paths/recipe.yaml`

```yaml
recipes:
  post:
    tags: [Recipes]
    operationId: createRecipe
    summary: Create a new user-catalogue recipe (manual_create trigger).
    security: [{ cookieAuth: [] }]
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/recipe.yaml#/CreateRecipeRequest' }
    responses:
      '201':
        description: Recipe created.
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
recipeById:
  get:
    tags: [Recipes]
    operationId: getRecipe
    summary: Fetch a recipe by id (current version body + branches list).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: Recipe.
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### New `src/main/resources/openapi/schemas/recipe.yaml`

Schemas: `Catalogue`, `DataQuality`, `NutritionStatus`, `VersionTrigger`, `Complexity`, `MealType`, `IngredientDto`, `MethodStepDto`, `RecipeMetadataDto` (with nested `StoresWellDto`), `RecipeTagsDto`, `RecipeVersionDto`, `RecipeDto`, `CreateIngredientRequest`, `CreateMethodStepRequest`, `CreateRecipeMetadataRequest`, `CreateRecipeTagsRequest`, `CreateRecipeRequest`. Field shapes per [LLD lines 293-411](../../lld/recipe.md). **Drop**: `RecipeBranchDto` (deferred to **recipe-01b** alongside the `branches[]` field on `RecipeDto`); `CharacterFingerprintDto` (always null in 01a — defer to schema in 01d/branch ticket); `RecipeSubstitutionDto`; `RecipeDiffDto`; `RecipeSearchCriteriaDto`; `ImportRecipeFromUrlRequest`; `UpdateRecipeManualEditRequest`; `CreateBranchRequest`; `CreateSubstitutionRequest`. All deferred.

`RecipeDto` in 01a does NOT include a `branches` array. The current `RecipeVersionDto` carries enough info (versionNumber, currentBranchId on the parent Recipe) for callers; consumers wanting the branch list use the future `GET /api/v1/recipes/{recipeId}/branches` endpoint that lands in 01b.

### Two-region edit to `src/main/resources/openapi/openapi.yaml`

Append under `paths:`:

```yaml
  /api/v1/recipes:
    $ref: 'paths/recipe.yaml#/recipes'
  /api/v1/recipes/{recipeId}:
    $ref: 'paths/recipe.yaml#/recipeById'
```

Append under `components.schemas:` (one ref line per schema — ~16 lines; `RecipeBranchDto` deferred to recipe-01b).

## Verbatim shape snippets

### Recipe entity (root) — JSONB-free root

```java
@Entity
@Table(name = "recipe_recipes")
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Recipe {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;
  @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "catalogue", nullable = false, length = 16) private Catalogue catalogue;

  @Column(name = "name", nullable = false, length = 160) private String name;
  @Column(name = "description") private String description;
  @Column(name = "current_version", nullable = false) private int currentVersion;
  @Column(name = "current_branch_id") private UUID currentBranchId;

  @Enumerated(EnumType.STRING)
  @Column(name = "data_quality", nullable = false, length = 16) private DataQuality dataQuality;

  @Enumerated(EnumType.STRING)
  @Column(name = "nutrition_status", nullable = false, length = 16) private NutritionStatus nutritionStatus;

  @Column(name = "forked_from_recipe_id") private UUID forkedFromRecipeId;
  @Column(name = "last_used_in_plan_at") private Instant lastUsedInPlanAt;
  @Column(name = "archived_at") private Instant archivedAt;
  @Column(name = "deleted_at") private Instant deletedAt;

  @Version @Column(name = "optimistic_version", nullable = false) private long optimisticVersion;
  @CreationTimestamp @Column(name = "created_at", updatable = false, nullable = false) private Instant createdAt;
  @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;
}
```

### RecipeVersion entity — JSONB columns + child collections (List<> + lazy-load inside @Transactional)

```java
@Entity
@Table(name = "recipe_versions",
       uniqueConstraints = @UniqueConstraint(columnNames = {"recipe_id", "branch_id", "version_number"}))
@Getter @Setter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RecipeVersion {
  @Id @Column(name = "id", updatable = false, nullable = false) private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recipe_id", nullable = false) private Recipe recipe;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "branch_id", nullable = false) private RecipeBranch branch;

  @Column(name = "version_number", nullable = false) private int versionNumber;
  @Column(name = "parent_version_id") private UUID parentVersionId;

  @Type(JsonBinaryType.class)
  @Column(name = "change_diff", nullable = false, columnDefinition = "jsonb")
  private JsonNode changeDiff;

  @Column(name = "change_reason") private String changeReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger", nullable = false, length = 32) private VersionTrigger trigger;

  @Type(JsonBinaryType.class)
  @Column(name = "character_fingerprint", columnDefinition = "jsonb")
  private JsonNode characterFingerprint;

  @Type(JsonBinaryType.class)
  @Column(name = "nutrition_per_serving", columnDefinition = "jsonb")
  private JsonNode nutritionPerServing;

  @Column(name = "embedding_status", nullable = false, length = 16) private String embeddingStatus;

  @CreationTimestamp @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;
  @Column(name = "created_by_actor", nullable = false, length = 32) private String createdByActor;
  @Column(name = "adapter_trace_id") private UUID adapterTraceId;

  // Children — List<> with no @EntityGraph at the repo. Service touches getters inside
  // @Transactional to force lazy load; mapper applies Comparator ordering when building the DTO.
  @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  @Builder.Default private List<RecipeIngredient> ingredients = new ArrayList<>();

  @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  @Builder.Default private List<RecipeMethodStep> methodSteps = new ArrayList<>();

  @OneToOne(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  private RecipeMetadata metadata;

  @OneToOne(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
  private RecipeTags tags;
}
```

### Repository — package-private

```java
interface RecipeRepository extends JpaRepository<Recipe, UUID> {
  Optional<Recipe> findByIdAndDeletedAtIsNull(UUID id);
}

interface RecipeVersionRepository extends JpaRepository<RecipeVersion, UUID> {
  // No @EntityGraph — multi-bag fetch trap. Service lazy-loads collections inside @Transactional.
  Optional<RecipeVersion> findFirstByRecipeIdAndBranchIdAndVersionNumber(
      UUID recipeId, UUID branchId, int versionNumber);
}

interface RecipeBranchRepository extends JpaRepository<RecipeBranch, UUID> {
  // The "list branches for recipe" query lands with recipe-01b alongside the branches[] DTO.
}

interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, UUID> {}
interface RecipeMethodStepRepository extends JpaRepository<RecipeMethodStep, UUID> {}
interface RecipeMetadataRepository  extends JpaRepository<RecipeMetadata, UUID> {}
interface RecipeTagsRepository      extends JpaRepository<RecipeTags, UUID> {}
```

### Service interfaces (01a subset only)

```java
public interface RecipeQueryService {
  Optional<RecipeDto> getById(UUID recipeId);
}

public interface RecipeUpdateService {
  RecipeDto createRecipe(UUID userId, CreateRecipeRequest request);
}
```

The full LLD service surface (LLD lines 517-548) covers 25+ methods across query / update / write-api — **all out of scope here**.

## Edge-case checklist

- [ ] `POST /recipes` for an authenticated user → 201, all 4 child collections populated, `Recipe.currentBranchId` set to the new 'main' branch's id, `Recipe.currentVersion = 1`, `Location` header set
- [ ] `POST /recipes` writes 7 rows in one tx: 1 recipe + 1 branch + 1 version + N ingredients + M method steps + 1 metadata + 1 tags. Verify count.
- [ ] `POST /recipes` validation: empty `name` → 400; `name > 160` → 400; empty `ingredients` list → 400; empty `method` list → 400; missing `metadata` → 400
- [ ] `POST /recipes` validation: `lineOrder` collisions in ingredients → 400 via `@ValidIngredientList`
- [ ] `POST /recipes` validation: step-number collisions → 400 via `@ValidMethodSteps`
- [ ] `POST /recipes` without cookie → 401
- [ ] `GET /recipes/{recipeId}` for an existing recipe → 200, full hydrated body, ingredients ordered by `lineOrder`, method steps ordered by `stepNumber`. (Branches list is deferred — DTO has no `branches` field in 01a.)
- [ ] After `POST /recipes`, a DB-level assertion (via `JdbcTemplate`) confirms exactly one `recipe_branches` row exists for the new recipe with `name = 'main'`. The branch is internal-only in 01a; the API doesn't expose it.
- [ ] `GET /recipes/{recipeId}` for a `deletedAt`-non-null recipe → 404 (`findByIdAndDeletedAtIsNull` filters)
- [ ] `GET /recipes/{recipeId}` for a non-existent id → 404 `recipe-not-found` ProblemDetail
- [ ] `GET /recipes/{recipeId}` without cookie → 401
- [ ] No N+1 on the GET path — verified via Hibernate stats or single-statement assertion in IT
- [ ] `RecipeCreatedEvent` and `RecipeVersionCreatedEvent` each published exactly once after commit
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter)
- [ ] `RecipeBoundaryTest` passes — outside-module classes cannot import `recipe.domain.repository`
- [ ] Tag fields default cleanly when the request omits the `tags` block (all-null `RecipeTags` row inserted)
- [ ] `embedding_status = 'pending'` set on the version row (so 01h's listener has its target)

## Files this ticket touches

```
NEW   src/main/resources/db/migration/V20260601800000__recipe_create_recipes.sql
NEW   src/main/resources/db/migration/V20260601800100__recipe_create_recipe_versions.sql
NEW   src/main/resources/db/migration/V20260601800200__recipe_create_recipe_branches.sql
NEW   src/main/resources/db/migration/V20260601800201__recipe_add_branch_fks.sql
NEW   src/main/resources/db/migration/V20260601800400__recipe_create_ingredients.sql
NEW   src/main/resources/db/migration/V20260601800500__recipe_create_method_steps.sql
NEW   src/main/resources/db/migration/V20260601800600__recipe_create_recipe_metadata.sql
NEW   src/main/resources/db/migration/V20260601800700__recipe_create_recipe_tags.sql

NEW   src/main/java/com/example/mealprep/recipe/RecipeModule.java
NEW   src/main/java/com/example/mealprep/recipe/api/controller/RecipesController.java
NEW   src/main/java/com/example/mealprep/recipe/api/RecipeExceptionHandler.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeVersionDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/IngredientDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/MethodStepDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeMetadataDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeTagsDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CreateRecipeRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CreateIngredientRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CreateMethodStepRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CreateRecipeMetadataRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CreateRecipeTagsRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeMapper.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeVersionMapper.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/IngredientMapper.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/MethodStepMapper.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeMetadataMapper.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeTagsMapper.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/Recipe.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeVersion.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeBranch.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeIngredient.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeMethodStep.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeMetadata.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeTags.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/Catalogue.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/DataQuality.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/NutritionStatus.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/VersionTrigger.java
NEW   src/main/java/com/example/mealprep/recipe/domain/entity/Complexity.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeVersionRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeBranchRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeIngredientRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeMethodStepRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeMetadataRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeTagsRepository.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/RecipeQueryService.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/RecipeUpdateService.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java
NEW   src/main/java/com/example/mealprep/recipe/event/RecipeCreatedEvent.java
NEW   src/main/java/com/example/mealprep/recipe/event/RecipeVersionCreatedEvent.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeNotFoundException.java
NEW   src/main/java/com/example/mealprep/recipe/validation/ValidIngredientList.java
NEW   src/main/java/com/example/mealprep/recipe/validation/ValidIngredientListValidator.java
NEW   src/main/java/com/example/mealprep/recipe/validation/ValidMethodSteps.java
NEW   src/main/java/com/example/mealprep/recipe/validation/ValidMethodStepsValidator.java
NEW   src/main/java/com/example/mealprep/recipe/validation/ValidRecipeMetadata.java
NEW   src/main/java/com/example/mealprep/recipe/validation/ValidRecipeMetadataValidator.java

NEW   src/main/resources/openapi/paths/recipe.yaml
NEW   src/main/resources/openapi/schemas/recipe.yaml
MOD   src/main/resources/openapi/openapi.yaml                                                  (2 lines under paths:; ~16 lines under components.schemas: — RecipeBranchDto deferred)

NEW   src/test/java/com/example/mealprep/recipe/RecipeServiceImplTest.java
NEW   src/test/java/com/example/mealprep/recipe/RecipesFlowIT.java
NEW   src/test/java/com/example/mealprep/recipe/RecipeBoundaryTest.java
NEW   src/test/java/com/example/mealprep/recipe/testdata/RecipeTestData.java
```

Count is ~47 files after the pre-split (was 52; dropped `RecipeBranchDto`, `RecipeBranchMapper`, the `branches[]` field on `RecipeDto`, and the `findAllByRecipeId` query — all deferred to recipe-01b). Most are 1-line `@Enumerated`/record DTO files; the actual logic lives in `RecipeServiceImpl` + `RecipesController`. Estimated agent runtime 35-45 min.

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`

## Dependencies

- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged).
- **Sibling tickets running in parallel** (Wave 2 round 1): `household-01a`, `nutrition-01a`, `provisions-01a`. None should touch any recipe file or any cross-cutting file.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `RecipeExceptionHandler` annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`
- [ ] No regression on existing tests
- [ ] No N+1 on the GET-by-id path
- [ ] `Recipe.currentBranchId` set atomically with the branch insert (not as a follow-up SET in a separate tx)

## What's NOT in scope

- `branches[]` field on `RecipeDto`, `RecipeBranchDto`, `RecipeBranchMapper`, `findAllByRecipeId` query, `GET /api/v1/recipes/{recipeId}/branches` endpoint → **recipe-01b** (alongside the branch creation flow that actually populates non-main branches). The `recipe_branches` table is created in 01a's migration with the 'main' row inserted internally per recipe; 01b layers the user-facing list on top.
- HTML extraction / URL import / `ImportRecipeFromUrlRequest` / `HtmlImportParser` / `RecipeImport` table → **recipe-01b** (also lands `getImportProvenance`)
- Manual edit (`UpdateRecipeManualEditRequest` → version v2+ on the same branch), `RecipeUpdatedEvent`, `VersionDiffer`, `change_diff` payload generation, `diff(fromVersionId, toVersionId)` endpoint and DTOs → **recipe-01c**
- Branch creation (`CreateBranchRequest`, `RecipeBranch` non-main rows, divergence-score computation, `CharacterFingerprintDto` population, fingerprint refresh) → **recipe-01d**
- Substitutions (`RecipeSubstitution` entity + table V…120300, `CreateSubstitutionRequest`, `getActiveSubstitutions`, `getSubstitutionsForVersion`, substitution-state machine, `SubstitutionOverlayApplier`, `recordSubstitution`) → **recipe-01e**
- `RecipeWriteApi` SPI for the adaptation pipeline (add-version / branch / substitution / fingerprint refresh / nutrition recalc / embedding store) → **recipe-01f** (depends on `ai` module)
- `RecipeAdaptedEvent` + `RecipeEvolvedEvent` + listener that triggers nutrition recalc → **recipe-01f**
- Promotion (system → user catalogue), demotion (`deletedAt` set), archive (`archivedAt` set, `last_used_in_plan_at` updated by planner), `ArchiveEligibilityScanner` daily scan, `RecipePromotedEvent`, `RecipeArchivedEvent` → **recipe-01g**
- pgvector — `vector(1536)` column, `CREATE EXTENSION IF NOT EXISTS vector`, partial HNSW index, `EmbeddingTask`, async listener on `RecipeVersionCreatedEvent` → `aiService.embed`, `getEmbedding` cross-module helper → **recipe-01h**
- Search (`RecipeSearchCriteriaDto`, `Page<RecipeDto> search`, `TagSearchHelper`, JPA `Specification`s, GIN indexes on `equipment_required` / `meal_types` / `flavour_profile` / `dietary_flags` / pgvector similarity search) → **recipe-01i**
- Cross-module helpers: `getIngredientMappingKeys(recipeId)`, `getIngredientMappingKeysByIds(List<UUID>)`, `getFingerprint`, `getNutritionPerServing`, `getEmbedding`, `getImportProvenance` → land with their respective tickets (most in 01j as a small wrapper ticket)
- AI tag inference (cuisine / protein / cookingMethod / complexity / flavourProfile / dietaryFlags) — 01a accepts these as user-supplied or null → **recipe-01k** (depends on `ai` module)
- Soft-delete `DELETE /recipes/{recipeId}`, hard-delete (test-only) → **recipe-01g**
- Multi-controller split (`RecipeVersionsController`, `RecipeBranchesController`, `RecipeSubstitutionsController`, `RecipeImportsController`, `RecipeAdminController`) — 01a uses a single `RecipesController`; the others land with their respective tickets

Squash-merge with: `feat(recipe): 01a — recipe aggregate (root + main branch + v1 body) + create/getById endpoints`
