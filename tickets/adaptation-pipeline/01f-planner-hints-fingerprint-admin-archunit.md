# Ticket: adaptation-pipeline — 01f Planner-Hint Emitter + Fingerprint Refresher + Admin Controllers + Expiry Sweep + ArchUnit Boundary

## Summary

Close out the adaptation-pipeline module with the remaining helpers and the architectural boundary test. Per [lld/adaptation-pipeline.md §FingerprintRefresher (lines 195-212)](../../lld/adaptation-pipeline.md), [§PlannerHintEmitter (lines 214-241)](../../lld/adaptation-pipeline.md), [§AdaptationAdminController + AdapterRunHistoryController (lines 597-618)](../../lld/adaptation-pipeline.md), [§Expiry sweep (LLD line 155)](../../lld/adaptation-pipeline.md), [§ModuleBoundaryArchTest (line 937)](../../lld/adaptation-pipeline.md).

Ships:

- **`emitPlannerHint(PlannerHintRequest, UUID actorUserId)`** body on `AdaptationServiceImpl` — persists a `PlannerHintRecord`, publishes `PlannerHintEmittedEvent`, invalidates any prior-version hint via the `@Modifying` query from 01a.
- **`PlannerHintEmitter`** helper at `domain/service/internal/` — encapsulates the emit + auto-invalidate flow. Called by the worker pipeline on new-version writes (LLD line 769) AND by the public `emitPlannerHint` for planner-noticed hints.
- **`FingerprintRefresher`** at `domain/service/internal/` — derives + UPSERTs into `adaptation_fingerprints` on branch-creation events, then pushes through `RecipeWriteApi.updateCharacterFingerprint`. v1 derivation runs **inline within the adaptation prompt** per LLD §Decisions §3 (line 964) — the `RecipeAdaptationResponse` from 01e carries the fingerprint when `classification = BRANCH`. v2 splits into a sibling `RecipeFingerprintTask` (deferred per LLD line 946).
- **`AdaptationAdminController`** at `api/controller/` — admin/debug surface per [LLD lines 599-608](../../lld/adaptation-pipeline.md). Seven endpoints; `ROLE_ADMIN` gated.
- **`AdapterRunHistoryController`** at `api/controller/` — quality-dashboard data feed per [LLD lines 613-618](../../lld/adaptation-pipeline.md). Two endpoints; admin-gated.
- **`sweepExpiredPendingChanges()`** body on `AdaptationServiceImpl` — flips PENDING rows with `expires_at < now()` to `EXPIRED`. Called by `@Scheduled` cron `0 0 4 * * *` (per `config.pendingExpirySweepCron()`).
- **`@Scheduled` registration** for the expiry sweep + an `AdminController` POST endpoint `/admin/sweep-expired-pending` for ad-hoc invocation per LLD line 604.
- **`@Scheduled` registration** for batch-orchestrator was shipped in 01d; 01f just confirms the cron registration's lifecycle.
- **`AdaptationQueryService` query method bodies** that 01b stubbed — all twelve methods now have real impls reading from repositories.
- **`AdaptationModule.java` facade additions** if any public methods need re-exporting (likely none beyond 01b).
- **`ModuleBoundaryArchTest`** ArchUnit class per LLD line 937 — asserts no package outside `adaptation.*` imports `adaptation.domain.repository.*` or `adaptation.domain.entity.*`; `RecipeWriteApi` is the only `recipe.*` symbol injected here; `AiTask` SPI is the only `ai.*` symbol injected here.
- **OpenAPI excerpts** for the admin + run-history endpoints; appended to `paths/adaptation.yaml` and `schemas/adaptation.yaml` from 01d.

01f closes the module. After 01f merges, the adaptation-pipeline is feature-complete for v1; remaining LLD §Out of Scope items (real prompt content, Anthropic Batches API, RecipeFingerprintTask v2, etc.) are tracked separately.

## Behavioural spec

### `emitPlannerHint` + `PlannerHintEmitter`

1. **`emitPlannerHint(PlannerHintRequest request, UUID actorUserId)`** on `AdaptationServiceImpl`. `@Transactional` (REQUIRED). Validation `@Valid @ValidPlannerHint` from 01d (annotation already on `PlannerHintRequest` class).

