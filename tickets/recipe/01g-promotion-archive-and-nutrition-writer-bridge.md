# Ticket: recipe — 01g Promotion + Archive + `ArchiveEligibilityScanner` + `RecipeNutritionWriterImpl` Bridge

## Summary

Layer the catalogue-move flows + the daily archive-eligibility scanner on top of 01a..01f. Per [LLD §`RecipeUpdateService.promoteToUserCatalogue` / `archive` / `unarchive` line 562-577](../../lld/recipe.md), [LLD §Flow 7/8 lines 759-764](../../lld/recipe.md), [LLD §Flow 9 lines 766-772](../../lld/recipe.md), [LLD §`findArchiveEligibleSystemRecipes` lines 466-471](../../lld/recipe.md), [LLD §`ArchiveScanIT` line 821](../../lld/recipe.md). Ships:

- **`POST /api/v1/recipes/{recipeId}/promote`** — `promoteToUserCatalogue(systemRecipeId, callingUserId)` — flip-in-place from `catalogue=SYSTEM` to `catalogue=USER`, set `userId = callingUserId`, publish `RecipePromotedEvent`.
- **`POST /api/v1/recipes/{recipeId}/demote`** — `demoteToSystemCatalogue(userRecipeId, actorUserId)` — flip-in-place from `USER` to `SYSTEM`, retain `userId` for provenance, publish `RecipeArchivedEvent(cause=USER_DEMOTION)`.
- **`POST /api/v1/recipes/{recipeId}/archive`** — `archive(recipeId, actorUserId)` — soft archive; sets `archived_at = now()`, publishes `RecipeArchivedEvent(cause=MANUAL_ADMIN)`.
- **`POST /api/v1/recipes/{recipeId}/unarchive`** — `unarchive(recipeId, actorUserId)` — clears `archived_at`, no event.
- **`ArchiveEligibilityScanner`** — `@Scheduled(cron = "0 30 3 * * *")` daily job in `recipe/domain/service/internal/`. Computes `cutoff = now - 90 days`, batches `findArchiveEligibleSystemRecipes(cutoff)`, sets `archived_at`, publishes one `RecipeArchivedEvent(cause=INACTIVITY_3_MONTHS)` per row.
- **Manual admin trigger** — `POST /api/v1/recipes/admin/run-archive-scan` returns `{ flaggedCount }` (LLD line 656).
- **`markUsedInPlan(List<UUID> recipeIds)`** — bulk update of `last_used_in_plan_at = now()` per LLD line 576, exposed on `RecipeUpdateService` so the cook listener (provisions-01g) and the future planner module call it.
- **The SPI bridge commit** — `RecipeNutritionWriterImpl` deferred from recipe-01f, now landable because nutrition-01f's `RecipeNutritionWriter` interface is on `main`. Single `@Component @ConditionalOnClass` class + a small IT verifying the cross-module wire.

## Why the SPI bridge belongs in 01g, not a separate commit

Recipe-01f shipped `RecipeWriteApi` + events but **deferred** `RecipeNutritionWriterImpl` for parallel-safety: the impl class can't compile until nutrition-01f's `RecipeNutritionWriter` interface is on the recipe branch's classpath. Both 01f's are now merged on `main`, so the interface IS on the classpath. 01g picks up the bridge:

