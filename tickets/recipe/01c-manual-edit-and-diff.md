# Ticket: recipe — 01c Manual Edit + Version Diff

## Summary

Layer the **manual-edit-creates-v2+** flow on top of the 01a/01b recipe aggregate: `UpdateRecipeManualEditRequest` accepted at `PUT /api/v1/recipes/{recipeId}` writing a new `RecipeVersion` (v2, v3, …) on the recipe's **current branch** (NOT a new branch — that's 01d), the `VersionDiffer` that builds the structured `change_diff` JSONB at write time, the `RecipeUpdatedEvent` published `AFTER_COMMIT`, plus the read-side `GET /api/v1/recipes/{recipeId}/versions/{a}/diff/{b}` endpoint returning `RecipeDiffDto` (a key-value lookup of the persisted `change_diff`, NOT a recompute). Per [`lld/recipe.md`](../../lld/recipe.md) §`UpdateRecipeManualEditRequest` (lines 396-404), §`RecipeDiffDto` (lines 371-379), §`VersionDifferTest` (line 805 — pure logic), §`RecipeUpdatedEvent` (lines 695-696), §Service Interfaces (`manualEdit` line 557, `diff` line 534), §REST Controllers (`PUT /{id}` line 638, `GET /diff?from=&to=` line 648 — but 01c uses path-form per LLD divergence below).

**Defers** (still out of scope after 01c):
- **Branch creation** (`CreateBranchRequest`, non-main rows, divergence-score recomputation, `CharacterFingerprintDto` population on branch) → **recipe-01d**
- Substitutions (`RecipeSubstitution` + endpoints + substitution-promotion as a version) → **recipe-01e**
- `RecipeWriteApi` SPI + adaptation events (`RecipeAdaptedEvent`, `RecipeEvolvedEvent` listener for nutrition recalc) → **recipe-01f**
- Promotion / demotion / archive / `ArchiveEligibilityScanner` → **recipe-01g**
- pgvector + embedding column population (async listener on `RecipeVersionCreatedEvent` — emits in 01a, listener lands in 01h) → **recipe-01h**
- Search → **recipe-01i**
- Cross-module helpers `getIngredientMappingKeys`, `getFingerprint`, `getNutritionPerServing`, `getEmbedding` → **recipe-01j**
- AI tag inference → **recipe-01k**
- `revertToVersion` REST endpoint (LLD §REST line 647 — `POST /revert`) → **recipe-01d** (alongside branches; revert is conceptually a branch-pointer move)
- `DeduplicationFingerprintHasher` for edit-time dedup detection — 01c trusts the PUT path; dedup is a 01g concern (still deferred from 01a/01b)

01c unblocks the basic editing flow: the user creates a recipe in 01a, possibly imports one in 01b, and now in 01c can edit it (creating v2, v3, ...). Without 01c, recipes are append-only at v1 forever; the only way to change a recipe is to re-create it. 01c also ships the **diff endpoint** so the UI can render "what changed in v2".