2. **Auto-invalidation**: per [LLD line 769](../../lld/adaptation-pipeline.md) the pipeline invalidates old hints on new-version writes; per [LLD line 240](../../lld/adaptation-pipeline.md) "On new-version writes, `PlannerHintEmitter` invalidates hints attached to the prior version." 01f wires:
   - When `emitPlannerHint` is called for a new `versionId` for a recipe, look up any active hint with the same `recipe_id` but a different `version_id` AND the same `hint_type` → invalidate (call `plannerHintRecordRepository.invalidateForOldVersion(oldVersionId, now())`).
   - **However**: the worker pipeline (01c) also needs to invalidate hints when a new version is written via `RecipeWriteApi`. That invalidation happens at the END of `processJob` step 10 (LLD line 769). 01f extends 01c's `processJob`: after `RecipeWriteApi.saveAdaptedVersion` / `saveAdaptedBranch` succeeds, call `plannerHintEmitter.invalidateHintsForOldVersion(oldVersionId)`.

3. **`PlannerHintEmitter`** at `adaptation/domain/service/internal/`:
   ```java
   @Component
   public class PlannerHintEmitter {
     private final PlannerHintRecordRepository repo;
     private final ApplicationEventPublisher events;

     @Transactional
     public PlannerHintRecord emit(PlannerHintRequest request, UUID emittedByJobId) {
       invalidateHintsForOldVersion(/* derived from request — see below */);
       PlannerHintRecord record = PlannerHintRecord.builder()
           .id(UUID.randomUUID())
           .recipeId(request.recipeId())
           .versionId(request.versionId())
           .branchId(request.branchId())
           .hintType(request.hintType())
           .description(request.description())
           .payload(request.payload())
           .severity(request.severity())
           .emittedByJobId(emittedByJobId)
           .traceId(request.traceId())
           .build();
       repo.save(record);
       events.publishEvent(new PlannerHintEmittedEvent(
           record.getId(), record.getRecipeId(), record.getVersionId(),
           record.getHintType(), record.getSeverity(),
           record.getTraceId(), Instant.now()));
       return record;
     }

     @Transactional
     public int invalidateHintsForOldVersion(UUID oldVersionId) {
       return repo.invalidateForOldVersion(oldVersionId, Instant.now());
     }
   }
   ```

4. **`emittedByJobId` for planner-noticed hints** (LLD line 509 — "Public so peer modules (notably the planner) can emit a hint they noticed."): when the planner calls `emitPlannerHint` outside any pipeline job, `emittedByJobId` is null. The DB column allows null (LLD line 230 — `emitted_by_job_id uuid REFERENCES adaptation_jobs(id) ON DELETE SET NULL`).

### `FingerprintRefresher`

5. **`FingerprintRefresher`** at `adaptation/domain/service/internal/`. Called from 01c's `processJob` when `classification = BRANCH`:
   - **v1 behaviour**: pull the fingerprint from `RecipeAdaptationResponse` — the LLD §Decisions §3 (line 964) says "Fingerprint derivation runs inline within the adaptation prompt" — so the AI's response already carries the fingerprint when classification = BRANCH. **No second AI call.**
   - **Persist**: UPSERT into `adaptation_fingerprints` keyed on `(recipeId, branchId)` UNIQUE. Body hash computed by hashing the new version's normalised body (SHA-256).
   - **Push through catalogue**: `recipeWriteApi.updateCharacterFingerprint(versionId, fingerprintDto)` per LLD line 596.

6. **Idempotency via `body_hash`**: if the body hash already matches an existing fingerprint row (same body, same hash), skip the derivation + WriteApi call. The `findByBodyHash` repo method (from 01a) supports this.

