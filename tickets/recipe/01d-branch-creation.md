# Ticket: recipe — 01d Branch Creation + Divergence Score + CharacterFingerprint

## Summary

Layer the **user-facing branch creation** flow on top of the 01a/01b/01c recipe aggregate: the `RecipeBranchesController` endpoints under `/api/v1/recipes/{recipeId}/branches` per [LLD §REST line 649-651](../../lld/recipe.md) (`GET /branches` list, `POST /branches` create, `GET /branches/{branchId}` fetch), the `CreateBranchRequest` body shape from [LLD lines 406-411](../../lld/recipe.md), the `DivergenceScoreCalculator` `@Component` that computes the 0..1 score from `CharacterFingerprintDto` overlap, the `CharacterFingerprintDto` (already declared in OpenAPI from 01a's earlier hydration; new in 01d as a populated Java record) populated on the new branch's v1 row, and the `revertToVersion` endpoint at `POST /api/v1/recipes/{recipeId}/versions/revert` per [LLD §REST line 647](../../lld/recipe.md) (deferred from 01c per the 01c "what's NOT in scope" list — "alongside branches; revert is conceptually a branch-pointer move"). Per [`lld/recipe.md`](../../lld/recipe.md) §V20260601120200 (lines 137-153), §`CharacterFingerprintDto` (lines 309-312), §`RecipeBranchDto` (lines 321-324), §`CreateBranchRequest` (lines 406-411), §`RecipeBranchRepository` (lines 491-494), §Service Interfaces (`getBranches`, `getBranch` lines 528-529; `revertToVersion` line 568), §`updateCharacterFingerprint` / `updateBranchDivergence` (lines 597-598), §REST table lines 647-651, §Event `RecipeVersionCreatedEvent` (already published in 01a/01c for v1 / manual-edit; same event class).

**Defers** (still out of scope after 01d):

- Substitutions (`RecipeSubstitution` + endpoints + substitution-promotion as a version) → **recipe-01e**
- `RecipeWriteApi` SPI + adaptation events (`RecipeAdaptedEvent`, `RecipeEvolvedEvent`) → **recipe-01f**
- Promotion / demotion / archive / `ArchiveEligibilityScanner` → **recipe-01g**
- pgvector + embedding column population (async listener on `RecipeVersionCreatedEvent`) → **recipe-01h**
- Search → **recipe-01i**
- Cross-module helpers `getIngredientMappingKeys`, `getFingerprint`, `getNutritionPerServing`, `getEmbedding` → **recipe-01j** (01d adds `getFingerprint` as an internal helper but does **not** expose it via REST per the cross-module-helper deferral list; see invariant 17 below)
- AI tag inference → **recipe-01k**
- Cross-branch diff (the `RecipeDiffCrossBranchException` from 01c can now happen — 01d lets users create branches, so v1 → v2 on branch-A vs v1 → v3 on branch-B is reachable; the diff endpoint **still** returns 422 per 01c's lock-out; cross-branch merge semantics remain a 01f+ concern)
- AI-driven `CharacterFingerprintDto` derivation — 01d **requires the request body to supply `fingerprintOverride`** OR derives a minimal fingerprint from the v1 body without AI (see "LLD divergence note: fingerprint derivation" below)
- `RecipeAdaptedEvent` / pipeline-driven branch creation — 01d's flow is **user-facing only**; pipeline calls land via `RecipeWriteApi.saveAdaptedBranch` in 01f

01d unblocks the **multi-variant recipe** UX: a user can fork "Mum's lasagne" into "Mum's lasagne — gluten-free variant" by creating a branch off v3 with modified ingredients/method, without losing the main-branch history. It also unblocks **revert** — the user can wind a branch back to an earlier version (writes a new version on the same branch with `trigger = REVERT` and the old body cloned).

**LLD divergence note** — **fingerprint derivation**: LLD line 309-312 declares `CharacterFingerprintDto { definingIngredients, definingTechniques, textureEssentials, flavourAnchors, complexityTier, cuisineAnchor }` and line 597 says `updateCharacterFingerprint` is owned by the pipeline (i.e. AI-derived). LLD line 113 ("per-version snapshot, refreshed only on branch creation") commits to fingerprint-on-branch-creation as a write-time event. **01d's CreateBranchRequest carries `fingerprintOverride` (LLD line 411)** — when supplied, the row's `character_fingerprint` is set to that value verbatim. **When NOT supplied**, 01d **derives a minimal fingerprint from the v1 body**:
- `definingIngredients`: top-3 ingredient `displayName`s by `lineOrder` (the recipe's "headline" ingredients are typically listed first).
- `definingTechniques`: empty list (AI-derivable; deferred to 01k).
- `textureEssentials`: empty list (AI-derivable).
- `flavourAnchors`: copied from the new version's `RecipeTags.flavourProfile` (already user-supplied).
- `complexityTier`: copied from `RecipeTags.complexity` (already user-supplied).
- `cuisineAnchor`: copied from `RecipeMetadata.cuisine` (already user-supplied).
- **Worth user review**: the derived fingerprint is a strict subset of the LLD-intended AI-rich version; the divergence score (invariant 14 below) will be artificially close to 0 for branches created without override (because two branches with the same flavour + complexity + cuisine but different ingredients/method are scored as similar). The pipeline-driven path in 01f will overwrite the derived fingerprint via `updateCharacterFingerprint` once AI inference lands.

**LLD divergence note** — **divergence score formula**: LLD line 153 indexes `divergence_score > 0.7` for the "promote to standalone" prompt UI, but [line 838 of `lld/recipe.md`](../../lld/recipe.md) ("Branch divergence formula. Catalogue stores `divergence_score` and exposes `updateBranchDivergence`; the formula and threshold for 'promote to standalone' prompts are pipeline LLD concerns") **explicitly defers the formula to the pipeline LLD**. 01d implements a **provisional formula** so the `divergence_score` column is populated meaningfully on branch creation (zeros everywhere is misleading):
- **Compute as fingerprint-jaccard-distance** between the parent branch's current-version fingerprint and the new branch's v1 fingerprint. For each of the 4 list-fields (`definingIngredients`, `definingTechniques`, `textureEssentials`, `flavourAnchors`), compute `1 - |intersection| / |union|`. For the 2 scalar fields (`complexityTier`, `cuisineAnchor`), `0` if equal, `1` if different.
- **Mean of the 6 component scores → 0..1**. Round to 3 decimal places to match the `numeric(4,3)` column.
- **Edge cases**: both fingerprints empty → `0.0` (same); one empty, one populated → `1.0` (max divergence).
- **Future-proof**: when the pipeline LLD ships the canonical formula, `DivergenceScoreCalculator` is the single point of replacement; existing rows can be recomputed via `updateBranchDivergence` (LLD line 598).
- **Worth user review**: jaccard-distance is a common similarity baseline but doesn't weight any field (e.g. defining-ingredients changing should plausibly matter more than flavour-anchors changing). 01d ships the unweighted variant for simplicity.

**LLD divergence note** — **`revertToVersion` shape**: LLD §REST line 647 specifies `POST /api/v1/recipes/{recipeId}/versions/revert` with body `{ branchId, versionNumber }`. 01d follows verbatim. **Behaviour**: writes a new version on the target branch with `trigger = REVERT` whose **body is cloned from the target version** (ingredients/method/metadata/tags all duplicated as new child rows; `version_number` continues monotonic per `(recipe_id, branch_id)`). The change-diff is computed via 01c's `VersionDiffer.diff(currentVersion, targetVersion)` — note **inverted direction**: the diff shows "what changed from the current version's body to the reverted body." `change_reason` is auto-populated to `"Reverted to version " + targetVersion.versionNumber`. Re-uses 01c's `VersionDiffer`, `recipe_versions` insert path, and `RecipeBranch.currentVersion` bump path — **no new repository methods needed**.

## Behavioural spec

### `getBranches` (list) — `GET /api/v1/recipes/{recipeId}/branches`

1. Authenticated. Returns `List<RecipeBranchDto>` for the recipe, sorted `created_at ASC` (oldest-first: main always first). 404 `RecipeNotFoundException` if the recipe doesn't exist.
2. **Authorisation**: same rule as 01a's get-by-id — open to any authenticated caller for v1 (both SYSTEM and USER catalogue recipes are readable).
3. Repository: `findAllByRecipeId(UUID)` (existing from 01a, line 492). Sort by `created_at ASC` is added — verify 01a's method actually sorts or just returns unsorted; if unsorted, **01d appends a new method** `findAllByRecipeIdOrderByCreatedAtAsc(UUID)`. **Verify 01a's actual repo before assuming**.

### `getBranch` (single) — `GET /api/v1/recipes/{recipeId}/branches/{branchId}`

4. Authenticated. Returns `RecipeBranchDto`. 404 `RecipeBranchNotFoundException` (NEW exception) if missing. 404 if `branch.recipeId != {recipeId}` from the path (don't leak; treat as missing).

### `createBranch` flow — `POST /api/v1/recipes/{recipeId}/branches`

5. Authenticated. Server resolves `actorUserId` via `CurrentUserResolver`. Body: `CreateBranchRequest` per [LLD lines 406-411](../../lld/recipe.md):
   ```java
   public record CreateBranchRequest(
       @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9-]+") String name,
       @Size(max = 120) String label,
       @NotBlank String reason,
       @NotNull UUID branchPointVersionId,
       @NotNull @Valid CreateRecipeBodyRequest body,
       @Valid CharacterFingerprintDto fingerprintOverride          // nullable
   ) {}
   ```
   `CreateRecipeBodyRequest` is a NEW DTO bundling `(List<CreateIngredientRequest>, List<CreateMethodStepRequest>, CreateRecipeMetadataRequest, CreateRecipeTagsRequest)` — same shapes as 01a's `CreateRecipeRequest` minus `name` and `description` (the branch reuses the recipe's name/description; branch-specific differentiation is via `label`).

6. **Authorisation**: caller must own the recipe (`recipe.userId == actorUserId`) for `catalogue = USER` recipes — else 404 `RecipeNotFoundException` (same rule as 01c's manual edit). For `catalogue = SYSTEM`: 422 `RecipeCatalogueViolationException` (the user must promote first; promote is deferred to 01g — same gate as 01c).

7. **Validation checks** (in order):
   1. Recipe exists and not soft-deleted → 404 `RecipeNotFoundException` if not.
   2. `branchPointVersionId` exists, has `recipeId == {recipeId}` → 422 `RecipeBranchPointInvalidException` (NEW). LLD line 817 of the test catalogue calls out "foreign `branchPointVersionId` 422."
   3. Branch `name` is unique within the recipe (DB `UNIQUE (recipe_id, name)` from 01a's migration; pre-check via `findByRecipeIdAndName` to surface 409 `RecipeBranchNameConflictException` (NEW) rather than `DataIntegrityViolationException` (clearer error for the user).
   4. Branch `name` is NOT `"main"` (reserved; LLD line 142 lists `'main'` as the auto-generated branch name from 01a). → 422 `RecipeBranchNameReservedException` (NEW; or fold into `RecipeBranchPointInvalidException`'s detail message — **lock as a separate exception** for clarity).

8. Single `@Transactional` write:
   1. Insert `RecipeBranch` row:
      - `id = UUID.randomUUID()`
      - `recipeId = recipe.id`
      - `parentBranchId = branchPointVersion.branchId` (the branch the user forked FROM — usually `main`).
      - `branchPointVersionId = request.branchPointVersionId()`
      - `name = request.name()`
      - `label = request.label()`
      - `reason = request.reason()`
      - `currentVersion = 1` (the new branch's first version).
      - `divergenceScore = <computed by DivergenceScoreCalculator>` — see invariant 14.
      - `createdAt = Instant.now()` (auto via `@CreationTimestamp`).
      - `createdByActor = "user:" + actorUserId` (varchar 64 wide enough per 01a's column-width adjustment).
      - `adapterTraceId = null` (manual flow; not pipeline).
   2. Insert new `RecipeVersion` row for the branch's v1:
      - `id = UUID.randomUUID()`
      - `recipeId = recipe.id`
      - `branchId = <new branch's id>`
      - `versionNumber = 1` (each branch starts at v1; LLD line 107 — "monotonic per `(recipe_id, branch_id)`").
      - `parentVersionId = branchPointVersion.id` (cross-branch parent link; LLD line 108 — `parent_version_id uuid REFERENCES recipe_versions(id), null for v1 of any branch` — **wait, the LLD says "null for v1 of any branch"**; **lock divergence note**: 01d sets `parentVersionId = branchPointVersionId` because semantically the new branch's v1 IS derived from the branch-point version. LLD line 108 is wrong in implication — the FK exists, and clearing it on cross-branch v1 loses the genealogy. **01d sets the FK**; the LLD's "null" rule applies only to main's v1 (which has no parent). Document on the migration's leading comment.
      - `changeDiff = <computed via VersionDiffer.diff(branchPointVersion, requestedBody)>`
      - `changeReason = request.reason()`
      - `trigger = BRANCH_CREATION` (LLD line 111-112 enum value).
      - `characterFingerprint = <DERIVED or override>` per invariant 11 below.
      - `nutritionPerServing = null` (recalculated by 01f's listener).
      - `embeddingStatus = "pending"` (01h's listener picks up).
      - `createdByActor = "user:" + actorUserId`.
      - `adapterTraceId = null`.
   3. Insert child rows for the new version (`RecipeIngredient`, `RecipeMethodStep`, `RecipeMetadata`, `RecipeTags`) — same insert pattern as 01a's `create` and 01c's `manualEdit` (re-use the helper extracted in 01c).
   4. **Do NOT mutate `Recipe.currentVersion` / `Recipe.currentBranchId`** — branch creation does NOT switch the recipe's current branch (`main` stays default). The user can `POST /api/v1/recipes/{recipeId}/branches/{branchId}/checkout` to switch; **that endpoint is NOT in 01d** — deferred to 01g (branch checkout / promote / demote land together).
   5. Return 201 + `RecipeBranchDto`. `Location: /api/v1/recipes/{recipeId}/branches/{branchId}`.

9. **Events**:
   - Publish `RecipeVersionCreatedEvent(UUID versionId, UUID recipeId, UUID branchId, int versionNumber, UUID traceId, Instant occurredAt)` `AFTER_COMMIT` (existing event from 01a; same emit pattern).
   - Publish new `RecipeBranchCreatedEvent(UUID recipeId, UUID branchId, UUID parentBranchId, UUID branchPointVersionId, BigDecimal divergenceScore, UUID traceId, Instant occurredAt)` `AFTER_COMMIT`. **LLD divergence**: LLD §Events section doesn't declare this event explicitly. 01d adds it because:
     - Symmetry with `RecipeUpdatedEvent` from 01c (manual-edit emits a "something happened to this recipe" notification).
     - The 01f planner-bundle invalidation needs to know when a new branch landed (the planner's per-recipe cache is keyed on `(recipeId, branchId, currentVersion)` — a new branch is a new cache key).
     - Cost is one record class; no listeners in 01d.
   - **Do NOT publish `RecipeUpdatedEvent`** — that event is reserved for manual-edit-on-the-current-branch flows (per 01c invariant 8). Branch creation is a distinct semantic event.

### `CharacterFingerprintDto` — populated on every new branch's v1

10. `CharacterFingerprintDto` is **new in 01d** as a populated record (the OpenAPI shape was declared in 01a for the `RecipeVersionDto` hydration, but the field was always null in 01a/01b/01c — no creation path wrote to it). 01d ships the Java record `com.example.mealprep.recipe.api.dto.CharacterFingerprintDto` and the JSONB column population.

11. **Verbatim from [LLD line 309](../../lld/recipe.md)**:
    ```java
    public record CharacterFingerprintDto(
        List<String> definingIngredients,         // up to 5
        List<String> definingTechniques,          // up to 5; empty in 01d (AI-derived; deferred to 01k)
        List<String> textureEssentials,           // up to 5; empty in 01d
        List<String> flavourAnchors,              // up to 5
        Complexity complexityTier,
        String cuisineAnchor                       // nullable
    ) {}
    ```
    Persisted to `recipe_versions.character_fingerprint` via `@Type(JsonType.class)` JSONB. The column already exists on the table (created in 01a's `V20260601800100__recipe_create_recipe_versions.sql`).

12. **Derivation when `fingerprintOverride` is null**: see "LLD divergence note: fingerprint derivation" above.

### `DivergenceScoreCalculator` — `domain/service/internal/`

13. `DivergenceScoreCalculator` is a `@Component` in `recipe/domain/service/internal/`. Single method `BigDecimal compute(CharacterFingerprintDto parent, CharacterFingerprintDto child)`. Implements the provisional jaccard-mean formula per "LLD divergence note: divergence score formula" above.

14. **Implementation**:
    ```java
    public BigDecimal compute(CharacterFingerprintDto parent, CharacterFingerprintDto child) {
      if (parent == null && child == null) return BigDecimal.ZERO;
      if (parent == null || child == null) return BigDecimal.ONE;
      double sum = 0.0;
      sum += jaccardDistance(parent.definingIngredients(), child.definingIngredients());
      sum += jaccardDistance(parent.definingTechniques(),  child.definingTechniques());
      sum += jaccardDistance(parent.textureEssentials(),   child.textureEssentials());
      sum += jaccardDistance(parent.flavourAnchors(),      child.flavourAnchors());
      sum += parent.complexityTier() == child.complexityTier() ? 0.0 : 1.0;
      sum += Objects.equals(parent.cuisineAnchor(), child.cuisineAnchor()) ? 0.0 : 1.0;
      return BigDecimal.valueOf(sum / 6.0).setScale(3, RoundingMode.HALF_UP);
    }

    private static double jaccardDistance(List<String> a, List<String> b) {
      Set<String> aSet = a == null ? Set.of() : new HashSet<>(a);
      Set<String> bSet = b == null ? Set.of() : new HashSet<>(b);
      if (aSet.isEmpty() && bSet.isEmpty()) return 0.0;
      Set<String> union = new HashSet<>(aSet); union.addAll(bSet);
      Set<String> intersection = new HashSet<>(aSet); intersection.retainAll(bSet);
      return 1.0 - ((double) intersection.size() / union.size());
    }
    ```

### `revertToVersion` flow — `POST /api/v1/recipes/{recipeId}/versions/revert`

15. Authenticated. Body: `RevertToVersionRequest { @NotNull UUID branchId, @Min(1) int versionNumber, long expectedRecipeOptimisticVersion }`. Server resolves `actorUserId`.

16. **Validation**:
    - Recipe exists / not deleted / caller owns (USER catalogue) → 404 / 422 (same gates as `createBranch`).
    - Stale `expectedRecipeOptimisticVersion` → 409 via `OptimisticLockingFailureException`.
    - Branch exists with `branchId` AND `recipeId` matching path → 404 `RecipeBranchNotFoundException`.
    - Target `versionNumber` exists on that branch → 404 `RecipeVersionNotFoundException` (existing from 01c).
    - `versionNumber == branch.currentVersion` (no-op revert) → 400 `NoChangesException` (existing from 01c).
17. Single `@Transactional` write — writes a NEW version on the branch with:
    - `parentVersionId = currentVersion.id` (the version we're moving away from; the new version's parent is whatever the head used to be).
    - `versionNumber = currentVersion.versionNumber + 1`.
    - `branchId = request.branchId()`.
    - `trigger = REVERT` (LLD line 111-112 enum value).
    - `changeDiff = VersionDiffer.diff(currentVersion, targetVersion)` (inverted from 01c's manual-edit direction; documents "what's being reverted").
    - `changeReason = "Reverted to version " + targetVersion.versionNumber`.
    - Child rows: cloned from `targetVersion`'s body (ingredients, method, metadata, tags duplicated as new rows under the new version's id).
    - `characterFingerprint = currentVersion.characterFingerprint` (revert doesn't refresh fingerprint per LLD line 113 — fingerprint refreshes only on branch creation).
    - `embeddingStatus = "pending"` (01h listener picks up).
    - `createdByActor = "user:" + actorUserId`.
    - `Recipe.currentVersion` / `RecipeBranch.currentVersion` bumped to the new version's number.
18. Return 200 + `RecipeVersionDto` (the new revert-version row, hydrated). Publish `RecipeVersionCreatedEvent` AFTER_COMMIT. **Also publish `RecipeUpdatedEvent`** (`trigger = REVERT`) — symmetric with manual-edit; the planner / nutrition recalc listener treats revert the same as manual-edit. **LLD divergence**: LLD `RecipeUpdatedEvent` line 695 doesn't specify the trigger field's exact enum coverage; 01d carries `VersionTrigger` so listeners can switch on it.

### Service interfaces — append-only

19. Append to `RecipeQueryService` (already declares `getById`, `getByIds`, `listUserCatalogue`, etc., plus 01c's `diff`):
    ```java
    List<RecipeBranchDto> getBranches(UUID recipeId);
    Optional<RecipeBranchDto> getBranch(UUID branchId);
    Optional<CharacterFingerprintDto> getFingerprint(UUID recipeId, UUID branchId);   // internal cross-module helper; no REST exposure
    ```
    `getBranches` / `getBranch` verbatim from LLD lines 528-529. `getFingerprint` is a cross-module helper — exposed on the interface for 01f/01j to inject, but **no controller wired in 01d**.
20. Append to `RecipeUpdateService`:
    ```java
    RecipeBranchDto createBranch(UUID recipeId, CreateBranchRequest request, UUID actorUserId);
    RecipeVersionDto revertToVersion(UUID recipeId, UUID branchId, int versionNumber, UUID actorUserId,
                                     long expectedRecipeOptimisticVersion);
    ```
    `createBranch` is new — LLD §`RecipeUpdateService` (lines 549-578) **doesn't list it** as a user-facing method; it only lists `saveAdaptedBranch` on the SPI. **01d adds the user-facing variant**; the SPI variant ships with 01f. Document the divergence on the service-impl Javadoc.

### Repository — additions to existing 01a/01b/01c repos

21. **`RecipeBranchRepository`** (existing from 01a; LLD line 491-494) already has `findAllByRecipeId`, `findByRecipeIdAndName`. **01d appends** (if not already present in 01a):
    ```java
    List<RecipeBranch> findAllByRecipeIdOrderByCreatedAtAsc(UUID recipeId);
    ```
22. **`RecipeVersionRepository`** (modified in 01c with `findCurrentVersionId`) — **no further changes**. Existing `findById`, `findWithBodyById` cover branch-creation lookups.
23. **Boundary**: existing `RecipeBoundaryTest` from 01a covers the repos. **No changes to the test**.

### Errors

24. New module exception subclasses extending the existing `RecipeException` from 01a:
    - `RecipeBranchNotFoundException` (404, `type = .../recipe-branch-not-found`) — LLD line 666 names it.
    - `RecipeBranchPointInvalidException` (422, `type = .../recipe-branch-point-invalid`) — NEW per invariant 7.2.
    - `RecipeBranchNameConflictException` (409, `type = .../recipe-branch-name-conflict`) — NEW per invariant 7.3.
    - `RecipeBranchNameReservedException` (422, `type = .../recipe-branch-name-reserved`) — NEW per invariant 7.4.
25. **Append four new `@ExceptionHandler` methods** to the existing `RecipeExceptionHandler` `@RestControllerAdvice` from 01a/01b/01c (already `@Order(Ordered.HIGHEST_PRECEDENCE)`). Do **NOT** create a second handler class. Do **NOT** modify `config/GlobalExceptionHandler.java`.

## Database

**Zero migrations.** 01d is a pure code-path change — branch creation writes to the existing `recipe_branches` (01a), `recipe_versions` (01a), `recipe_ingredients` / `recipe_method_steps` / `recipe_metadata` / `recipe_tags` (01a) tables. The `character_fingerprint` JSONB column already exists on `recipe_versions` from 01a's `V20260601800100__recipe_create_recipe_versions.sql` per LLD line 113.

**Note on `parent_version_id` for cross-branch v1**: the FK constraint `parent_version_id uuid REFERENCES recipe_versions(id)` from 01a's migration **allows non-null cross-branch parents** — the FK doesn't require parent and child to share a `branch_id`. 01d's cross-branch parent FK works without schema change.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/recipe.yaml`

(File created by 01a, extended by 01b/01c — append three new path-items below 01c's diff blocks. Do NOT touch existing path-items.)

```yaml
recipeBranches:
  get:
    tags: [Recipes]
    operationId: listRecipeBranches
    summary: List all branches of a recipe, sorted by creation time (main first).
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: All branches of the recipe.
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/recipe.yaml#/RecipeBranchDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Recipe not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
  post:
    tags: [Recipes]
    operationId: createRecipeBranch
    summary: Fork a recipe into a new branch off a specific version.
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
          schema: { $ref: '../schemas/recipe.yaml#/CreateBranchRequest' }
    responses:
      '201':
        description: Branch created; new RecipeBranchDto returned.
        headers:
          Location:
            schema: { type: string, format: uri }
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeBranchDto' }
      '400': { description: Validation error, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Recipe not found / not owned, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Branch name already exists for this recipe, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: Invalid branch-point version / reserved branch name / manual branch on SYSTEM recipe, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
recipeBranch:
  get:
    tags: [Recipes]
    operationId: getRecipeBranch
    summary: Fetch a single branch of a recipe.
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
      - in: path
        name: branchId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: The branch.
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeBranchDto' }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Branch not found / belongs to another recipe, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
recipeVersionsRevert:
  post:
    tags: [Recipes]
    operationId: revertRecipeToVersion
    summary: Revert a branch to an earlier version by writing a new version whose body clones the target.
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
          schema: { $ref: '../schemas/recipe.yaml#/RevertToVersionRequest' }
    responses:
      '200':
        description: New revert-version row created; returned with hydrated body.
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeVersionDto' }
      '400': { description: Validation error / no-op revert (target == current), content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '401': { description: Unauthenticated, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: Recipe / branch / version not found, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: Stale expectedRecipeOptimisticVersion, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: Revert on a SYSTEM-catalogue recipe, content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/recipe.yaml`

(Append three new schemas; `RecipeBranchDto`, `CharacterFingerprintDto`, `CreateIngredientRequest`, `CreateMethodStepRequest`, `CreateRecipeMetadataRequest`, `CreateRecipeTagsRequest`, `RecipeVersionDto`, `Complexity` are existing from 01a/01b/01c — verify before assuming. `CharacterFingerprintDto` may need adjustment to populated-shape if 01a's was a placeholder. Do NOT touch other 01a/01b/01c schemas.)

```yaml
CharacterFingerprintDto:
  type: object
  description: Per-version character fingerprint, refreshed only on branch creation.
  required: [definingIngredients, definingTechniques, textureEssentials, flavourAnchors, complexityTier]
  properties:
    definingIngredients:
      type: array
      maxItems: 5
      items: { type: string, maxLength: 160 }
    definingTechniques:
      type: array
      maxItems: 5
      items: { type: string, maxLength: 64 }
    textureEssentials:
      type: array
      maxItems: 5
      items: { type: string, maxLength: 64 }
    flavourAnchors:
      type: array
      maxItems: 5
      items: { type: string, maxLength: 64 }
    complexityTier: { $ref: '#/Complexity' }
    cuisineAnchor:
      type: string
      maxLength: 64
      nullable: true
CreateRecipeBodyRequest:
  type: object
  required: [ingredients, method, metadata]
  properties:
    ingredients:
      type: array
      minItems: 1
      items: { $ref: '#/CreateIngredientRequest' }
    method:
      type: array
      minItems: 1
      items: { $ref: '#/CreateMethodStepRequest' }
    metadata: { $ref: '#/CreateRecipeMetadataRequest' }
    tags: { $ref: '#/CreateRecipeTagsRequest' }   # optional — record nullability allowed
CreateBranchRequest:
  type: object
  required: [name, reason, branchPointVersionId, body]
  properties:
    name:
      type: string
      minLength: 1
      maxLength: 64
      pattern: '^[a-z0-9-]+$'
    label:
      type: string
      maxLength: 120
      nullable: true
    reason: { type: string, minLength: 1, maxLength: 2000 }
    branchPointVersionId: { type: string, format: uuid }
    body: { $ref: '#/CreateRecipeBodyRequest' }
    fingerprintOverride:
      type: object
      nullable: true
      description: When supplied, the branch's v1 character_fingerprint is set verbatim. Otherwise derived from the body.
      properties:
        definingIngredients:
          type: array
          items: { type: string, maxLength: 160 }
        definingTechniques:
          type: array
          items: { type: string, maxLength: 64 }
        textureEssentials:
          type: array
          items: { type: string, maxLength: 64 }
        flavourAnchors:
          type: array
          items: { type: string, maxLength: 64 }
        complexityTier: { type: string, enum: [MINIMAL, MODERATE, INVOLVED] }
        cuisineAnchor:
          type: string
          maxLength: 64
          nullable: true
RevertToVersionRequest:
  type: object
  required: [branchId, versionNumber, expectedRecipeOptimisticVersion]
  properties:
    branchId: { type: string, format: uuid }
    versionNumber: { type: integer, minimum: 1 }
    expectedRecipeOptimisticVersion: { type: integer, format: int64, minimum: 0 }
```

**Gotcha applied**: `fingerprintOverride` on `CreateBranchRequest` uses **inline** `type: object` + `nullable: true` (NOT `$ref + nullable: true` — sibling keywords on `$ref` are silently ignored). Inlining duplicates the `CharacterFingerprintDto` shape; that's the documented workaround.

**Gotcha applied**: `label`, `cuisineAnchor` use inline `nullable: true`.

### Append to entry `src/main/resources/openapi/openapi.yaml`

**Location**: under the existing `# recipe` block in `paths:` (after 01c's diff ref). Append three new path-item refs:

```yaml
  /api/v1/recipes/{recipeId}/branches:
    $ref: 'paths/recipe.yaml#/recipeBranches'
  /api/v1/recipes/{recipeId}/branches/{branchId}:
    $ref: 'paths/recipe.yaml#/recipeBranch'
  /api/v1/recipes/{recipeId}/versions/revert:
    $ref: 'paths/recipe.yaml#/recipeVersionsRevert'
```

**Location**: under `components.schemas:`, append three new schema refs in the existing `# recipe` block (alphabetical):

```yaml
    CreateBranchRequest: { $ref: 'schemas/recipe.yaml#/CreateBranchRequest' }
    CreateRecipeBodyRequest: { $ref: 'schemas/recipe.yaml#/CreateRecipeBodyRequest' }
    RevertToVersionRequest: { $ref: 'schemas/recipe.yaml#/RevertToVersionRequest' }
```

**Note**: 01a likely already declared a `CharacterFingerprintDto` schema (referenced from `RecipeVersionDto.characterFingerprint`). 01d **modifies** that schema to add `required`-array enforcement and tighten the field shapes per the populated record. If 01a's schema is already populated correctly, leave it alone. **Verify before modifying.**

## Verbatim shape snippets

### `CharacterFingerprintDto` Java record

```java
public record CharacterFingerprintDto(
    List<String> definingIngredients,         // 0..5
    List<String> definingTechniques,          // 0..5; empty in 01d
    List<String> textureEssentials,           // 0..5; empty in 01d
    List<String> flavourAnchors,              // 0..5
    Complexity complexityTier,
    String cuisineAnchor                       // nullable
) {}
```

### Fingerprint derivation skeleton

```java
@Component
public class FingerprintDeriver {
  CharacterFingerprintDto deriveFromBody(NewVersionInput body) {
    List<String> definingIngredients = body.ingredients().stream()
        .sorted(Comparator.comparingInt(CreateIngredientRequest::lineOrder))
        .limit(3)
        .map(CreateIngredientRequest::displayName)
        .toList();
    List<String> flavour = body.tags() == null || body.tags().flavourProfile() == null
        ? List.of()
        : body.tags().flavourProfile().stream().limit(5).toList();
    Complexity complexity = body.tags() == null || body.tags().complexity() == null
        ? Complexity.MODERATE
        : body.tags().complexity();
    String cuisine = body.metadata() == null ? null : body.metadata().cuisine();
    return new CharacterFingerprintDto(
        definingIngredients, List.of(), List.of(), flavour, complexity, cuisine);
  }
}
```

### Service-impl — `createBranch` skeleton

```java
@Transactional
public RecipeBranchDto createBranch(UUID recipeId, CreateBranchRequest request, UUID actorUserId) {
  Recipe recipe = recipeRepository.findByIdAndDeletedAtIsNull(recipeId)
      .orElseThrow(RecipeNotFoundException::new);
  if (recipe.getCatalogue() == Catalogue.SYSTEM) {
    throw new RecipeCatalogueViolationException("branch creation on SYSTEM recipe");
  }
  if (!recipe.getUserId().equals(actorUserId)) {
    throw new RecipeNotFoundException();                                    // do not leak
  }
  if ("main".equals(request.name())) {
    throw new RecipeBranchNameReservedException("'main' is reserved");
  }
  if (recipeBranchRepository.findByRecipeIdAndName(recipeId, request.name()).isPresent()) {
    throw new RecipeBranchNameConflictException(request.name());
  }
  RecipeVersion branchPoint = recipeVersionRepository.findWithBodyById(request.branchPointVersionId())
      .filter(v -> v.getRecipeId().equals(recipeId))
      .orElseThrow(() -> new RecipeBranchPointInvalidException(request.branchPointVersionId()));
  branchPoint.getIngredients().size();  branchPoint.getMethodSteps().size();  // force lazy-load

  NewVersionInput requested = mapper.fromBody(request.body());

  CharacterFingerprintDto childFingerprint = request.fingerprintOverride() != null
      ? request.fingerprintOverride()
      : fingerprintDeriver.deriveFromBody(requested);
  // Parent fingerprint may be null (01a/01c never wrote one) — use deriver
  CharacterFingerprintDto parentFingerprint = branchPoint.getCharacterFingerprint() != null
      ? branchPoint.getCharacterFingerprint()
      : fingerprintDeriver.deriveFromBody(NewVersionInput.fromVersion(branchPoint));
  BigDecimal divergence = divergenceCalculator.compute(parentFingerprint, childFingerprint);

  RecipeBranch branch = RecipeBranch.builder()
      .id(UUID.randomUUID())
      .recipe(recipe)
      .parentBranchId(branchPoint.getBranchId())
      .branchPointVersionId(branchPoint.getId())
      .name(request.name()).label(request.label()).reason(request.reason())
      .currentVersion(1).divergenceScore(divergence)
      .createdByActor("user:" + actorUserId).adapterTraceId(null)
      .build();
  recipeBranchRepository.saveAndFlush(branch);

  RecipeVersion v1 = RecipeVersion.builder()
      .id(UUID.randomUUID()).recipeId(recipeId).branchId(branch.getId())
      .versionNumber(1).parentVersionId(branchPoint.getId())
      .changeDiff(versionDiffer.diff(branchPoint, requested))
      .changeReason(request.reason()).trigger(VersionTrigger.BRANCH_CREATION)
      .characterFingerprint(childFingerprint).nutritionPerServing(null)
      .embeddingStatus("pending").createdByActor("user:" + actorUserId).adapterTraceId(null)
      .createdAt(Instant.now()).build();
  v1.replaceIngredients(buildIngredients(requested.ingredients(), v1));
  v1.replaceMethodSteps(buildMethodSteps(requested.method(), v1));
  v1.setMetadata(buildMetadata(requested.metadata(), v1));
  v1.setTags(buildTags(requested.tags(), v1));
  recipeVersionRepository.saveAndFlush(v1);

  publisher.publishEvent(new RecipeVersionCreatedEvent(
      v1.getId(), recipeId, branch.getId(), 1, traceIdFromMdcOrRandom(), Instant.now()));
  publisher.publishEvent(new RecipeBranchCreatedEvent(
      recipeId, branch.getId(), branch.getParentBranchId(), branchPoint.getId(),
      divergence, traceIdFromMdcOrRandom(), Instant.now()));
  return branchMapper.toDto(branch);
}
```

## Edge-case checklist

- [ ] `GET /recipes/{id}/branches` for a recipe with only main → 200, list of 1 (main)
- [ ] `GET /recipes/{id}/branches` for a recipe with main + 2 user branches → 200, list of 3, sorted `created_at ASC`
- [ ] `GET /recipes/{id}/branches` for non-existent recipe → 404
- [ ] `GET /recipes/{id}/branches/{branchId}` happy path → 200
- [ ] `GET /recipes/{id}/branches/{branchId}` for a branch belonging to a different recipe → 404 (don't leak)
- [ ] `POST /recipes/{id}/branches` happy path (USER recipe, owner, valid branch-point, unique name, no override) → 201; `Location` set; new `RecipeBranch` row exists with `currentVersion = 1`, `divergenceScore` populated; new `RecipeVersion` row at v1 with `trigger = BRANCH_CREATION`, `characterFingerprint` JSONB populated from derivation; body child rows inserted; **`Recipe.currentBranchId` UNCHANGED** (stays on main)
- [ ] `POST /recipes/{id}/branches` with `fingerprintOverride` supplied → branch row's character_fingerprint matches the override verbatim; derivation skipped
- [ ] `POST /recipes/{id}/branches` for a SYSTEM-catalogue recipe → 422 `recipe-catalogue-violation`
- [ ] `POST /recipes/{id}/branches` for a USER recipe owned by a different user → 404
- [ ] `POST /recipes/{id}/branches` with `branchPointVersionId` belonging to a different recipe → 422 `recipe-branch-point-invalid`
- [ ] `POST /recipes/{id}/branches` with `name = "main"` → 422 `recipe-branch-name-reserved`
- [ ] `POST /recipes/{id}/branches` with `name` already used in this recipe → 409 `recipe-branch-name-conflict`
- [ ] `POST /recipes/{id}/branches` validation: empty `name` → 400; `name` with capitals → 400 (`[a-z0-9-]+` pattern); empty `ingredients` → 400; missing `metadata` → 400; missing `reason` → 400; `branchPointVersionId = null` → 400
- [ ] `POST /recipes/{id}/branches` with override containing `complexityTier = null` → 400 (required field)
- [ ] Divergence calculator: identical fingerprints (both empty) → 0.000; identical non-empty → 0.000; completely disjoint → 1.000; one empty + one populated → 1.000; partial overlap → middle value (verified via unit test with hand-crafted inputs)
- [ ] `RecipeVersionCreatedEvent` AND `RecipeBranchCreatedEvent` BOTH published `AFTER_COMMIT` exactly once on create
- [ ] `RecipeUpdatedEvent` is **NOT** published on branch creation (locked decision)
- [ ] `POST /versions/revert` happy path → 200; new version row at `currentVersion + 1` with `trigger = REVERT`, `parentVersionId = previous current version's id`, body cloned from target; `Recipe.currentVersion` + `RecipeBranch.currentVersion` bumped
- [ ] `POST /versions/revert` for `versionNumber == currentVersion` → 400 `no-changes`
- [ ] `POST /versions/revert` for unknown branch → 404; unknown version → 404; cross-recipe branch → 404
- [ ] `POST /versions/revert` stale `expectedRecipeOptimisticVersion` → 409
- [ ] `POST /versions/revert` for SYSTEM recipe → 422
- [ ] `POST /versions/revert` publishes BOTH `RecipeVersionCreatedEvent` AND `RecipeUpdatedEvent` (`trigger = REVERT`)
- [ ] After revert + manual edit on the same branch, 01c's diff endpoint between the revert-version and the manual-edit-version still works (key-value lookup on `change_diff` — verified via IT)
- [ ] `recipe_versions` rows from branch creation include `parent_version_id` non-null and pointing at the branch-point version on a different `branch_id` (cross-branch parent FK)
- [ ] `RecipeBoundaryTest` (from 01a) still passes
- [ ] `RecipeExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the four new methods
- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in IT)
- [ ] No N+1 on the branch-creation hot path — verified via Hibernate statistics (load Recipe + branchPoint with body + branch repo find-by-name; INSERTs for new branch + version + children + UPDATEs nothing extra)

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/recipe/api/controller/RecipeBranchesController.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CharacterFingerprintDto.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CreateBranchRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/CreateRecipeBodyRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RevertToVersionRequest.java
NEW   src/main/java/com/example/mealprep/recipe/api/mapper/RecipeBranchMapper.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/DivergenceScoreCalculator.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/FingerprintDeriver.java
NEW   src/main/java/com/example/mealprep/recipe/event/RecipeBranchCreatedEvent.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeBranchNotFoundException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeBranchPointInvalidException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeBranchNameConflictException.java
NEW   src/main/java/com/example/mealprep/recipe/exception/RecipeBranchNameReservedException.java

MOD   src/main/java/com/example/mealprep/recipe/api/controller/RecipeVersionsController.java          (add POST /versions/revert handler; KEEP existing diff handler from 01c)
MOD   src/main/java/com/example/mealprep/recipe/api/RecipeExceptionHandler.java                        (append 4 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeQueryService.java                 (append getBranches, getBranch, getFingerprint)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeUpdateService.java                (append createBranch, revertToVersion)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java        (implement the five new methods; reuse 01c's VersionDiffer + child-row builders)
MOD   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeBranchRepository.java         (append findAllByRecipeIdOrderByCreatedAtAsc if 01a's variant doesn't sort)
MOD   src/main/java/com/example/mealprep/recipe/domain/entity/RecipeVersion.java                       (verify the JSONB `character_fingerprint` field has `@Type(JsonType.class)` and maps to CharacterFingerprintDto; 01a may have left it as raw JsonNode — adapt if needed)

MOD   src/main/resources/openapi/paths/recipe.yaml      (append 3 new path-items below 01c's; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/recipe.yaml    (append 3 new schemas: CreateBranchRequest, CreateRecipeBodyRequest, RevertToVersionRequest; verify CharacterFingerprintDto is fully populated and tighten if 01a left a placeholder)
MOD   src/main/resources/openapi/openapi.yaml           (3 lines under paths: in the `# recipe` block; 3 lines under components.schemas: in the `# recipe` block)

NEW   src/test/java/com/example/mealprep/recipe/RecipeBranchesServiceTest.java
NEW   src/test/java/com/example/mealprep/recipe/RecipeBranchesFlowIT.java
NEW   src/test/java/com/example/mealprep/recipe/DivergenceScoreCalculatorTest.java
NEW   src/test/java/com/example/mealprep/recipe/RecipeRevertFlowIT.java
MOD   src/test/java/com/example/mealprep/recipe/testdata/RecipeTestData.java                          (append CreateBranchRequest + RevertToVersionRequest builders + fingerprint builder)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-4 tickets running in parallel must not collide):
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`.
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, `<module>BoundaryTest.java`, migrations, entities — none touched.
- 01a's `Recipe`, `RecipeBranch`, `RecipeIngredient`, `RecipeMethodStep`, `RecipeMetadata`, `RecipeTags` entities — used as-is.
- 01a's `RecipeMapper.toDto(...)` — used as-is for the hydration helpers.
- 01c's `VersionDiffer` — reused for branch-creation's `change_diff` and revert's `change_diff` computation.
- 01c's `RecipesController` (manual-edit handler) — independent; this ticket adds a new `RecipeBranchesController` and extends `RecipeVersionsController` (already-touched in 01c).
- `RecipeBoundaryTest` is unchanged.

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe`, `RecipeBranch`, `RecipeVersion`, all body entities, repositories, `RecipeQueryService`, `RecipeUpdateService`, `RecipeExceptionHandler`, `RecipeBoundaryTest`, `RecipeException`, `RecipeNotFoundException`, `Catalogue`, `VersionTrigger` (including the `BRANCH_CREATION` and `REVERT` enum values — verify 01a declared them; if not, 01d appends them — `VersionTrigger.java` is the **one shared file** the agent may modify), `Complexity` enum, `RecipeVersionCreatedEvent`, `RecipeMapper`, `CreateIngredientRequest`, `CreateMethodStepRequest`, `CreateRecipeMetadataRequest`, `CreateRecipeTagsRequest`.
- **Hard dependency**: `recipe-01b` (merged) — `RecipeImport` (untouched but co-exists); the per-module YAML / advice append-only convention.
- **Hard dependency**: `recipe-01c` (merged) — `VersionDiffer` (re-used by 01d for both branch creation's diff and revert's diff), `NoChangesException` (re-used for no-op revert), `RecipeCatalogueViolationException`, `RecipeVersionNotFoundException`, `RecipeUpdatedEvent`.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **Sibling tickets running in parallel** (Wave 2 round 4): `household-01d`, `nutrition-01d`, `provisions-01d`. None should touch any recipe file or any of the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# recipe` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `RecipeExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending the four new methods
- [ ] `saveAndFlush` used on `RecipeBranch` and `RecipeVersion` after building children so child INSERTs see the parent's id
- [ ] `recipe_versions` rows are still append-only after 01d — verified by an IT that asserts no UPDATE statements on `recipe_versions` during a branch-creation flow (the `Recipe` and `RecipeBranch` rows ARE updated for `currentVersion` bumps in the revert path; that's allowed)
- [ ] `DivergenceScoreCalculatorTest` covers: identical, completely disjoint, partial overlap, both empty, one-empty-one-populated, single-field-difference
- [ ] Cross-branch `parent_version_id` FK in 01a's migration permits the cross-branch parent reference (verified by an IT that inserts a branch v1 with `parent_version_id = main.v3.id` and queries successfully)
- [ ] OpenAPI 3.0 nullable fields use **inline** `nullable: true` (NOT `$ref + nullable: true`) on `label`, `cuisineAnchor`, `fingerprintOverride`
- [ ] `CharacterFingerprintDto` JSONB round-trips on `recipe_versions.character_fingerprint` — write entity → re-read → fields identical
- [ ] No regression on existing tests, including 01a's `RecipeCreateFlowIT`, 01b's `RecipeImportFlowIT`, 01c's `RecipeManualEditFlowIT`, 01c's `RecipeVersionDiffFlowIT`
- [ ] No N+1 on the branch-creation hot path — single SELECT for Recipe, single SELECT (with `@EntityGraph` body fetch) for branchPoint, single SELECT for the name-uniqueness check; verified via Hibernate stats
- [ ] No pom.xml dependency adds

## What's NOT in scope

- Substitutions (`RecipeSubstitution` + endpoints + substitution-promotion as a version) → **recipe-01e**
- `RecipeWriteApi` SPI + adaptation events (`RecipeAdaptedEvent`, `RecipeEvolvedEvent` listener for nutrition recalc) → **recipe-01f**
- Promotion / demotion / archive / `ArchiveEligibilityScanner` → **recipe-01g**
- Branch checkout endpoint (`POST /branches/{branchId}/checkout` mutating `Recipe.currentBranchId`) → **recipe-01g**
- pgvector + embedding column population (async listener on `RecipeVersionCreatedEvent`) → **recipe-01h**
- Search → **recipe-01i**
- Cross-module helpers `getIngredientMappingKeys`, `getNutritionPerServing`, `getEmbedding` → **recipe-01j** (01d adds `getFingerprint` as an internal helper only)
- AI tag inference + AI-derived fingerprint refresh → **recipe-01k**
- Cross-branch diff recompute — locked-out from 01c; 01d makes cross-branch reachable but the 422 from 01c stands
- Pipeline-driven `saveAdaptedBranch` (the SPI) → **recipe-01f**
- AI-rich fingerprint inference (`definingTechniques`, `textureEssentials`) — 01d ships empty lists; AI inference deferred to **recipe-01k**
- Pipeline-canonical divergence-score formula — 01d ships provisional jaccard-mean; canonical formula owned by the **pipeline LLD** (LLD line 838)
- Branch-rename endpoint — none specified in the LLD

Squash-merge with: `feat(recipe): 01d — branch creation + revertToVersion + CharacterFingerprintDto + DivergenceScoreCalculator + RecipeBranchCreatedEvent`