**LLD divergence note** — **diff endpoint URL shape**: LLD §REST line 648 specifies `GET /{id}/versions/diff?fromVersionId=&toVersionId=` (query params). 01c uses **path form** `GET /{id}/versions/{fromVersionId}/diff/{toVersionId}` because:
- The two version ids are part of the resource identity (the diff is a resource between two versions, not a query result).
- Cleaner OpenAPI shape — two `path` parameters with `format: uuid` validate inline; query-form would need a custom validator.
- HTTP cache semantics — path-form responses are trivially cacheable on `(fromVersionId, toVersionId)` (immutable: the persisted `change_diff` never changes after the version row commits); query-form is harder for some CDNs.
- The LLD calls this out as "**worth user review**" implicitly (the LLD's REST table is consistent: nested resources prefer path; this looks like an oversight). Document the divergence on the controller class Javadoc.

**LLD divergence note** — **diff sourcing**: LLD line 381 says "`change_diff` on each version row is the same shape persisted as JSONB — `VersionDiffer` builds it once at write time, so the diff endpoint is a key-value lookup, not a recompute." 01c implements this **strictly**: the diff endpoint MUST NOT recompute. Specifically:
- For consecutive versions on the same branch (i.e., `fromVersionId = toVersionId.parentVersionId`): return `toVersionId.changeDiff` verbatim, wrapped in `RecipeDiffDto`.
- For non-consecutive versions on the same branch (skip-version diffs, e.g., v1 → v3): **return 422 `RecipeDiffNotComputedException`** (NEW exception). This forces the UI / planner to render diffs only between consecutive versions in v1; cross-version recompute is a 01d-or-later concern. **Worth user review** — the alternative is to recompute on demand, but that breaks the "key-value lookup" contract and would duplicate `VersionDiffer` logic. Document on the controller Javadoc.
- For cross-branch diffs (different `branchId`): 422 `RecipeDiffCrossBranchException` (NEW exception). Cross-branch comparison needs branch-merge semantics that don't exist until 01d.

## Behavioural spec

### Aggregate shape — append-only versions

1. **No new entity in 01c** — `RecipeVersion` already exists from 01a (with all fields including `change_diff`, `parent_version_id`, `trigger`, `version_number`, `branch_id`). 01a creates v1 with `changeDiff = {}` (empty object) and `trigger = MANUAL_CREATE`. 01c writes v2+ with `trigger = MANUAL_EDIT` and `changeDiff = <computed>`.
2. **`recipe_versions` is append-only** per [LLD line 130](../../lld/recipe.md): "never updated after initial save (with three well-defined exceptions: nutrition recalculation, fingerprint refresh on branch, and embedding population, all via WriteApi)." 01c **does not modify any existing row**. The v2+ write is a pure INSERT.
3. **`Recipe.currentVersion` and `Recipe.currentBranchId` are mutated** — `Recipe.currentVersion` increments to the new version's `versionNumber`; `currentBranchId` is unchanged (manual edit stays on the current branch). `Recipe.optimisticVersion` (the `@Version` column on `Recipe`) bumps. `RecipeBranch.currentVersion` also increments.

### `manualEdit` flow (Flow 3 in the LLD)

4. `PUT /api/v1/recipes/{recipeId}`. Body: `UpdateRecipeManualEditRequest` per [LLD lines 396-404](../../lld/recipe.md):
   ```java
   public record UpdateRecipeManualEditRequest(
       @NotBlank @Size(max=160) String name,
       @Size(max=2000) String description,
       @NotEmpty @Valid List<CreateIngredientRequest> ingredients,
       @NotEmpty @Valid List<CreateMethodStepRequest> method,
       @NotNull @Valid CreateRecipeMetadataRequest metadata,
       @Valid CreateRecipeTagsRequest tags,                                  // optional
       @NotBlank String changeReason,                                        // user-supplied; goes into recipe_versions.change_reason
       long expectedOptimisticVersion                                         // 409 on stale
   ) {}
   ```
   Cookie-auth required. Server resolves `actorUserId` via `CurrentUserResolver`.
5. **Authorisation**: caller must own the recipe (`recipe.userId == actorUserId`) for `catalogue = USER` recipes — else 404 `RecipeNotFoundException` (don't leak existence; same rule as 01a's get-by-id ownership check). For `catalogue = SYSTEM` recipes, 01c **rejects** with 422 `RecipeCatalogueViolationException` (LLD line 668; LLD line 683 — "manual edit cannot promote a system recipe (use `promoteToUserCatalogue`)"). The user must promote first (deferred to 01g).
6. **Stale `expectedOptimisticVersion`**: if `recipe.optimisticVersion != request.expectedOptimisticVersion()` → 409 via `OptimisticLockingFailureException` (mapped by `GlobalExceptionHandler`). LLD line 422: "`expectedOptimisticVersion` on manual edit guards against silently overwriting an in-flight pipeline write."
7. Single `@Transactional` write:
   1. Load `Recipe` via `findByIdAndDeletedAtIsNull(recipeId)`. 404 if missing or deleted.
   2. Reject if `catalogue = SYSTEM` (invariant 5).
   3. Reject if `expectedOptimisticVersion` mismatched (invariant 6).
   4. Load the **current version** (`recipeVersionRepository.findWithBodyById(currentVersionId)` — uses 01a's `@EntityGraph` on body children). Force-touch `ingredients` / `methodSteps` getters inside the `@Transactional` to lazy-load (mirrors 01a's strategy). This is the `parentVersion` for the new write.
   5. **Compute `change_diff`** via `VersionDiffer.diff(parentVersion, requestedNewBody): JsonNode` — see "VersionDiffer" section below.
   6. **No-op detection**: if the computed `change_diff` is empty (`{ ingredientChanges: [], methodChanges: [], metadataChanges: [], tagChanges: [] }`), **reject with 400 `NoChangesException`** (NEW exception). Justification: a no-op edit shouldn't pollute the version history; the LLD doesn't explicitly cover this but Flow 3 implies "the user changed something." **Worth user review** — alternative is to silently 200 with the existing version. **Pick 400** because the user supplied a `changeReason`; ignoring it is surprising. **LLD divergence noted**.
   7. **Persist new `RecipeVersion`** row:
      - `id = UUID.randomUUID()`
      - `recipeId = recipe.id`
      - `branchId = recipe.currentBranchId` (stays on the current branch — locked from the parent's instructions: "creates version v2+ on the same branch")
      - `versionNumber = parentVersion.versionNumber + 1`
      - `parentVersionId = parentVersion.id`
      - `changeDiff = <computed>`
      - `changeReason = request.changeReason()`
      - `trigger = MANUAL_EDIT`
      - `characterFingerprint = null` (only refreshed on branch creation per LLD line 113 — 01d's concern)
      - `nutritionPerServing = null` (recalculated by 01f's `NutritionCalculationService` listener via `RecipeUpdatedEvent`)
      - `embeddingStatus = 'pending'` (01h's listener picks it up)
      - `createdByActor = "user:" + actorUserId.toString()` (varchar 32 width is sufficient — `"user:" + UUID.toString()` is exactly 41 chars; **column width gotcha** — see invariant 18 below; 01a's column already widened per ticket convention)
      - `adapterTraceId = null` (manual edit; not pipeline)
      - `createdAt = Instant.now()`
   8. **Insert child rows for the new version** — `RecipeIngredient` × N (lineOrder = index in request), `RecipeMethodStep` × N (stepNumber = step's number in request), `RecipeMetadata` (one row), `RecipeTags` (one row, with all-null fields if `tags` block omitted). All rows reference the new version's `id` via `version_id`. The old version's children are **untouched** — append-only versioning means each version's body is its own set of rows.
   9. **Update `Recipe`**: `currentVersion = parentVersion.versionNumber + 1`, `updatedAt` (auto via `@LastModifiedDate`); JPA bumps `optimisticVersion`. Optionally update `name` / `description` on the `Recipe` row to mirror the new version's metadata (LLD line 67 has `recipe_recipes.name varchar(160)` — keep `Recipe.name` and `Recipe.description` synced with the latest version's values for cheap list-endpoint reads). **Decision: yes, sync** — list endpoints read from `Recipe` not `RecipeVersion`; mismatch confuses the dashboard.
   10. **Update `RecipeBranch`**: `currentVersion = parentVersion.versionNumber + 1`. Bump branch's `@Version`.
   11. Return `RecipeDto` (200) — hydrated with the new current version's body. Re-use 01a's hydration helper.
8. **Events**:
   - Publish `RecipeUpdatedEvent(UUID recipeId, UUID branchId, UUID newVersionId, int newVersionNumber, VersionTrigger trigger, UUID traceId, Instant occurredAt)` `AFTER_COMMIT` per [LLD lines 695-696](../../lld/recipe.md). `trigger = MANUAL_EDIT`. No listeners in 01c — emitted for downstream consumers (nutrition recalc in 01f; planner re-opt in a future planner ticket).
   - Publish `RecipeVersionCreatedEvent(UUID versionId, UUID recipeId, UUID branchId, int versionNumber, UUID traceId, Instant occurredAt)` `AFTER_COMMIT` — same event 01a publishes for v1. Required by 01h's async embedding listener; no listener in 01c.

### `VersionDiffer` (pure logic, in `domain/service/internal/`)

9. `VersionDiffer` is a `@Component` in `domain/service/internal/`. Single method:
   ```java
   public JsonNode diff(RecipeVersion parent, NewVersionInput requested);
   ```
   `NewVersionInput` is a record carrying the post-mapping new ingredient list / method / metadata / tags (post-Jakarta validation; pre-persist). The output is a Jackson `JsonNode` shaped per [LLD §RecipeDiffDto lines 371-379](../../lld/recipe.md):
   ```json
   {
     "fromVersionId": "<uuid>",
     "toVersionId": "<uuid>",
     "ingredientChanges": [
       {"action": "ADDED|REMOVED|MODIFIED", "from": {...}, "to": {...}, "fieldChanged": "quantity"|"unit"|...}
     ],
     "methodChanges": [
       {"action": "ADDED|REMOVED|MODIFIED", "step": 3, "from": "knead 5 min", "to": "knead 10 min"}
     ],
     "metadataChanges": [
       {"action": "MODIFIED", "field": "totalTimeMins", "from": 30, "to": 45}
     ],
     "tagChanges": [
       {"action": "ADDED|REMOVED|MODIFIED", "dimension": "flavourProfile", "from": ["sweet"], "to": ["sweet","sour"]}
     ]
   }
   ```
10. **Diff rules** (mirrors `VersionDifferTest` in LLD line 805):
    - **Ingredients**: matched by `(ingredientMappingKey, preparation)` pair (same as 01a's `@ValidIngredientList` uniqueness rule). Pairs in `requested` but not `parent` → ADDED. Pairs in `parent` but not `requested` → REMOVED. Pairs in both with any of `quantity`, `unit`, `displayName`, `optional`, `lineOrder`, `mappingConfidence`, `needsReview` differing → MODIFIED, with `fieldChanged` set to the **first** differing field (alphabetical). For multiple differing fields, emit one MODIFIED entry per `fieldChanged` value (so the UI can render per-field diffs).
    - **Method steps**: matched by `stepNumber`. Same logic as ingredients — ADDED / REMOVED / MODIFIED on `instruction` or `durationMinutes`. **Renumbering** (e.g., parent has steps 1,2,3,4 and request has 1,2,3 with the old step 3 deleted and the old step 4 renumbered to 3) is handled by matching on `stepNumber`; the LLD test enumerates "method renumbering" as a covered case.
    - **Metadata**: each scalar field of `RecipeMetadata` (servings, prepTimeMins, cookTimeMins, totalTimeMins, fridgeDays, freezerWeeks, packable, cuisine, equipmentRequired, mealTypes) compared. Emit one `MetadataChangeDto` per changed field with `from` / `to` as Jackson `JsonNode` (`ObjectMapper.valueToTree`).
    - **Tags**: each scalar field of `RecipeTags` (protein, cookingMethod, complexity, flavourProfile, dietaryFlags). Same as metadata.
    - **Empty diff**: returns `{ "ingredientChanges": [], "methodChanges": [], "metadataChanges": [], "tagChanges": [] }`. Detection: all four arrays empty.
11. **No `fromVersionId` / `toVersionId` in the persisted `change_diff`** — those are added by the controller mapping into `RecipeDiffDto` at read time (the persisted JSONB carries only the four arrays, since the row's own ids identify which two versions the diff is "from" / "to" — `parentVersionId` and `id` of the row). Mapping at read time:
    - `RecipeDiffDto.fromVersionId = recipeVersion.parentVersionId`
    - `RecipeDiffDto.toVersionId = recipeVersion.id`
    - Four arrays: copied from `change_diff` JSONB.

### `diff(fromVersionId, toVersionId)` endpoint

12. `GET /api/v1/recipes/{recipeId}/versions/{fromVersionId}/diff/{toVersionId}`. Returns `RecipeDiffDto` (200). Authorisation: caller must be able to read the recipe (same rule as 01a's get-by-id — open to any authenticated caller for v1, since system + user catalogues are both readable). Cookie-auth required.
13. **Resolution**:
    - Load `toVersionId` row. 404 `RecipeVersionNotFoundException` if missing.
    - Verify `toVersion.recipeId == {recipeId}` from the path. 404 if mismatched.
    - **Cross-branch check**: load `fromVersionId` row. 404 if missing. If `fromVersion.branchId != toVersion.branchId` → 422 `RecipeDiffCrossBranchException` (NEW; deferred to 01d for cross-branch support).
    - **Consecutive check**: if `toVersion.parentVersionId != fromVersionId` → 422 `RecipeDiffNotComputedException` (NEW; LLD-locked "key-value lookup, not recompute").
    - Build `RecipeDiffDto` from `toVersion.changeDiff` JSONB + the two version ids.
14. The diff is **immutable** for the lifetime of the row (the JSONB is written once and never updated per the LLD's append-only rule). `Cache-Control: public, max-age=31536000` is appropriate; 01c **does not** add caching headers (frontend's concern; document the immutability).

### Service interfaces — append-only to existing 01a/01b interfaces

15. Append to `RecipeQueryService`:
    ```java
    RecipeDiffDto diff(UUID recipeId, UUID fromVersionId, UUID toVersionId);
    ```
    The LLD's `diff(UUID fromVersionId, UUID toVersionId)` (line 534) is widened to take `recipeId` so the controller can validate the version-belongs-to-recipe invariant without a second repository call.
16. Append to `RecipeUpdateService`:
    ```java
    RecipeDto manualEdit(UUID recipeId, UpdateRecipeManualEditRequest request, UUID actorUserId);
    ```
    Verbatim from LLD line 557.

### Repository — additions to existing 01a repos (NOT new repos)

17. **No new repository class** — 01a's `RecipeVersionRepository` already has `findWithBodyById` (the parent-version load) and the `findCurrentVersionsForRecipes` batch method. 01c needs one new method appended:
    ```java
    @Query("""
        select v.id from RecipeVersion v
         where v.recipe.id = :recipeId and v.branch.id = :branchId
           and v.versionNumber = :currentVersion""")
    Optional<UUID> findCurrentVersionId(@Param("recipeId") UUID recipeId,
                                        @Param("branchId") UUID branchId,
                                        @Param("currentVersion") int currentVersion);
    ```
    Used by the manual-edit service to resolve the parent version's id from `Recipe.currentBranchId` + `Recipe.currentVersion` in one query. **Modifies** 01a's `RecipeVersionRepository` interface (additive only — sibling 01d/01e/etc. tickets append further methods later, no collision with this one).

### `created_by_actor` column width — already correct in 01a

18. **No action needed.** 01a shipped `recipe_versions.created_by_actor` and `recipe_branches.created_by_actor` as `varchar(64)` (parent-side patch during 01a verify caught the LLD's `varchar(32)` was too narrow for `"user:<UUID>"` = 41 chars). 01c can write `"user:<UUID>"` directly without any column-widening migration.

### Errors

19. New module exception subclasses extending the existing `RecipeException` from 01a:
    - `RecipeVersionNotFoundException` (404, `type = .../recipe-version-not-found`) — LLD line 666 names it.
    - `RecipeCatalogueViolationException` (422, `type = .../recipe-catalogue-violation`) — LLD line 668 names it; thrown when the user attempts manual edit on a SYSTEM recipe.
    - `NoChangesException` (400, `type = .../no-changes`) — NEW exception per invariant 7.6.
    - `RecipeDiffNotComputedException` (422, `type = .../recipe-diff-not-computed`) — NEW exception per invariant 13.
    - `RecipeDiffCrossBranchException` (422, `type = .../recipe-diff-cross-branch`) — NEW exception per invariant 13.
20. **Append five new `@ExceptionHandler` methods** to the existing `RecipeExceptionHandler` `@RestControllerAdvice` from 01a (which is already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`. `OptimisticLockingFailureException` continues handled by `GlobalExceptionHandler` (409). `MethodArgumentNotValidException` (validation) likewise.

## Database

**Zero migrations.** 01c is a pure code-path change — manual edit writes new rows into the existing `recipe_versions` + `recipe_ingredients` + `recipe_method_steps` + `recipe_metadata` + `recipe_tags` tables; diff reads the existing `change_diff` JSONB column. No new tables and no schema changes.

**No other migrations** — 01c uses 01a/01b's existing tables (`recipe_recipes`, `recipe_versions`, `recipe_branches`, `recipe_ingredients`, `recipe_method_steps`, `recipe_metadata`, `recipe_tags`). Manual edit is a pure code-path concern.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/recipe.yaml`

(File created by 01a, extended by 01b — append two new path-items below 01b's URL-import / branches blocks. Do NOT touch existing path-items.)

```yaml
recipeRoot:
  put:
    tags: [Recipes]
    operationId: manualEditRecipe
    summary: Manually edit a recipe; creates a new RecipeVersion (v2+) on the current branch.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/recipe.yaml#/UpdateRecipeManualEditRequest' }
    responses:
      '200':
        description: Recipe updated; response body carries the new current version's hydrated DTO.
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeDto' }
      '400': { description: Validation error / no-op edit, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Recipe not found / not owned, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedOptimisticVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: Manual edit attempted on a SYSTEM-catalogue recipe (use promote instead), content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
recipeVersionDiff:
  get:
    tags: [Recipes]
    operationId: getRecipeVersionDiff
    summary: Return the persisted change_diff between two consecutive versions on the same branch.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
      - in: path
        name: fromVersionId
        required: true
        schema: { type: string, format: uuid }
      - in: path
        name: toVersionId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: Structured diff between fromVersion and toVersion.
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeDiffDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Recipe / version not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: Versions are non-consecutive or cross-branch (recompute deferred to 01d+), content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/recipe.yaml`

(Append seven new schemas. Do NOT touch 01a/01b schemas.)

```yaml
ChangeAction:
  type: string
  enum: [ADDED, REMOVED, MODIFIED]
IngredientChangeDto:
  type: object
  required: [action]
  properties:
    action: { $ref: '#/ChangeAction' }
    from:
      type: object
      nullable: true
      description: The ingredient as it was in the parent version. Null on ADDED.
      properties:
        ingredientMappingKey: { type: string, maxLength: 160 }
        displayName: { type: string, maxLength: 160 }
        quantity: { type: number, format: double, nullable: true }
        unit: { type: string, maxLength: 16, nullable: true }
        preparation: { type: string, maxLength: 80, nullable: true }
        optional: { type: boolean }
        lineOrder: { type: integer }
    to:
      type: object
      nullable: true
      description: The ingredient as it is in the new version. Null on REMOVED.
      properties:
        ingredientMappingKey: { type: string, maxLength: 160 }
        displayName: { type: string, maxLength: 160 }
        quantity: { type: number, format: double, nullable: true }
        unit: { type: string, maxLength: 16, nullable: true }
        preparation: { type: string, maxLength: 80, nullable: true }
        optional: { type: boolean }
        lineOrder: { type: integer }
    fieldChanged:
      type: string
      maxLength: 32
      nullable: true
      description: Set on MODIFIED only; identifies which scalar field differs.
MethodChangeDto:
  type: object
  required: [action, step]
  properties:
    action: { $ref: '#/ChangeAction' }
    step: { type: integer, minimum: 1 }
    from:
      type: string
      nullable: true
    to:
      type: string
      nullable: true
MetadataChangeDto:
  type: object
  required: [action, field]
  properties:
    action: { $ref: '#/ChangeAction' }
    field: { type: string, maxLength: 64 }
    from: {}    # arbitrary JSON
    to: {}
TagChangeDto:
  type: object
  required: [action, dimension]
  properties:
    action: { $ref: '#/ChangeAction' }
    dimension: { type: string, maxLength: 32 }
    from: {}
    to: {}
RecipeDiffDto:
  type: object
  required: [fromVersionId, toVersionId, ingredientChanges, methodChanges, metadataChanges, tagChanges]
  properties:
    fromVersionId: { type: string, format: uuid }
    toVersionId: { type: string, format: uuid }
    ingredientChanges:
      type: array
      items: { $ref: '#/IngredientChangeDto' }
    methodChanges:
      type: array
      items: { $ref: '#/MethodChangeDto' }
    metadataChanges:
      type: array
      items: { $ref: '#/MetadataChangeDto' }
    tagChanges:
      type: array
      items: { $ref: '#/TagChangeDto' }
UpdateRecipeManualEditRequest:
  type: object
  required: [name, ingredients, method, metadata, changeReason, expectedOptimisticVersion]
  properties:
    name: { type: string, minLength: 1, maxLength: 160 }
    description: { type: string, maxLength: 2000, nullable: true }
    ingredients:
      type: array
      minItems: 1
      items: { $ref: '#/CreateIngredientRequest' }       # already declared in 01a
    method:
      type: array
      minItems: 1
      items: { $ref: '#/CreateMethodStepRequest' }       # already declared in 01a
    metadata: { $ref: '#/CreateRecipeMetadataRequest' }   # already declared in 01a
    tags:
      $ref: '#/CreateRecipeTagsRequest'                   # already declared in 01a (optional — null permitted via record nullability)
    changeReason: { type: string, minLength: 1, maxLength: 2000 }
    expectedOptimisticVersion: { type: integer, format: int64, minimum: 0 }
```

**Gotcha applied**: nullable fields throughout (`from`, `to`, `fieldChanged`, `description`) use **inline** `nullable: true` (not `$ref + nullable: true`). The `from` / `to` objects on `IngredientChangeDto` are **inlined** rather than `$ref`'d to a separate `IngredientSnapshotDto` so the `nullable: true` actually applies (per the swagger-parser gotcha).

**Gotcha applied**: `tags` field on `UpdateRecipeManualEditRequest` is a `$ref` without `nullable` — the underlying `CreateRecipeTagsRequest` from 01a is already nullable-tolerant (Jackson treats absent record components as null). If 01a declared `CreateRecipeTagsRequest` as a non-nullable record, 01c's `UpdateRecipeManualEditRequest` Java record uses `Optional<CreateRecipeTagsRequest>` or accepts null on a nullable record component. Document on the request class Javadoc.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# recipe` block in `paths:` (after 01b's URL-import / branches refs). Append two new path-item refs:

```yaml
  /api/v1/recipes/{recipeId}:
    $ref: 'paths/recipe.yaml#/recipeRoot'
  /api/v1/recipes/{recipeId}/versions/{fromVersionId}/diff/{toVersionId}:
    $ref: 'paths/recipe.yaml#/recipeVersionDiff'
```

**Note**: 01a's entry under `paths:` for `GET /api/v1/recipes/{recipeId}` MAY already be present as a different path-item. **Verify before writing**: if 01a has `/api/v1/recipes/{recipeId}` mapped to `paths/recipe.yaml#/recipeGetById` (or similar), 01c needs to **merge** the new PUT operation into the same path-item, not declare a parallel entry. The cleanest approach is to **rename** 01a's `recipeGetById` to `recipeRoot` in the `paths/recipe.yaml` and add the PUT under it. **The agent should check the merged 01a YAML and pick the right path** — both options are documented:
- Option A (preferred — single path-item per URL): rename 01a's entry; add PUT alongside GET in `paths/recipe.yaml`. One ref line in `openapi.yaml`.
- Option B (unique path keys): keep 01a's `recipeGetById`; add a new sibling `recipeManualEdit` keyed differently. swagger-parser tolerates this **only if** the path-item keys differ; if both refer to the same URL, OpenAPI rejects.

**Lock to Option A**. The agent merges PUT into 01a's existing path-item.

**Location**: under `components.schemas:`, append seven new schema refs in the existing `# recipe` block (alphabetical):

```yaml
    ChangeAction: { $ref: 'schemas/recipe.yaml#/ChangeAction' }
    IngredientChangeDto: { $ref: 'schemas/recipe.yaml#/IngredientChangeDto' }
    MetadataChangeDto: { $ref: 'schemas/recipe.yaml#/MetadataChangeDto' }
    MethodChangeDto: { $ref: 'schemas/recipe.yaml#/MethodChangeDto' }
    RecipeDiffDto: { $ref: 'schemas/recipe.yaml#/RecipeDiffDto' }
    TagChangeDto: { $ref: 'schemas/recipe.yaml#/TagChangeDto' }
    UpdateRecipeManualEditRequest: { $ref: 'schemas/recipe.yaml#/UpdateRecipeManualEditRequest' }
```

## Verbatim shape snippets

### `VersionDiffer` skeleton

```java
@Component
public class VersionDiffer {
  private final ObjectMapper objectMapper;

  public VersionDiffer(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

  public ObjectNode diff(RecipeVersion parent, NewVersionInput requested) {
    ObjectNode out = objectMapper.createObjectNode();
    out.set("ingredientChanges", diffIngredients(parent.getIngredients(), requested.ingredients()));
    out.set("methodChanges",     diffMethod(parent.getMethodSteps(),     requested.method()));
    out.set("metadataChanges",   diffMetadata(parent.getMetadata(),       requested.metadata()));
    out.set("tagChanges",        diffTags(parent.getTags(),                requested.tags()));
    return out;
  }

  /** Empty when all four arrays are empty. */
  public boolean isEmpty(JsonNode diff) {
    return diff.path("ingredientChanges").isEmpty()
        && diff.path("methodChanges").isEmpty()
        && diff.path("metadataChanges").isEmpty()
        && diff.path("tagChanges").isEmpty();
  }
  // ... per-section helpers ...
}
```

### Service-impl — manualEdit skeleton

```java
@Transactional
public RecipeDto manualEdit(UUID recipeId, UpdateRecipeManualEditRequest request, UUID actorUserId) {
  Recipe recipe = recipeRepository.findByIdAndDeletedAtIsNull(recipeId)
      .orElseThrow(RecipeNotFoundException::new);

  // Catalogue gate
  if (recipe.getCatalogue() == Catalogue.SYSTEM) {
    throw new RecipeCatalogueViolationException("manual edit on SYSTEM recipe");
  }
  // Ownership gate (USER catalogue)
  if (!recipe.getUserId().equals(actorUserId)) {
    throw new RecipeNotFoundException();          // do not leak existence
  }
  // Stale version gate
  if (recipe.getOptimisticVersion() != request.expectedOptimisticVersion()) {
    throw new OptimisticLockingFailureException("stale expectedOptimisticVersion");
  }

  UUID currentVersionId = recipeVersionRepository
      .findCurrentVersionId(recipe.getId(), recipe.getCurrentBranchId(), recipe.getCurrentVersion())
      .orElseThrow(() -> new IllegalStateException("recipe inconsistent: no current version"));
  RecipeVersion parent = recipeVersionRepository.findWithBodyById(currentVersionId)
      .orElseThrow(() -> new IllegalStateException("recipe inconsistent: current version missing"));
  parent.getIngredients().size();  parent.getMethodSteps().size();   // force lazy-load

  NewVersionInput requested = mapper.fromRequest(request);
  ObjectNode changeDiff = versionDiffer.diff(parent, requested);
  if (versionDiffer.isEmpty(changeDiff)) {
    throw new NoChangesException("manual edit produced no diff");
  }

  RecipeBranch currentBranch = recipeBranchRepository.findById(recipe.getCurrentBranchId()).orElseThrow();
  int newVersionNumber = parent.getVersionNumber() + 1;

  RecipeVersion newVersion = RecipeVersion.builder()
      .id(UUID.randomUUID()).recipeId(recipe.getId()).branchId(currentBranch.getId())
      .versionNumber(newVersionNumber).parentVersionId(parent.getId())
      .changeDiff(changeDiff).changeReason(request.changeReason())
      .trigger(VersionTrigger.MANUAL_EDIT)
      .characterFingerprint(null).nutritionPerServing(null)
      .embeddingStatus("pending")
      .createdByActor("user:" + actorUserId)
      .adapterTraceId(null)
      .createdAt(Instant.now())
      .build();

  // Insert children for the new version
  newVersion.replaceIngredients(buildIngredients(requested.ingredients(), newVersion));
  newVersion.replaceMethodSteps(buildMethodSteps(requested.method(), newVersion));
  newVersion.setMetadata(buildMetadata(requested.metadata(), newVersion));
  newVersion.setTags(buildTags(requested.tags(), newVersion));
  recipeVersionRepository.saveAndFlush(newVersion);                  // gotcha: flush so children get version-id FK

  // Pointer updates
  recipe.setCurrentVersion(newVersionNumber);
  recipe.setName(request.name());
  recipe.setDescription(request.description());
  recipeRepository.saveAndFlush(recipe);                             // bumps @Version
  currentBranch.setCurrentVersion(newVersionNumber);
  recipeBranchRepository.saveAndFlush(currentBranch);

  publisher.publishEvent(new RecipeVersionCreatedEvent(
      newVersion.getId(), recipe.getId(), currentBranch.getId(), newVersionNumber,
      traceIdFromMdcOrRandom(), Instant.now()));
  publisher.publishEvent(new RecipeUpdatedEvent(
      recipe.getId(), currentBranch.getId(), newVersion.getId(), newVersionNumber,
      VersionTrigger.MANUAL_EDIT, traceIdFromMdcOrRandom(), Instant.now()));
  return recipeMapper.toDto(recipe, newVersion, List.of(currentBranch));
}
```

### Diff endpoint skeleton

```java
@Transactional(readOnly = true)
public RecipeDiffDto diff(UUID recipeId, UUID fromVersionId, UUID toVersionId) {
  RecipeVersion to = recipeVersionRepository.findById(toVersionId)
      .orElseThrow(RecipeVersionNotFoundException::new);
  if (!to.getRecipeId().equals(recipeId)) throw new RecipeVersionNotFoundException();

  RecipeVersion from = recipeVersionRepository.findById(fromVersionId)
      .orElseThrow(RecipeVersionNotFoundException::new);
  if (!from.getBranchId().equals(to.getBranchId())) {
    throw new RecipeDiffCrossBranchException();
  }
  if (!fromVersionId.equals(to.getParentVersionId())) {
    throw new RecipeDiffNotComputedException();
  }
  return diffMapper.fromJsonNode(fromVersionId, toVersionId, to.getChangeDiff());
}
```

## Edge-case checklist

- [ ] `PUT /recipes/{recipeId}` happy path (USER recipe, owner, valid expectedOptimisticVersion, real changes) → 200; new `RecipeVersion` row exists with `versionNumber = parent + 1`, `parentVersionId = parent.id`, `branchId = currentBranchId`, `trigger = MANUAL_EDIT`, `changeDiff` non-empty, `createdByActor = "user:<uuid>"`; `Recipe.currentVersion` bumped; `RecipeBranch.currentVersion` bumped; **old version's body rows untouched** (still queryable)
- [ ] `PUT /recipes/{recipeId}` for a SYSTEM-catalogue recipe → 422 `recipe-catalogue-violation`
- [ ] `PUT /recipes/{recipeId}` for a USER-catalogue recipe owned by a different user → 404 (not 403; don't leak existence)
- [ ] `PUT /recipes/{recipeId}` with stale `expectedOptimisticVersion` → 409 `concurrent-update`
- [ ] `PUT /recipes/{recipeId}` with no actual changes (request body identical to current version) → 400 `no-changes`
- [ ] `PUT /recipes/{recipeId}` validation: empty `name` → 400; empty `ingredients` → 400; empty `method` → 400; missing `changeReason` → 400; oversize `description` (> 2000) → 400
- [ ] After a successful edit, `GET /recipes/{recipeId}` returns the v2 body — ingredient changes / method changes / metadata changes / tag changes all reflected
- [ ] After a successful edit, `GET /recipes/{recipeId}/versions/{v1.id}/diff/{v2.id}` returns 200 with a non-empty `RecipeDiffDto` matching the persisted `change_diff`
- [ ] `GET /diff/...` for non-consecutive versions on the same branch (e.g., v1→v3 after a v3 edit) → 422 `recipe-diff-not-computed`
- [ ] `GET /diff/...` for versions on different branches → 422 `recipe-diff-cross-branch` (this is unreachable until 01d, but wire the check now)
- [ ] `GET /diff/...` with a non-existent `fromVersionId` or `toVersionId` → 404
- [ ] `GET /diff/...` with `toVersionId.recipeId != {recipeId}` → 404
- [ ] `VersionDiffer` unit tests (per LLD line 805): empty diff for identical bodies; ingredient ADDED/REMOVED/MODIFIED; method renumbering; metadata field changes; tag-array changes
- [ ] `RecipeUpdatedEvent` published `AFTER_COMMIT` exactly once on edit; `RecipeVersionCreatedEvent` likewise (separate event)
- [ ] No event published when the manual edit is a no-op (caught by the 400 above before event emission)
- [ ] `recipe_versions` is not UPDATEd by 01c — verified by Hibernate stats or by an IT that asserts the parent version's body rows still exist with the same content after the edit
- [ ] `created_by_actor` column accepts the 41-char `"user:<UUID>"` value (already at varchar(64) in 01a; no widening needed)
- [ ] `RecipeBoundaryTest` (from 01a) still passes — no new repos in this ticket
- [ ] `RecipeExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after the five new methods are appended
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT) — including the inline `nullable` on diff `from`/`to` snapshots

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/recipe/api/dto/UpdateRecipeManualEditRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RecipeDiffDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/IngredientChangeDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/MethodChangeDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/MetadataChangeDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/TagChangeDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/ChangeAction.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeDiffMapper.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/VersionDiffer.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/NewVersionInput.java
NEW   src/main/java/com/example/mealprep/recipe/event/RecipeUpdatedEvent.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeVersionNotFoundException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeCatalogueViolationException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/NoChangesException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeDiffNotComputedException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeDiffCrossBranchException.java

MOD   src/main/java/com/example/mealprep/recipe/api/controller/RecipesController.java                  (add PUT /{recipeId} method; KEEP existing GET / POST / DELETE handlers)
NEW   src/main/java/com/example/mealprep/recipe/api/controller/RecipeVersionsController.java          (new — only the diff endpoint in 01c; future tickets layer version listing / revert)
MOD   src/main/java/com/example/mealprep/recipe/api/RecipeExceptionHandler.java                        (append 5 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeQueryService.java                 (append diff(recipeId, fromVersionId, toVersionId))
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeUpdateService.java                (append manualEdit)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java        (implement manualEdit + diff)
MOD   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeVersionRepository.java         (append findCurrentVersionId)

MOD   src/main/resources/openapi/paths/recipe.yaml      (merge PUT into 01a's `recipeRoot` path-item; append 1 new `recipeVersionDiff` path-item)
MOD   src/main/resources/openapi/schemas/recipe.yaml    (append 7 new schemas: ChangeAction, IngredientChangeDto, MethodChangeDto, MetadataChangeDto, TagChangeDto, RecipeDiffDto, UpdateRecipeManualEditRequest)
MOD   src/main/resources/openapi/openapi.yaml           (1 line under paths: in the `# recipe` block — diff path; PUT is added in-place to the existing `recipeRoot` ref so no second top-level entry is needed; 7 lines under components.schemas: in the `# recipe` block)

NEW   src/test/java/com/example/mealprep/recipe/RecipeManualEditServiceTest.java
NEW   src/test/java/com/example/mealprep/recipe/RecipeManualEditFlowIT.java
NEW   src/test/java/com/example/mealprep/recipe/VersionDifferTest.java
NEW   src/test/java/com/example/mealprep/recipe/RecipeVersionDiffFlowIT.java
MOD   src/test/java/com/example/mealprep/recipe/testdata/RecipeTestData.java                          (append manual-edit-request builder fixture)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-3 tickets running in parallel must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — module exceptions go in the existing `RecipeExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module boundary rule lives in the existing `RecipeBoundaryTest`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`, migrations, entities — none touched.
- 01a's `Recipe`, `RecipeBranch`, `RecipeVersion`, `RecipeIngredient`, `RecipeMethodStep`, `RecipeMetadata`, `RecipeTags` entities — used as-is; **no new fields**, **no new annotations**.
- `RecipeBoundaryTest` is unchanged (no new repos; the existing repo gains one method but lives in the same package).
- 01b's `RecipeImportsController` and `RecipeImport` entity — independent concern.

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe`, `RecipeBranch`, `RecipeVersion`, all body entities, `RecipeRepository`, `RecipeVersionRepository`, `RecipeBranchRepository`, `RecipeQueryService`, `RecipeUpdateService`, `RecipeMapper` (with the three-arg `toDto(Recipe, RecipeVersion, List<RecipeBranch>)`), `RecipeExceptionHandler`, `RecipeBoundaryTest`, `RecipeException`, `RecipeNotFoundException`, `Catalogue` enum, `VersionTrigger` enum, `RecipeVersionCreatedEvent`, `CreateIngredientRequest`, `CreateMethodStepRequest`, `CreateRecipeMetadataRequest`, `CreateRecipeTagsRequest`, `created_by_actor` column wide enough for 41 chars OR widen-able by 01c's conditional migration.
- **Hard dependency**: `recipe-01b` (merged) — extends the same two service interfaces; the per-module YAML / advice append-only convention; reuses `RecipeImport` for context (not modified).
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Sibling tickets running in parallel** (Wave 2 round 3): `household-01c`, `nutrition-01c`, `provisions-01c`. None should touch any recipe file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# recipe` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `RecipeExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after the five new methods are appended
- [ ] `saveAndFlush` used on `RecipeVersion` after building children so child INSERTs see the parent's id (gotcha: pure `save` plus same-tx child inserts can produce null `version_id` on the child rows depending on Hibernate's flush ordering)
- [ ] `recipe_versions` rows are append-only — verified by Hibernate stats or by an IT that asserts no UPDATE statements on `recipe_versions` during a manual edit
- [ ] `VersionDifferTest` covers all five LLD-listed cases (line 805)
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (NOT `$ref + nullable: true`) on the diff-snapshot fields (`from`, `to`, `fieldChanged`, `description`)
- [ ] Diff endpoint is a key-value lookup of the persisted JSONB — verified by an IT that asserts the same `change_diff` value on the `recipe_versions` row matches the diff endpoint's response (no recomputation)
- [ ] `created_by_actor` column accepts `"user:" + UUID` (41 chars) — invariant 18 verified
- [ ] No regression on existing tests, including 01a's create + get-by-id ITs and 01b's URL-import / branches ITs
- [ ] No N+1 on the manual-edit hot path — verified via Hibernate statistics or single-tx statement-count assertion in IT (one SELECT on Recipe, one on current version + body, one on branch; INSERTs for new version + children + UPDATEs for Recipe and Branch)
- [ ] No pom.xml dependency adds (uses existing JPA / MapStruct / Jackson / `hypersistence-utils-hibernate-63`)

## What's NOT in scope

- Branch creation (`CreateBranchRequest`, non-main rows, divergence-score recomputation, `CharacterFingerprintDto` population on branch) → **recipe-01d**
- `revertToVersion` REST endpoint (`POST /versions/revert`) → **recipe-01d**
- Substitutions (`RecipeSubstitution` + endpoints + substitution-promotion as a version) → **recipe-01e**
- `RecipeWriteApi` SPI for adaptation pipeline + `RecipeAdaptedEvent` + `saveAdaptedVersion` → **recipe-01f**
- `NutritionCalculationService` listener on `RecipeUpdatedEvent` (recalculates `nutrition_per_serving` on the new version) → **nutrition-01f**
- pgvector + embedding column population on the new version → **recipe-01h**
- `getIngredientMappingKeys`, `getFingerprint`, `getNutritionPerServing`, `getEmbedding` cross-module helpers → **recipe-01j**
- AI tag inference on edit → **recipe-01k**
- Cross-version diff recompute (i.e., diff between non-consecutive versions) — locked-out in 01c (see invariant 13); future ticket
- Cross-branch diff — locked-out in 01c; ships with 01d
- `DeduplicationFingerprintHasher` on edit (re-running dedup against other recipes) — deferred to 01g per 01a's deferral list

Squash-merge with: `feat(recipe): 01c — manual edit creates v2+ + VersionDiffer + diff endpoint + RecipeUpdatedEvent`