7. **`FingerprintRefresher` shape**:
   ```java
   @Component
   public class FingerprintRefresher {
     private final AdaptationFingerprintRepository repo;
     private final RecipeWriteApi recipeWriteApi;

     @Transactional
     public AdaptationFingerprint refreshOnBranch(UUID recipeId, UUID branchId, UUID versionId,
                                                    JsonNode fingerprintFromResponse, String normalisedBody,
                                                    UUID derivedByJobId) {
       String bodyHash = sha256(normalisedBody);
       Optional<AdaptationFingerprint> existing = repo.findByBodyHash(bodyHash);
       if (existing.isPresent()) {
         return existing.get();                                                  // idempotent skip
       }
       AdaptationFingerprint record = AdaptationFingerprint.builder()
           .id(UUID.randomUUID())
           .recipeId(recipeId).branchId(branchId).versionId(versionId)
           .bodyHash(bodyHash)
           .fingerprint(fingerprintFromResponse)
           .derivedByJobId(derivedByJobId)
           .derivedAt(Instant.now())
           .build();
       repo.save(record);
       recipeWriteApi.updateCharacterFingerprint(versionId, mapToCharacterFingerprintDto(fingerprintFromResponse));
       return record;
     }
   }
   ```

8. **`mapToCharacterFingerprintDto(JsonNode)`** — the LLD line 279 notes a `CharacterFingerprintDocument` typed record-tree that lives in the recipe module's DTO surface. 01f converts via Jackson `ObjectMapper.treeToValue(node, CharacterFingerprintDto.class)`. **Agent verifies the actual class name in the recipe module.**

### `AdaptationQueryService` query method impls

9. All twelve methods on `AdaptationQueryService` (declared in 01b, stubbed as UOE on `AdaptationServiceImpl`) get real bodies. All `@Transactional(readOnly = true)`:
   - `listPendingForUser(UUID userId)` — already wired in 01d for the controller; if 01d didn't ship it (out of scope check there), 01f wires; expected 01d-shipped.
   - `listPendingHistoryForRecipe(UUID recipeId)` — `pendingChangeRepository.findByRecipeIdOrderByCreatedAtDesc(recipeId, Pageable.unpaged())` → mapper → list.
   - `getPendingChange(UUID id)` — `findById` → mapper → Optional.
   - `getJobsForRecipe(UUID recipeId, Pageable p)` — `adaptationJobRepository.findByRecipeIdOrderByEnqueuedAtDesc(recipeId, p)` → mapper.
   - `getActiveJobsForUser(UUID userId, Pageable p)` — uses LLD's `findByUserIdAndStatusOrderByEnqueuedAtDesc` (multiple status filter — agent extends the repo if needed: `findByUserIdAndStatusInOrderByEnqueuedAtDesc(userId, Set.of(PENDING, RUNNING), p)`).
   - `getTracesForRecipe(UUID recipeId, Pageable p)` — `adaptationTraceRepository.findByRecipeIdOrderByCreatedAtDesc(recipeId, p)`.
   - `getTracesForPromptVersion(String name, String version, Pageable p)` — `adaptationTraceRepository.findByPromptTemplateNameAndPromptTemplateVersionOrderByCreatedAtDesc(name, version, p)`.
   - `getTraceForJob(UUID jobId)` — `findByJobId(jobId)` → mapper → Optional.
   - `getActiveHintsForVersion(UUID versionId)` — `plannerHintRecordRepository.findActiveForVersion(versionId)` → mapper → list.
   - `getActiveHintsForVersions(List<UUID> versionIds)` — N+1 protection: single query `WHERE version_id IN (:ids) AND invalidated_at IS NULL`; agent adds repo method `findActiveForVersions(List<UUID>)`; group by versionId into a Map.
   - `getMostRecentResultForRecipe(UUID recipeId)` — query latest DONE job by recipeId; load its trace; map trace → `AdaptationResultDto`. Empty Optional if no DONE job.

### `sweepExpiredPendingChanges()` impl

10. Replace the `return 0;` stub from 01b:
    ```java
    @Override
    @Transactional
    public int sweepExpiredPendingChanges() {
      List<PendingChange> expired = pendingChangeRepository.findExpiredPending(Instant.now(), Pageable.ofSize(500));
      Instant now = Instant.now();
      for (PendingChange pc : expired) {
        pc.setStatus(PendingChangeStatus.EXPIRED);
        pc.setResolvedAt(now);
      }
      // Hibernate flushes on tx commit; no explicit saveAll needed.
      return expired.size();
    }
    ```
    Per-run cap of 500 to keep the tx short; if more remain, the next cron tick picks them up. **Worth user review** — cap value.