- One class: `recipe/spi/internal/RecipeNutritionWriterImpl.java` (verbatim shape from recipe-01f's ticket lines 290-316 — copy-paste).
- One IT: verifies the `@Component` wires when the nutrition module is on the classpath AND delegates to `RecipeWriteApi.updateNutritionStatus` correctly.
- Zero changes to nutrition-01f's `NoopRecipeNutritionWriterConfiguration` — its `@ConditionalOnMissingBean(RecipeNutritionWriter.class)` defers as soon as the recipe-side `@Component` registers.

**Worth user review**: alternative is a tiny dedicated bridge PR. Bundled here because (a) it's 2 files, (b) the round-6 retro line 82 already plans for "a small bridge commit to wire `RecipeNutritionWriterImpl` now that both nutrition-01f and recipe-01f are merged on main", and (c) 01g's archive scan needs to assert that `nutrition_status` rows survive the archive flip — exercising the cross-module wire is a natural fit for the same IT.

## LLD divergence — none of note

LLD line 562-577 enumerates `promoteToUserCatalogue`, `demoteToSystemCatalogue`, `archive`, `unarchive`, `softDelete`, `revertToVersion`. 01g ships the first four; `softDelete` and `revertToVersion` are **deferred**:

- `softDelete` — sets `deleted_at`; covered by 01c's existing DELETE endpoint (verify; if not, ship it in a later ticket).
- `revertToVersion` — already shipped in recipe-01c (`POST /{id}/versions/revert`).

LLD line 766-772 (Flow 9) is shipped verbatim — same cron expression, same 90-day cutoff, same batch size (100), same `RecipeArchivedEvent(cause=INACTIVITY_3_MONTHS)`.

LLD line 656 (`RecipeAdminController` admin trigger) is shipped — single `POST /admin/run-archive-scan` returning a small `{ flaggedCount }` JSON shape.

## Defers (still out of scope after 01g)

- `markUsedInPlan` callers — the **cook listener** is provisions-01g's concern (provisions-01g already lists it as a publisher of `MealCookedEvent`-derived adjustments; the recipe-side listener that calls `markUsedInPlan` lives in a future planner module). 01g exposes the method on `RecipeUpdateService` so the wiring is ready.
- Recipe embeddings (`embedding` column + HNSW index) → **recipe-01h**.
- Recipe search → **recipe-01i**.
- Cross-module helpers (`getIngredientMappingKeys` etc) → **recipe-01j**.
- AI tag inference → **recipe-01k**.
- `softDelete` flow — not in 01g scope.
- Household-aware promotion ("copy to my library" when household LLD lands) → future migration + endpoint.
- Admin-role gate on `/admin/run-archive-scan` — open to authenticated callers v1, lock down when role enum gains `ADMIN`.

## Behavioural spec

### Promote system → user

1. **Endpoint**: `POST /api/v1/recipes/{recipeId}/promote`. No request body. Authenticated.
2. **Service signature** (LLD line 563): `RecipeDto promoteToUserCatalogue(UUID systemRecipeId, UUID userId)`. Joins existing `RecipeServiceImpl`.
3. **Transactional, write**. `@Transactional` (REQUIRED).
4. Loads the recipe via `recipeRepository.findById(systemRecipeId)` (404 `RecipeNotFoundException` if missing).
5. **Catalogue assertion**: `recipe.catalogue == Catalogue.SYSTEM` else **422 `RecipeCatalogueViolationException`** (existing from recipe-01a/01c). Message: `"recipe is already in user catalogue"`.
6. **Soft-delete assertion**: `recipe.deletedAt == null` else **422** (don't promote a deleted row). Same exception.
7. **Archived assertion**: `recipe.archivedAt == null` else **422** (don't promote an archived row; user must unarchive first). Same exception.
8. **Flip-in-place** per LLD line 763:
   - `recipe.catalogue = Catalogue.USER`
   - `recipe.userId = userId` (the calling user — promotion claims ownership)
   - Versions / branches / substitutions untouched. Existing plan references stay valid (same UUID).
   - `optimistic_version` bumps via `@Version`.
9. **Publish** `RecipePromotedEvent(recipeId, userId, fromCatalogue=SYSTEM, toCatalogue=USER, traceId, occurredAt)` `AFTER_COMMIT` (LLD line 701).
10. **Response**: 200 + `RecipeDto` (the now-USER recipe).
11. **Worth user review**: LLD line 763 notes "Multi-user household: first user wins; a copy-to-my-library flow can be added when the household LLD lands." 01g implements the "first user wins" semantics — a subsequent `promote` call from a different user on the same (now-USER) recipe gets a 422 because `catalogue != SYSTEM`.

### Demote user → system

12. **Endpoint**: `POST /api/v1/recipes/{recipeId}/demote`. No request body. Authenticated.
13. **Service signature** (LLD line 564): `void demoteToSystemCatalogue(UUID userRecipeId, UUID actorUserId)`.
14. **Transactional, write**.
15. Loads (404 if missing).
16. **Ownership check**: `recipe.userId == actorUserId` else **404 `RecipeNotFoundException`** (don't leak existence; same rule as 01a's get-by-id).
17. **Catalogue assertion**: `recipe.catalogue == Catalogue.USER` else **422 `RecipeCatalogueViolationException`**.
18. **Flip-in-place** per LLD line 764:
   - `recipe.catalogue = Catalogue.SYSTEM`
   - `recipe.userId` **retained for provenance**.
   - `recipe.deletedAt` left null — the recipe stays pool-accessible.
   - `recipe.archivedAt` left untouched.
19. **Publish** `RecipeArchivedEvent(recipeId, cause=USER_DEMOTION, traceId, occurredAt)` `AFTER_COMMIT` (LLD line 698, 764).
20. **Response**: 204 (no content).

### Archive (manual)

21. **Endpoint**: `POST /api/v1/recipes/{recipeId}/archive`. No request body. Authenticated.
22. **Service signature** (LLD line 565): `void archive(UUID recipeId, UUID actorUserId)`.
23. **Transactional, write**.
24. Loads (404 if missing).
25. **Authorisation**:
    - `catalogue == USER`: `recipe.userId == actorUserId` else 404.
    - `catalogue == SYSTEM`: any authenticated caller can archive (manual admin path). **Worth user review** — alternative is to require an admin role; v1 keeps it open and locks down when the role enum gains `ADMIN`.
26. **Already-archived guard**: `recipe.archivedAt != null` → 204 (idempotent no-op). No event re-published.
27. **Already-deleted guard**: `recipe.deletedAt != null` → **422 `RecipeCatalogueViolationException`** (`"recipe is already deleted"`).
28. **Write**: `recipe.archivedAt = Instant.now()`. `optimistic_version` bumps.
29. **Publish** `RecipeArchivedEvent(recipeId, cause=MANUAL_ADMIN, traceId, occurredAt)` `AFTER_COMMIT`.
30. **Response**: 204.

### Unarchive

31. **Endpoint**: `POST /api/v1/recipes/{recipeId}/unarchive`. Authenticated.
32. **Service signature** (LLD line 566): `void unarchive(UUID recipeId, UUID actorUserId)`.
33. **Transactional, write**.
34. Loads + ownership (same rules as `archive`).
35. **Already-unarchived guard**: `recipe.archivedAt == null` → 204 (idempotent no-op).
36. **Write**: `recipe.archivedAt = null`. `optimistic_version` bumps.
37. **No event published** per LLD §Events (no `RecipeUnarchivedEvent` in LLD line 691-707).
38. **Response**: 204.

### `ArchiveEligibilityScanner`

39. New `@Component` `ArchiveEligibilityScanner` in `recipe/domain/service/internal/` (package-private).
40. **Schedule**: `@Scheduled(cron = "${mealprep.recipe.archive.cron:0 30 3 * * *}")` — daily at 03:30 UTC, configurable via `application.yml` property.
41. **Cutoff**: `Instant cutoff = Instant.now().minus(Duration.ofDays(90))`.
42. **Query** (new repo method per LLD line 466-471): `List<UUID> findArchiveEligibleSystemRecipes(Instant cutoff)` returns up to 1000 IDs ordered by `last_used_in_plan_at ASC NULLS FIRST` (so the oldest get processed first):
    ```java
    @Query("""
       select r.id from Recipe r
       where r.catalogue = 'SYSTEM'
         and r.archivedAt is null
         and r.deletedAt is null
         and (r.lastUsedInPlanAt is null or r.lastUsedInPlanAt < :cutoff)
       order by r.lastUsedInPlanAt asc nulls first
    """)
    List<UUID> findArchiveEligibleSystemRecipes(@Param("cutoff") Instant cutoff, Pageable page);
    ```
    The caller passes `PageRequest.of(0, 1000)` to bound the batch. **Worth user review** — alternative is unbounded; rejected because a one-off backfill could churn the scheduler thread. The job runs daily — 1000/day comfortably handles steady-state archival growth.
43. **Batch loop**: per batch of 100 (chunk the 1000 into 10 batches of 100), execute:
    ```java
    @Modifying
    @Query("update Recipe r set r.archivedAt = :now where r.id in :ids and r.archivedAt is null")
    int markArchived(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);
    ```
    This is **bulk update bypassing the `@Version` check** — that's intentional for the system-archive flow (no race with user edits because `catalogue=SYSTEM` recipes have no `userId` writing them concurrently).
44. **Per-ID event**: after each batch commits, publish one `RecipeArchivedEvent(recipeId, cause=INACTIVITY_3_MONTHS, traceId=randomUUID, occurredAt=now)` per archived ID — published via `ApplicationEventPublisher` in the same transaction; listeners fire `AFTER_COMMIT`.
45. **Logging**: INFO log per batch summary (`count flagged`, `oldestLastUsed`, `elapsedMs`). DEBUG log per ID if needed.
46. **Idempotency**: re-running the scan on the same day produces zero new archives (the `archivedAt is null` filter excludes already-archived rows). The `update ... where archivedAt is null` guard is belt-and-braces against race conditions.

### Admin trigger

47. **Endpoint**: `POST /api/v1/recipes/admin/run-archive-scan`. No request body. Authenticated.
48. **Controller**: new `RecipeAdminController` under `recipe/api/controller/`. **Worth user review** — alternative is to extend the existing `RecipesController`; rejected because admin endpoints are a distinct concern.
49. **Authorisation**: any authenticated caller v1 (no admin role). Document as TODO in the controller class Javadoc.
50. **Body**: invokes `archiveEligibilityScanner.runOnce()` synchronously (same job body the `@Scheduled` calls). Returns `{ flaggedCount: integer }` JSON. 200 status.
51. **Worth user review**: `runOnce()` blocks the request thread for the duration of the scan. With max-batch=1000 and chunk=100, this is ~10 DB round-trips — usually <1s. If the scan grows, an async pattern (return 202 + a job-id) is the natural follow-up.

### `markUsedInPlan`

52. **Service signature** (LLD line 577): `void markUsedInPlan(List<UUID> recipeIds)`. Joins `RecipeUpdateService`.
53. **Transactional, write**.
54. **Empty list** → no-op (no SQL).
55. **Bulk update via `@Modifying` JPA query**:
    ```java
    @Modifying
    @Query("update Recipe r set r.lastUsedInPlanAt = :now where r.id in :ids")
    int touchLastUsedInPlan(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);
    ```
56. **No event published**.
57. **No exception on unknown IDs** — bulk updates are tolerant; the cook listener may pass IDs no longer in the catalogue. Returns count is informational only.
58. **No REST endpoint** — pure in-process method consumed by the cook listener (provisions-01g) and the future planner module.

### `RecipeNutritionWriterImpl` (bridge from recipe-01f)

59. New class `com.example.mealprep.recipe.spi.internal.RecipeNutritionWriterImpl` (package-private). **Verbatim shape** from recipe-01f's deferred snippet:
   ```java
   @Component
   @ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")
   class RecipeNutritionWriterImpl implements com.example.mealprep.nutrition.spi.RecipeNutritionWriter {
     private static final Logger log = LoggerFactory.getLogger(RecipeNutritionWriterImpl.class);
     private final RecipeWriteApi writeApi;
     private final ObjectMapper objectMapper;

     RecipeNutritionWriterImpl(RecipeWriteApi writeApi, ObjectMapper objectMapper) {
       this.writeApi = writeApi;
       this.objectMapper = objectMapper;
     }

     @Override
     public void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result) {
       log.debug("Writing nutrition for version {} (status {}, calories {}/serving)",
           versionId, result.nutritionStatus(), result.caloriesPerServing());
       NutritionStatus status = NutritionStatus.valueOf(result.nutritionStatus().toUpperCase());
       writeApi.updateNutritionStatus(versionId, status, objectMapper.valueToTree(result));
     }
   }
   ```
60. **`@ConditionalOnClass(name = "...")` string-form** — NOT class-literal — so the impl class never tries to resolve the missing class at class-load time. This is the parallel-safety mechanism from recipe-01f.
61. The bean's presence **automatically defers** nutrition-01f's `NoopRecipeNutritionWriter` because the Noop bean is `@ConditionalOnMissingBean(RecipeNutritionWriter.class)`.
62. **No `@ConditionalOnMissingBean` on the `@Component`** — that's the round-5 bug-1 pattern that the recipe-01f ticket explicitly warned against. Use `@ConditionalOnClass` instead.

### Errors

63. **No new exception types**. Existing `RecipeNotFoundException` (404), `RecipeCatalogueViolationException` (422), `OptimisticLockException` (409) from 01a/01b/01c cover every promote/demote/archive failure.
64. **No change** to `RecipeExceptionHandler` (no new `@ExceptionHandler` methods). Verify the existing handler maps `RecipeCatalogueViolationException` → 422 (it should from 01c per LLD line 668).
65. **DO NOT** modify `config/GlobalExceptionHandler.java`.

### Cross-module facade

66. **Append** `RecipeUpdateService.markUsedInPlan` method (already on the interface per LLD line 577). **Append** the impl on `RecipeServiceImpl`. The interface re-export on `RecipeModule.java` is already in place from 01a.

### Migration

67. **Zero new migrations.** The columns `archived_at`, `last_used_in_plan_at`, `forked_from_recipe_id`, `catalogue` already exist on `recipe_recipes` from 01a's `V20260601800000__recipe_create_recipes.sql` (verified: see file lines 12, 19, 20, 21). The indexes `idx_recipe_recipes_system_last_used` and `idx_recipe_recipes_catalogue_active` likewise already in place.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/recipe.yaml`

(File extended by 01a..01f — append 5 new path-items below 01f's most recent block. Do NOT touch existing path-items.)

```yaml
recipePromote:
  post:
    tags: [Recipes]
    operationId: promoteRecipeToUserCatalogue
    summary: 'Promote a SYSTEM-catalogue recipe to the calling user''s USER catalogue (flip-in-place).'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: 'Recipe is now in the user catalogue.'
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RecipeDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Recipe not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Recipe is already USER catalogue, deleted, or archived', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

recipeDemote:
  post:
    tags: [Recipes]
    operationId: demoteRecipeToSystemCatalogue
    summary: 'Demote a USER-catalogue recipe owned by the caller to SYSTEM catalogue (flip-in-place, retains userId for provenance).'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '204': { description: 'Recipe demoted; no body.' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Recipe not found or not owned by the caller', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Recipe is already SYSTEM catalogue', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

recipeArchive:
  post:
    tags: [Recipes]
    operationId: archiveRecipe
    summary: 'Soft-archive the recipe (sets archived_at); idempotent.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '204': { description: 'Recipe archived (or already was; idempotent).' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Recipe not found or not owned by the caller', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Recipe is already deleted', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

recipeUnarchive:
  post:
    tags: [Recipes]
    operationId: unarchiveRecipe
    summary: 'Unarchive the recipe (clears archived_at); idempotent.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '204': { description: 'Recipe unarchived (or was not archived; idempotent).' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Recipe not found or not owned by the caller', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }

recipeAdminRunArchiveScan:
  post:
    tags: [Recipes]
    operationId: runArchiveScan
    summary: 'Manual trigger for the daily archive-eligibility scan; returns the count of newly-archived rows.'
    security: [{ cookieAuth: [] }]
    responses:
      '200':
        description: 'Scan completed; flaggedCount is the number of rows newly archived this run.'
        content:
          application/json:
            schema: { $ref: '../schemas/recipe.yaml#/RunArchiveScanResultDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
```

### Append to `src/main/resources/openapi/schemas/recipe.yaml`

```yaml
RunArchiveScanResultDto:
  type: object
  required: [flaggedCount]
  properties:
    flaggedCount:
      type: integer
      minimum: 0
      description: 'Count of system-catalogue rows newly transitioned to archived_at != null this run.'
```

**Gotcha applied**: every description string containing `,` `:` `'` is single-quoted per round-4 lesson (`'recipe is already deleted'`, `'Scan completed; flaggedCount is...'`, `'Promote a SYSTEM-catalogue recipe...'`, etc).

**Gotcha applied**: no nullable fields on any new schema; no risk of the `$ref + nullable` sticky trap.

### Append to entry `src/main/resources/openapi/openapi.yaml`

Under `paths:` in the `# recipe` block (append after 01f's most recent — or after 01c's path-items since 01f added zero):

```yaml
  /api/v1/recipes/{recipeId}/promote:    { $ref: 'paths/recipe.yaml#/recipePromote' }
  /api/v1/recipes/{recipeId}/demote:     { $ref: 'paths/recipe.yaml#/recipeDemote' }
  /api/v1/recipes/{recipeId}/archive:    { $ref: 'paths/recipe.yaml#/recipeArchive' }
  /api/v1/recipes/{recipeId}/unarchive:  { $ref: 'paths/recipe.yaml#/recipeUnarchive' }
  /api/v1/recipes/admin/run-archive-scan: { $ref: 'paths/recipe.yaml#/recipeAdminRunArchiveScan' }
```

Under `components.schemas:` in the `# recipe` block:

```yaml
    RunArchiveScanResultDto: { $ref: 'schemas/recipe.yaml#/RunArchiveScanResultDto' }
```

## Verbatim shape snippets

### Promote impl skeleton

```java
@Override
@Transactional
public RecipeDto promoteToUserCatalogue(UUID systemRecipeId, UUID userId) {
  Recipe recipe = recipeRepository.findById(systemRecipeId).orElseThrow(RecipeNotFoundException::new);
  if (recipe.getCatalogue() != Catalogue.SYSTEM) {
    throw new RecipeCatalogueViolationException("recipe is already in user catalogue");
  }
  if (recipe.getDeletedAt() != null) {
    throw new RecipeCatalogueViolationException("recipe is deleted");
  }
  if (recipe.getArchivedAt() != null) {
    throw new RecipeCatalogueViolationException("recipe is archived; unarchive before promoting");
  }
  recipe.setCatalogue(Catalogue.USER);
  recipe.setUserId(userId);
  eventPublisher.publishEvent(new RecipePromotedEvent(systemRecipeId, userId,
      Catalogue.SYSTEM, Catalogue.USER, UUID.randomUUID(), Instant.now()));
  return recipeMapper.toDto(recipe);
}
```

### `ArchiveEligibilityScanner` skeleton

```java
@Component
class ArchiveEligibilityScanner {
  private static final Logger log = LoggerFactory.getLogger(ArchiveEligibilityScanner.class);
  private static final int BATCH = 100;
  private static final int MAX_PER_RUN = 1000;
  private static final Duration WINDOW = Duration.ofDays(90);

  private final RecipeRepository recipeRepository;
  private final ApplicationEventPublisher events;

  @Scheduled(cron = "${mealprep.recipe.archive.cron:0 30 3 * * *}")
  public int runScheduled() { return runOnce(); }

  @Transactional
  public int runOnce() {
    Instant now = Instant.now();
    Instant cutoff = now.minus(WINDOW);
    List<UUID> ids = recipeRepository.findArchiveEligibleSystemRecipes(cutoff,
        PageRequest.of(0, MAX_PER_RUN));
    if (ids.isEmpty()) {
      log.info("ArchiveEligibilityScanner: 0 rows eligible");
      return 0;
    }
    int total = 0;
    for (int i = 0; i < ids.size(); i += BATCH) {
      List<UUID> chunk = ids.subList(i, Math.min(i + BATCH, ids.size()));
      int n = recipeRepository.markArchived(chunk, now);
      total += n;
      for (UUID id : chunk) {
        events.publishEvent(new RecipeArchivedEvent(id,
            ArchiveCause.INACTIVITY_3_MONTHS, UUID.randomUUID(), now));
      }
    }
    log.info("ArchiveEligibilityScanner: archived {} rows (cutoff={})", total, cutoff);
    return total;
  }
}
```

### `RecipeNutritionWriterImpl` (bridge)

```java
package com.example.mealprep.recipe.spi.internal;

@Component
@ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")
class RecipeNutritionWriterImpl implements com.example.mealprep.nutrition.spi.RecipeNutritionWriter {
  private static final Logger log = LoggerFactory.getLogger(RecipeNutritionWriterImpl.class);
  private final RecipeWriteApi writeApi;
  private final ObjectMapper objectMapper;

  RecipeNutritionWriterImpl(RecipeWriteApi writeApi, ObjectMapper objectMapper) {
    this.writeApi = writeApi;
    this.objectMapper = objectMapper;
  }

  @Override
  public void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result) {
    log.debug("Writing nutrition for version {} (status {}, calories {}/serving)",
        versionId, result.nutritionStatus(), result.caloriesPerServing());
    NutritionStatus status = NutritionStatus.valueOf(result.nutritionStatus().toUpperCase());
    writeApi.updateNutritionStatus(versionId, status, objectMapper.valueToTree(result));
  }
}
```

## Edge-case checklist

### Promote

- [ ] `POST /promote` on SYSTEM recipe → 200 + RecipeDto with `catalogue=USER`, `userId=callerId`
- [ ] `POST /promote` on USER recipe → 422 `recipe-catalogue-violation`
- [ ] `POST /promote` on archived SYSTEM recipe → 422 `recipe-catalogue-violation`
- [ ] `POST /promote` on deleted recipe → 422
- [ ] `POST /promote` on missing recipe → 404
- [ ] `POST /promote` anonymous → 401
- [ ] After promote, plan references by the same recipe ID still resolve (no copy-on-promote; IDs preserved)
- [ ] `RecipePromotedEvent` published exactly once `AFTER_COMMIT` with `fromCatalogue=SYSTEM, toCatalogue=USER`

### Demote

- [ ] `POST /demote` on caller's USER recipe → 204 + recipe now SYSTEM
- [ ] `POST /demote` on someone-else's USER recipe → 404 (don't leak existence)
- [ ] `POST /demote` on SYSTEM recipe → 422
- [ ] `POST /demote` retains `userId` for provenance (verified via JdbcTemplate after the 204)
- [ ] `RecipeArchivedEvent(cause=USER_DEMOTION)` published once `AFTER_COMMIT`

### Archive / Unarchive

- [ ] `POST /archive` on caller's USER recipe → 204 + `archived_at` non-null
- [ ] `POST /archive` on already-archived → 204, no new event
- [ ] `POST /archive` on deleted recipe → 422
- [ ] `POST /archive` on someone-else's USER recipe → 404
- [ ] `POST /archive` on SYSTEM recipe (any caller) → 204 (v1 admin-open policy)
- [ ] `POST /unarchive` on archived recipe → 204 + `archived_at` null
- [ ] `POST /unarchive` on unarchived recipe → 204 (idempotent no-op)
- [ ] `POST /unarchive` publishes **no** event (LLD has no `RecipeUnarchivedEvent`)
- [ ] `RecipeArchivedEvent(cause=MANUAL_ADMIN)` published once on successful archive

### `ArchiveEligibilityScanner`

- [ ] System recipes with `last_used_in_plan_at < cutoff` get `archived_at` set
- [ ] System recipes with `last_used_in_plan_at >= cutoff` are preserved
- [ ] System recipes with `last_used_in_plan_at IS NULL` are eligible (treated as "never used")
- [ ] USER recipes never archived by the scan
- [ ] Already-archived recipes never re-archived (the `archivedAt is null` guard)
- [ ] Already-deleted recipes never archived
- [ ] Exactly one `RecipeArchivedEvent(cause=INACTIVITY_3_MONTHS)` per archived row
- [ ] Scan returns the correct `flaggedCount`
- [ ] Re-running the scan immediately → 0 new archives
- [ ] Batch size = 100, max-per-run = 1000 — 1500-row eligibility pool gets exactly 1000 in run 1 + 500 in run 2
- [ ] `@Scheduled` cron is configurable via `mealprep.recipe.archive.cron`
- [ ] `POST /admin/run-archive-scan` → 200 with `{flaggedCount: N}` matching the actual archived count

### `markUsedInPlan`

- [ ] Single ID → one row's `last_used_in_plan_at` updated; returns 1
- [ ] Multiple IDs → all rows updated; returns count
- [ ] Empty list → no SQL issued; returns 0
- [ ] Unknown IDs → no exception; returns count of actually-updated rows (likely 0)
- [ ] No event published

### `RecipeNutritionWriterImpl` bridge

- [ ] Bean **wires** in the full Spring context (verified via context-load IT) — `RecipeNutritionWriter` resolves to `RecipeNutritionWriterImpl`, NOT the nutrition-01f `NoopRecipeNutritionWriterImpl`
- [ ] Calling `writeNutritionPerServing(versionId, result)` delegates to `RecipeWriteApi.updateNutritionStatus(versionId, status, jsonNode)` with the enum + JSONB derived from `result`
- [ ] `RecipeNutritionResultDto.nutritionStatus()` string `"calculated"` → enum `NutritionStatus.CALCULATED` via `valueOf(toUpperCase())`
- [ ] `RecipeNutritionResultDto.nutritionStatus()` string `"partial"` → enum `NutritionStatus.PARTIAL`
- [ ] `RecipeNutritionResultDto.nutritionStatus()` string `"pending"` → enum `NutritionStatus.PENDING`
- [ ] `writeApi.updateNutritionStatus` publishes `RecipeEvolvedEvent(reason=NUTRITION_RECALCULATED)` (from recipe-01f) — verified via event-capture spy
- [ ] **End-to-end IT**: a `RecipeUpdatedEvent` triggers nutrition-01f's listener → calls `recalculateForEvolvedRecipe` → invokes `RecipeNutritionWriter.writeNutritionPerServing` (now the recipe-01g impl, not the Noop) → row's `nutrition_status` and `nutrition_per_serving` columns reflect the write
- [ ] `@ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")` uses the string-name form (NOT class-literal) — verified via grep on the file
- [ ] No `@ConditionalOnMissingBean` on the `RecipeNutritionWriterImpl` class (round-5 bug-1 avoidance)

### Cross-cutting

- [ ] OpenAPI request/response shapes match (swagger-request-validator filter active in the IT)
- [ ] `RecipeBoundaryTest` (from 01a) still passes — no new sub-packages required (controllers under `api/controller/`, scanner in `domain/service/internal/`, impl in existing `spi/internal/`)
- [ ] `RecipeExceptionHandler` unchanged — no new exceptions
- [ ] **No @MockBean of `RecipeServiceImpl`** in any new IT without checking interfaces — if any IT mocks `RecipeUpdateService`, it MUST also `@MockBean RecipeQueryService` and `RecipeSubstitutionRecorder` (round-6 gotcha — recipe service is a 3-interface impl; quick check: `grep "implements" src/main/java/.../RecipeServiceImpl.java` before adding `@MockBean`)
- [ ] No new migrations
- [ ] No regression on 01a/01b/01c/01d/01e/01f tests
- [ ] No `pom.xml` dependency adds
- [ ] No nutrition / household / provisions / auth / preference module file touched (the recipe-01g bridge imports `com.example.mealprep.nutrition.spi.RecipeNutritionWriter` and `RecipeNutritionResultDto` — those are nutrition-side public interfaces, which recipe is allowed to consume per the SPI pattern)

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/recipe/api/controller/RecipeAdminController.java
NEW   src/main/java/com/example/mealprep/recipe/api/dto/RunArchiveScanResultDto.java
NEW   src/main/java/com/example/mealprep/recipe/domain/service/internal/ArchiveEligibilityScanner.java
NEW   src/main/java/com/example/mealprep/recipe/spi/internal/RecipeNutritionWriterImpl.java         (the bridge — deferred from recipe-01f)

MOD   src/main/java/com/example/mealprep/recipe/api/controller/RecipesController.java               (append POST /{id}/promote, /demote, /archive, /unarchive handlers)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/RecipeUpdateService.java             (the interface already declares the methods per LLD line 562-577; verify; add markUsedInPlan if missing)
MOD   src/main/java/com/example/mealprep/recipe/domain/service/internal/RecipeServiceImpl.java      (implement promoteToUserCatalogue, demoteToSystemCatalogue, archive, unarchive, markUsedInPlan)
MOD   src/main/java/com/example/mealprep/recipe/domain/repository/RecipeRepository.java             (add findArchiveEligibleSystemRecipes(Instant cutoff, Pageable), markArchived(Collection<UUID>, Instant), touchLastUsedInPlan(Collection<UUID>, Instant))
MOD   src/main/java/com/example/mealprep/recipe/RecipeModule.java                                   (optional — verify the facade already re-exports the methods)

MOD   src/main/resources/openapi/paths/recipe.yaml      (append 5 new path-items; do NOT touch existing)
MOD   src/main/resources/openapi/schemas/recipe.yaml    (append `RunArchiveScanResultDto`)
MOD   src/main/resources/openapi/openapi.yaml           (5 path entries + 1 schema entry under `# recipe` block)
MOD   src/main/resources/application.yml                (add `mealprep.recipe.archive.cron: 0 30 3 * * *` default if not present)

NEW   src/test/java/com/example/mealprep/recipe/PromoteDemoteFlowIT.java                   (HTTP: promote 200/422/404/401; demote 204/422/404; ownership; event publication assertions)
NEW   src/test/java/com/example/mealprep/recipe/ArchiveFlowIT.java                         (HTTP: archive 204/422/404/401; unarchive 204; idempotency; ArchiveScanner end-to-end with cutoff fixture; admin trigger 200)
NEW   src/test/java/com/example/mealprep/recipe/ArchiveEligibilityScannerTest.java         (unit: batching math; cutoff arithmetic; empty-eligibility no-op; per-batch event publication)
NEW   src/test/java/com/example/mealprep/recipe/MarkUsedInPlanTest.java                    (empty list no-op; multi-ID bulk update; unknown ID tolerant)
NEW   src/test/java/com/example/mealprep/recipe/RecipeNutritionWriterImplIT.java           (cross-module wire: full context loads with both modules; @Component wires; Noop defers; end-to-end RecipeUpdatedEvent → nutrition listener → RecipeWriteApi.updateNutritionStatus → row's nutrition_status updated)
MOD   src/test/java/com/example/mealprep/recipe/testdata/RecipeTestData.java               (append builders for RunArchiveScanResultDto, ArchiveCause-bearing event fixtures)
```

**Files this ticket does NOT modify** (cross-cutting; sibling round-7 tickets running in parallel must not collide):

- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — no new cross-cutting exception.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module rule lives in `RecipeBoundaryTest` (unchanged).
- Other modules' `paths/*.yaml`, `schemas/*.yaml`, `<module>ExceptionHandler.java`, migrations, entities — none touched.
- The nutrition module (any production file) — **explicitly not modified**. Recipe-01g consumes `nutrition.spi.RecipeNutritionWriter` and `RecipeNutritionResultDto` via the existing public surface; no nutrition-side change.
- The household, provisions, auth, preference modules — none touched.
- 01a/01b/01c/01d/01e/01f's existing tests — none modified; only `RecipeTestData.java` gets new builder methods.

## Dependencies

- **Hard dependency**: `recipe-01a` (merged) — `Recipe`, `RecipeRepository`, `Catalogue` enum, `RecipeCatalogueViolationException`, `RecipeNotFoundException`, `RecipeMapper`, `RecipeServiceImpl`, `archived_at`/`last_used_in_plan_at`/`catalogue`/`deleted_at` columns + indexes. `RecipePromotedEvent`, `RecipeArchivedEvent`, `ArchiveCause` enum (from 01a or 01f).
- **Hard dependency**: `recipe-01b`/`01c`/`01d`/`01e` (merged) — pattern reuse only.
- **Hard dependency**: `recipe-01f` (merged) — `RecipeWriteApi.updateNutritionStatus`, `NutritionStatus` enum, `RecipeEvolvedEvent`, the SPI Noop pattern (now deferred-to). **The bridge commit specifically depends on recipe-01f's deferred bullet** ("RecipeNutritionWriterImpl deferred to follow-up commit per parallel-safety").
- **Hard dependency**: `nutrition-01f` (merged) — `nutrition.spi.RecipeNutritionWriter` interface, `RecipeNutritionResultDto`, `NoopRecipeNutritionWriterConfiguration` (the Noop that the bridge displaces).
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`, `SessionAuthenticationFilter`.
- **Hard dependency**: `refactor-01-split-merge-zones` (merged) — per-module YAML / advice / boundary-test layout.
- **No cross-module SPI coupling new in this ticket** — the bridge **consumes** an existing cross-module SPI (already shipped); doesn't define a new one.
- **Sibling tickets running in parallel** (Wave 2 round 7): `nutrition-01g`, `provisions-01g`. None should touch any recipe file or the cross-cutting files listed above. Only collision point is the entry `openapi.yaml`; this ticket appends in the `# recipe` block, sibling tickets append in their own module's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on the agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean (mandatory; not optional)
- [ ] CI green on the PR (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] **End-to-end SPI bridge IT passes**: `RecipeUpdatedEvent` → nutrition's listener → `RecipeNutritionWriter.writeNutritionPerServing` → recipe's `RecipeWriteApi.updateNutritionStatus` → row's `nutrition_status` and `nutrition_per_serving` columns updated; verified via JdbcTemplate
- [ ] `RecipeNutritionWriterImpl` wires (NOT the Noop) in the full-context IT
- [ ] `@ConditionalOnClass(name = "...")` is the **string-form** (NOT class-literal) — round-5 bug-1 avoidance
- [ ] `ArchiveEligibilityScanner` `@Scheduled` cron configurable via `mealprep.recipe.archive.cron` (verified via test-property override)
- [ ] `RecipeExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` (untouched in this ticket; verify no accidental edit)
- [ ] OpenAPI YAML description strings containing `,` `:` `'` are single-quoted (round-4 lesson)
- [ ] No `pom.xml` dependency adds
- [ ] No nutrition / household / provisions / auth / preference module file touched (recipe consumes nutrition.spi via imports only)

## What's NOT in scope

- Recipe embeddings + HNSW index → **recipe-01h**.
- Recipe search → **recipe-01i**.
- Cross-module helpers (`getIngredientMappingKeys`, `getRecipeIngredientLines`) → **recipe-01j**.
- AI tag inference → **recipe-01k**.
- `softDelete` flow (sets `deleted_at`) — already covered by 01c's DELETE endpoint; if missing, deferred to a follow-up ticket.
- `revertToVersion` — shipped in recipe-01c.
- Admin role gate on `/admin/run-archive-scan` — open to authenticated callers; lock down when role enum gains `ADMIN`.
- Async / job-queue archive scan — synchronous v1; the scan completes in <1s at v1 scale.
- Multi-user household promotion semantics ("copy to my library") — deferred until the household LLD covers it.
- The cook listener that calls `markUsedInPlan` lives in provisions-01g (or in a future planner module); 01g just exposes the method.

Squash-merge with: `feat(recipe): 01g — promote/demote/archive/unarchive + ArchiveEligibilityScanner @Scheduled + markUsedInPlan + RecipeNutritionWriterImpl SPI bridge`