11. **`@Scheduled` cron registration** for the sweep:
    ```java
    @Component
    @RequiredArgsConstructor
    public class PendingChangeExpirySweepScheduler {
      private final AdaptationService adaptationService;

      @Scheduled(cron = "${mealprep.adaptation.pending-expiry-sweep-cron:0 0 4 * * *}")
      public void sweep() {
        adaptationService.sweepExpiredPendingChanges();
      }
    }
    ```

### `AdaptationAdminController`

12. Per [LLD §AdaptationAdminController lines 599-608](../../lld/adaptation-pipeline.md), seven endpoints under `/api/v1/adaptation/...`:
    | Method | Path | Body | Response | Status |
    |---|---|---|---|---|
    | GET    | `/api/v1/adaptation/jobs/{jobId}`                        | — | `AdaptationJobDto` | 200 / 404 |
    | GET    | `/api/v1/adaptation/jobs/{jobId}/trace`                  | — | `AdaptationTraceDto` | 200 / 404 |
    | GET    | `/api/v1/adaptation/recipes/{recipeId}/jobs?page=&size=` | — | `Page<AdaptationJobDto>` | 200 |
    | GET    | `/api/v1/adaptation/recipes/{recipeId}/traces?page=&size=` | — | `Page<AdaptationTraceDto>` | 200 |
    | POST   | `/api/v1/adaptation/admin/sweep-expired-pending`         | — | `{ touched: int }` | 200 |
    | POST   | `/api/v1/adaptation/admin/retry-failed-job`              | `{ jobId }` | `AdaptationJobDto` | 200 / 404 / 409 |
    | GET    | `/api/v1/adaptation/admin/prompt-versions/{name}/{version}/traces?page=&size=` | — | `Page<AdaptationTraceDto>` | 200 |

13. **`POST /admin/retry-failed-job`** per LLD line 609:
    - Resolve job by id; 404 if not found.
    - Status check: must be `FAILED` → else 409.
    - Build a fresh `AdaptationJob` by copying `inputs`, `recipe_id`, `user_id`, `catalogue`, `source`, `priority`, `approval_policy`; set `parent_decision_id = oldJob.id` for audit chain; `status = PENDING`.
    - Insert; publish `JobReadyEvent` (or call `BatchJobOrchestrator` for BATCH-priority).
    - Return the new job's DTO.

14. **`@PreAuthorize("hasRole('ROLE_ADMIN')")`** on every controller method per LLD line 578 "admin/debug endpoints are gated to `ROLE_ADMIN`."

### `AdapterRunHistoryController`

15. Per [LLD lines 613-618](../../lld/adaptation-pipeline.md), two endpoints:
    | Method | Path | Body | Response | Status |
    |---|---|---|---|---|
    | GET    | `/api/v1/adaptation/run-history?source=&from=&to=&page=&size=` | — | `Page<AdaptationJobDto>` | 200 |
    | GET    | `/api/v1/adaptation/run-history/by-prompt-version?name=&version=&page=&size=` | — | `Page<AdaptationTraceDto>` | 200 |

16. Repository queries: `findBySourceAndEnqueuedAtBetween` (new repo method) for the first; `findByPromptTemplateNameAndPromptTemplateVersionOrderByCreatedAtDesc` (already from 01a) for the second.

17. **`@PreAuthorize("hasRole('ROLE_ADMIN')")`** per LLD line 578.

### ArchUnit `ModuleBoundaryArchTest`

18. New class `src/test/java/com/example/mealprep/adaptation/ModuleBoundaryArchTest.java`. Verbatim from [LLD line 937](../../lld/adaptation-pipeline.md):

    ```java
    @AnalyzeClasses(packages = "com.example.mealprep", importOptions = ImportOption.DoNotIncludeTests.class)
    class ModuleBoundaryArchTest {

      @ArchTest
      static final ArchRule no_outside_imports_of_adaptation_repositories =
          ArchRuleDefinition.noClasses()
              .that().resideOutsideOfPackage("com.example.mealprep.adaptation..")
              .should().dependOnClassesThat().resideInAPackage("com.example.mealprep.adaptation.domain.repository..");

      @ArchTest
      static final ArchRule no_outside_imports_of_adaptation_entities =
          ArchRuleDefinition.noClasses()
              .that().resideOutsideOfPackage("com.example.mealprep.adaptation..")
              .should().dependOnClassesThat().resideInAPackage("com.example.mealprep.adaptation.domain.entity..");

      @ArchTest
      static final ArchRule recipe_writeapi_is_only_recipe_dep =
          ArchRuleDefinition.classes()
              .that().resideInAPackage("com.example.mealprep.adaptation..")
              .should().onlyDependOnClassesThat().resideOutsideOfPackages("com.example.mealprep.recipe..")
                       .orShould().onlyDependOnClassesThat().resideInAPackage("com.example.mealprep.recipe.spi..")
                       .orShould().onlyDependOnClassesThat().resideInAPackage("com.example.mealprep.recipe.api.dto..");
              // ... refined to allow specific public types like RecipeQueryService + Catalogue enum

      @ArchTest
      static final ArchRule aitask_spi_is_only_ai_dep =
          ArchRuleDefinition.classes()
              .that().resideInAPackage("com.example.mealprep.adaptation..")
              .should().onlyDependOnClassesThat().resideOutsideOfPackages("com.example.mealprep.ai..")
                       .orShould().onlyDependOnClassesThat().resideInAPackage("com.example.mealprep.ai.spi..")
                       .orShould().onlyDependOnClassesThat().resideInAPackage("com.example.mealprep.ai.exception..");
    }
    ```

19. **The exact allowed-imports lists need calibration during impl** — the agent runs the test, sees the failures, and either:
    - Adds the specific peer-module classes the adaptation pipeline legitimately imports (e.g. `Catalogue` from `recipe.api.dto`, `PreferenceQueryService` from `preference.api.service`, `NutritionFloorGateService` from `nutrition.api.service`) to the allow-list with named ArchUnit conditions.
    - Refactors any import that shouldn't exist.

    The LLD line 937 is loose ("`RecipeWriteApi` is the only `recipe.*` symbol injected here") — strict interpretation: only `RecipeWriteApi` + the command records under `recipe.spi.*`. Reality: the pipeline also reads recipe state via `RecipeQueryService` (an API service, not the SPI). **Worth user review.** Pragmatic interpretation: allow `recipe.api.*` + `recipe.spi.*`; disallow `recipe.domain.*`.

### OpenAPI updates

20. Append to `src/main/resources/openapi/paths/adaptation.yaml` (the file from 01d) — nine new path-items for the seven admin endpoints + two run-history endpoints.

21. Append to `src/main/resources/openapi/schemas/adaptation.yaml` (the file from 01d) — `AdaptationJobDto`, `AdaptationJobDtoPage`, `AdaptationTraceDto`, `AdaptationTraceDtoPage`, `RetryFailedJobRequest`, `SweepExpiredPendingResponse`. **Flat Page<T>**, **inline nullable**, **single-quoted descriptions** per the project conventions.

22. Append to entry `src/main/resources/openapi/openapi.yaml` — the nine new path refs + the new schemas.

## Database

**Optional**: agent MAY add a repository method `findActiveForVersions(List<UUID> versionIds)` to `PlannerHintRecordRepository` if the 01a-shipped repo lacks it (it does — only `findActiveForVersion(UUID versionId)` ships in 01a). This is a code-only change, no migration.

**Optional**: agent MAY add `findBySourceAndEnqueuedAtBetween(JobSource source, Instant from, Instant to, Pageable p)` to `AdaptationJobRepository`. Same — code-only.

**Optional**: agent MAY add `findByUserIdAndStatusInOrderByEnqueuedAtDesc(UUID userId, Set<JobStatus> statuses, Pageable p)` to `AdaptationJobRepository`. Same.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/adaptation.yaml`

(Nine new path-items below the 01d-shipped block.)

```yaml
adaptationJob:
  get:
    tags: [Adaptation]
    operationId: getAdaptationJob
    summary: 'Admin: fetch a job by id.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: jobId
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200': { description: 'Job', content: { application/json: { schema: { $ref: '../schemas/adaptation.yaml#/AdaptationJobDto' } } } }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '403': { description: 'Not admin', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Job not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
# ... 8 more path-items: adaptationJobTrace, adaptationRecipeJobs, adaptationRecipeTraces,
#     adaptationAdminSweepExpired (POST), adaptationAdminRetryFailedJob (POST),
#     adaptationAdminPromptVersionTraces, adaptationRunHistory, adaptationRunHistoryByPromptVersion
```

(All YAML descriptions with `,` `:` `'` single-quoted; `additionalProperties: true` on every Page<T> shape; inline `nullable: true`.)

### Append to entry `openapi.yaml`

Nine path refs + the new schemas under `components.schemas:`.

## Edge-case checklist

- [ ] `emitPlannerHint` happy path → row inserted; `PlannerHintEmittedEvent` published `AFTER_COMMIT`
- [ ] `emitPlannerHint` invalidates an existing active hint for the same `recipe_id` + `hint_type` on a different `versionId` → the old row's `invalidated_at` is non-null after the call
- [ ] `emitPlannerHint` with null `emittedByJobId` (planner-noticed hint) → row inserted; FK SET NULL behaviour on `adaptation_jobs` deletion verified
- [ ] `@ValidPlannerHint` failure on `PREP_LEAD_TIME` without `payload.lead_time_hours` → 400 surfaces
- [ ] `FingerprintRefresher.refreshOnBranch` happy path → row UPSERTed into `adaptation_fingerprints`; `RecipeWriteApi.updateCharacterFingerprint` called
- [ ] `FingerprintRefresher.refreshOnBranch` second call for the same body hash → idempotent skip; no WriteApi call; return the existing row
- [ ] `FingerprintRefresher.refreshOnBranch` UPSERT conflict on `(recipe_id, branch_id)` UNIQUE → Hibernate `merge` updates instead of insert
- [ ] `AdaptationQueryService.listPendingHistoryForRecipe` returns all PENDING + resolved rows for a recipe, latest first
- [ ] `AdaptationQueryService.getJobsForRecipe(recipeId, Pageable.ofSize(20))` returns a Page sorted by `enqueued_at DESC`
- [ ] `AdaptationQueryService.getTraceForJob(jobId)` returns Optional.empty for a job without a trace (failed-pre-AI)
- [ ] `AdaptationQueryService.getActiveHintsForVersions(versionIds)` returns a Map<UUID, List> with one entry per version that has active hints; versions with no hints absent from the map (no empty-list entries)
- [ ] `AdaptationQueryService.getMostRecentResultForRecipe` for a recipe with no DONE jobs → Optional.empty
- [ ] `AdaptationQueryService.getMostRecentResultForRecipe` for a recipe with 3 DONE jobs → returns the most-recent's `AdaptationResultDto`
- [ ] `sweepExpiredPendingChanges` cron at 4am → flips all PENDING rows with `expires_at < now()` to EXPIRED; sets `resolved_at`; returns count
- [ ] `sweepExpiredPendingChanges` with no expired rows → returns 0; no DB writes
- [ ] `sweepExpiredPendingChanges` with 600 expired rows → first run flips 500; second cron tick flips the remaining 100
- [ ] `POST /admin/sweep-expired-pending` for an authenticated non-admin → 403
- [ ] `POST /admin/sweep-expired-pending` for an admin → 200 with `{ touched: <int> }`
- [ ] `POST /admin/retry-failed-job` for a FAILED job → new job inserted with `parent_decision_id = oldJob.id`; new job in PENDING state; returns the new job's DTO
- [ ] `POST /admin/retry-failed-job` for a DONE job → 409
- [ ] `POST /admin/retry-failed-job` for a non-existent job → 404
- [ ] `GET /admin/prompt-versions/{name}/{version}/traces` returns paginated traces filtered by both fields
- [ ] `GET /run-history?source=FEEDBACK&from=2026-01-01T00:00:00Z&to=2026-12-31T00:00:00Z` returns paginated jobs filtered by source + time window
- [ ] All admin endpoints return 401 for unauthenticated; 403 for authenticated-but-not-admin; 200 for admin
- [ ] **ArchUnit**: `no_outside_imports_of_adaptation_repositories` — no class outside `adaptation.*` imports anything in `adaptation.domain.repository.*`
- [ ] **ArchUnit**: `no_outside_imports_of_adaptation_entities` — no class outside `adaptation.*` imports anything in `adaptation.domain.entity.*`
- [ ] **ArchUnit**: `aitask_spi_is_only_ai_dep` — adaptation only imports from `ai.spi.*` + `ai.exception.*`; NOT from `ai.domain.*` or `ai.internal.*`
- [ ] **ArchUnit**: `recipe_writeapi_is_only_recipe_dep` — adaptation only imports from `recipe.spi.*` + `recipe.api.*`; NOT from `recipe.domain.*` or `recipe.internal.*`. **NOTE**: legitimate imports include `RecipeQueryService` (from `recipe.api.service`) + `Catalogue` (from `recipe.api.dto`); ArchUnit rule's allowed-list reflects this.
- [ ] OpenAPI lint green; new admin schemas use flat Page<T> + inline nullable + single-quoted descriptions
- [ ] No regression on existing tests
- [ ] `pending-expiry-sweep-cron` config key resolves to `0 0 4 * * *` default; the `@Scheduled` annotation expression is parameterised so test runs can override

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java               (wire emitPlannerHint, sweepExpiredPendingChanges, all 12 AdaptationQueryService methods)

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/PlannerHintEmitter.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/FingerprintRefresher.java
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/PendingChangeExpirySweepScheduler.java   (@Scheduled cron)

NEW   src/main/java/com/example/mealprep/adaptation/api/controller/AdaptationAdminController.java
NEW   src/main/java/com/example/mealprep/adaptation/api/controller/AdapterRunHistoryController.java

NEW   src/main/java/com/example/mealprep/adaptation/api/dto/RetryFailedJobRequest.java                       (record { UUID jobId })
NEW   src/main/java/com/example/mealprep/adaptation/api/dto/SweepExpiredPendingResponse.java                  (record { int touched })

MOD   src/main/java/com/example/mealprep/adaptation/domain/repository/AdaptationJobRepository.java           (add findBySourceAndEnqueuedAtBetween, findByUserIdAndStatusInOrderByEnqueuedAtDesc)
MOD   src/main/java/com/example/mealprep/adaptation/domain/repository/PlannerHintRecordRepository.java       (add findActiveForVersions(List<UUID>))

MOD   src/main/resources/openapi/paths/adaptation.yaml                                                        (append 9 new path-items below 01d's block)
MOD   src/main/resources/openapi/schemas/adaptation.yaml                                                      (append AdaptationJobDto/Page, AdaptationTraceDto/Page, RetryFailedJobRequest, SweepExpiredPendingResponse)
MOD   src/main/resources/openapi/openapi.yaml                                                                  (append 9 path refs + new schema refs under `# adaptation` block)

NEW   src/test/java/com/example/mealprep/adaptation/PlannerHintEmitterTest.java
NEW   src/test/java/com/example/mealprep/adaptation/FingerprintRefresherTest.java
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationQueryServiceImplTest.java                       (all 12 methods)
NEW   src/test/java/com/example/mealprep/adaptation/PendingChangeExpirySweepIT.java                            (backdated PENDING rows → EXPIRED; in-window untouched)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationAdminControllerIT.java                          (MockMvc; ROLE_ADMIN gating; happy paths; retry-failed-job flow)
NEW   src/test/java/com/example/mealprep/adaptation/AdapterRunHistoryControllerIT.java                         (MockMvc; admin-gated; source + prompt-version filters)
NEW   src/test/java/com/example/mealprep/adaptation/PlannerHintInvalidationIT.java                            (new version V2 → V1's hints flip invalidated_at)
NEW   src/test/java/com/example/mealprep/adaptation/ModuleBoundaryArchTest.java                                (the 4 @ArchTest rules)
NEW   src/test/java/com/example/mealprep/adaptation/EventPublicationIT.java                                    (events fire once AFTER_COMMIT; failing listener doesn't roll back state — listener uses @TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW))
```

**Files this ticket does NOT touch**:

- Migrations (none added; only optional repo-method additions, code-only)
- 01a–01e files beyond the explicit MODs above
- Other modules' src/ — strictly contained
- Frontend / UI files — not in scope per LLD line 951

## Dependencies

- **Hard dependency**: `adaptation-pipeline-01e` (merged) — `NutritionalKnowledgeServiceImpl`, `RecipeAdaptationTask` real impl, `AdaptationContextAssembler`, prompt template stub. The block-and-prompt fallback is fully proven; 01f doesn't re-verify.
- **Hard dependency**: `adaptation-pipeline-01d` (merged) — `PendingChangesController` + listeners + batch orchestrator + custom validators.
- **Hard dependency**: `adaptation-pipeline-01c` (merged) — worker pipeline; 01f extends `processJob` end-of-flow to call `plannerHintEmitter.invalidateHintsForOldVersion(oldVersionId)` and `fingerprintRefresher.refreshOnBranch(...)` when applicable.
- **Hard dependency**: `adaptation-pipeline-01b` (merged) — `PlannerHintRequest` (with `@ValidPlannerHint`), `AdaptationJobDto`, `AdaptationTraceDto`, all DTOs.
- **Hard dependency**: `adaptation-pipeline-01a` (merged) — all schema + repositories.
- **Hard dependency**: `auth-01a` (merged) — `@PreAuthorize`, `ROLE_ADMIN`.
- **Hard dependency**: `recipe-01f` (merged) — `RecipeWriteApi.updateCharacterFingerprint`.
- **Soft dependency**: ArchUnit `@ArchTest` infrastructure — should already be in the project from earlier modules' ArchUnit usage.
- **No sibling-dependency**: 01f is the final ticket in the module.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit)
- [ ] All edge-case items above ticked
- [ ] **All four `@ArchTest` rules pass**; the allow-list of legitimate cross-module imports is documented in the test class's Javadoc
- [ ] **Per [decisions/0010 §round-7](../../../../ai-workflow/decisions/0010-wave2-round7-transactionaleventlistener-propagation.md)**: any `@TransactionalEventListener` listener in 01f (e.g. event-publication IT helpers) uses `@Transactional(REQUIRES_NEW)` or `NOT_SUPPORTED`
- [ ] **Per [agent-prompt-template line 256](../../../../ai-workflow/templates/agent-prompt-template.md)**: any new service method that writes audit + throws 4xx uses `@Transactional(noRollbackFor = ...)` — verify for `acceptPendingChange` (already in 01d) AND for `sweepExpiredPendingChanges` (currently doesn't throw; rule not needed)
- [ ] `sweepExpiredPendingChanges` cron expression matches `config.pendingExpirySweepCron()` default `0 0 4 * * *`
- [ ] OpenAPI lint green; flat Page<T>; inline nullable; single-quoted descriptions
- [ ] No regression on existing tests
- [ ] No pom.xml dependency adds
- [ ] No file outside the adaptation-pipeline module touched

Squash-merge with: `feat(adaptation): 01f — PlannerHintEmitter + FingerprintRefresher + AdaptationAdminController + AdapterRunHistoryController + expiry sweep + ArchUnit boundary test`

## What's NOT in scope

- **Real prompt content** for `recipe-adaptation.txt` — the stub from 01e ships.
- **Curated rows** in `adaptation_nutritional_knowledge`.
- **`RecipeFingerprintTask`** (v2 split) — LLD line 946.
- **Anthropic Batches API** integration — LLD line 794.
- **Per-prompt-version aggregate roll-up table** — LLD line 950.
- **Raw-trace retention sweep** (6-month cutoff on `raw_ai_response`) — LLD line 950.
- **Cross-recipe adaptation** — LLD line 953 open question.
- **Periodic fingerprint re-extraction for long-lived recipes** — LLD line 953 open question.
- **Per-user prompt variants**, **streaming**, **multi-turn tool-use** — LLD line 954.
- **Frontend / UI / API consumer concerns** — LLD line 951.
- **Conversational suggestion-box-alongside-diff flow** — LLD line 956.
- **Authentication** beyond `@PreAuthorize` — LLD line 955 owned by auth module.
