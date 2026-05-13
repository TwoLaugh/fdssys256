# Ticket: adaptation-pipeline — 01d Trigger Entries + Pending-Change Controllers + Listeners + Batch Orchestrator

## Summary

Wire the **four public trigger entry methods** on `AdaptationServiceImpl` (filling in what 01b stubbed as UOE), ship the **`PendingChangesController`** user surface (accept / reject / list), ship the **`@TransactionalEventListener` consumers** for Trigger 3 (data-model-change), ship the **`BatchJobOrchestrator`** `@Scheduled` job that processes BATCH-priority queue, and the **`@Scheduled` poll-fallback** for orphan PENDING jobs. Per [lld/adaptation-pipeline.md §Service Interfaces (lines 483-543)](../../lld/adaptation-pipeline.md), [§REST Controllers (lines 577-620)](../../lld/adaptation-pipeline.md), [§Events Consumed (lines 690-696)](../../lld/adaptation-pipeline.md), [§Trigger 3 (lines 788-794)](../../lld/adaptation-pipeline.md).

Ships:

- **Four trigger entry methods** on `AdaptationServiceImpl` (replacing the UOEs from 01b):
  - `enqueueImportJob(ImportJobRequest)` → inserts an `AdaptationJob(source = IMPORT, priority = ASYNC)`, publishes internal `JobReadyEvent`, returns `jobId`.
  - `enqueueFeedbackJob(FeedbackJobRequest)` → inserts `AdaptationJob(source = FEEDBACK, priority = SYNC)`, calls `processJob` synchronously (within the calling tx? — see Concurrency below), returns `AdaptationResultDto`.
  - `enqueueDataModelChangeJobs(DataModelJobRequest)` → inserts N `AdaptationJob(source = DATA_MODEL_CHANGE, priority = BATCH)` rows (one per affected recipe; `@Size(max = 5000)` cap per LLD line 334), returns list of job IDs.
  - `runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest)` → inserts `AdaptationJob(source = PLAN_TIME, priority = SYNC, approval_policy = PLAN_OVERLAY)`, calls `processJob` synchronously, returns `AdaptationResultDto`.
- **Pending-change lifecycle**:
  - `acceptPendingChange(UUID, AcceptPendingChangeRequest, UUID actorUserId)` — validates status + expiry + optimistic version, writes through `RecipeWriteApi.saveAdaptedVersion` (or `userEdits` variant), transitions to `ACCEPTED` / `MODIFIED`, publishes `PendingChangeAcceptedEvent`.
  - `rejectPendingChange(UUID, RejectPendingChangeRequest, UUID actorUserId)` — validates status, transitions to `REJECTED`, publishes `PendingChangeRejectedEvent`.
- **`PendingChangesController`** at `api/controller/` — five endpoints under `/api/v1/adaptation/pending-changes/...` per [LLD §PendingChangesController lines 585-595](../../lld/adaptation-pipeline.md).
- **Trigger 3 event listeners** under `domain/service/internal/` — five `@TransactionalEventListener(AFTER_COMMIT)` methods consuming `RecipeCreatedEvent`, `PreferenceChangedEvent`, `HardConstraintsChangedEvent`, `NutritionTargetsChangedEvent`, `ProvisionsBudgetChangedEvent`.
- **`BatchJobOrchestrator`** at `domain/service/internal/` — `@Scheduled` cron `0 30 4 * * *` (per `config.batchOrchestratorCron()`) processing BATCH-priority queue serially.
- **`@Scheduled` orphan-poll-fallback** — re-picks orphan PENDING jobs older than 5 minutes after JVM restart (LLD line 772).
- **Custom validators**: `@ValidPlannerHint` (asserts `payload` shape matches `hintType`), `@ValidRecipeDiff` (asserts diff still references `baseVersionId`; ingredient mapping keys exist).

**01d does NOT update `emitPlannerHint` or `sweepExpiredPendingChanges`** — both are 01f's territory (planner hint emitter + expiry sweep both live with the LLD's `PlannerHintEmitter` + scheduled-sweep components).

## Critical: `@TransactionalEventListener` propagation rule

**The five Trigger 3 listeners MUST follow the [decisions/0010 §round-7 lesson](../../../../ai-workflow/decisions/0010-wave2-round7-transactionaleventlistener-propagation.md)**:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void onPreferenceChanged(PreferenceChangedEvent event) {
  Set<UUID> affected = preferenceQueryService.findRecipesAffectedByPreferenceChange(event.userId(), event.changedField());
  if (affected.isEmpty()) return;
  enqueueDataModelChangeJobs(new DataModelJobRequest(
      event.userId(), DataModelChangeType.PREFERENCE, buildSummary(event), affected, event.traceId()));
}
```

**Why `REQUIRES_NEW` not default `REQUIRED`**:

1. `AFTER_COMMIT` means the publisher's tx has committed and closed — the listener body has no active tx.
2. The listener body reads JPA entities (`preferenceQueryService.findRecipesAffectedByPreferenceChange`) and writes (`enqueueDataModelChangeJobs` inserts N rows) — both need an active tx.
3. `@Transactional(REQUIRED)` (the default) is REJECTED at Spring context-load with: `"@TransactionalEventListener method must not be annotated with @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED"`. **This fail-fast blocks every IT across the project** — not just the one with the listener. **Round-7 burned a parent agent on this rule.**
4. `@Transactional(SUPPORTS)` is also REJECTED — same error.
5. Only `REQUIRES_NEW` and `NOT_SUPPORTED` are allowed. Since the listener body does writes, `REQUIRES_NEW` is correct.

**Every one of the five listeners follows this pattern.** If any listener body becomes empty (no JPA work needed), the annotations drop to just `@TransactionalEventListener` — but only after verifying nothing inside makes a JPA call.

## Critical: `@Transactional(noRollbackFor=...)` rule for budget gate

**The 3-per-week budget gate writes a budget-exceeded audit log row before throwing 4xx — `@Transactional(noRollbackFor = ...)` ensures the audit commits.**

Wait — re-reading the LLD: **the 3-per-week budget is enforced at SURFACE TIME (rank-at-read), not at write time** per [LLD line 155-156](../../lld/adaptation-pipeline.md):

> "The 3-per-week user budget is enforced at **surface time** — the list endpoint caps + ranks; the row exists, it just isn't surfaced beyond rank 3."

And per [LLD §`AdaptationQueryService.listPendingForUser` line 522](../../lld/adaptation-pipeline.md):

> "Top-3 per HLD budget rule"

And per the parent's brief: **"3-per-week adaptation budget rank-at-read, blocks-and-prompts on AI unavailable"** AND **"3-per-week budget is enforced rank-at-read (not pre-check): query the audit-log on each request, count, reject if ≥3 in trailing 7 days. Locked decision."**

These two statements are **inconsistent**:

- **LLD**: budget enforced at list-endpoint time; cap to top-3 by `(impactScore × confidence)`; rows still exist; the surface just truncates. **No rejection** — proposals 4..N are persisted but invisible until older ones resolve.
- **Parent brief**: budget enforced by querying audit log + rejecting at ≥3 in trailing 7 days. **Active rejection** at request time.

**Resolution**: The LLD is the source of truth per the parent's "LLD is source of truth" rule, AND per the LLD's explicit Decisions §2: "**The 3-per-week budget is rank-at-read**, not status-at-write — fresh high-impact proposals overtake stale ones without status churn." **01d implements the LLD's rank-at-read** in `listPendingForUser`:

```java
@Override
@Transactional(readOnly = true)
public List<PendingChangeListItemDto> listPendingForUser(UUID userId) {
  Pageable top3 = PageRequest.of(0, config.pendingChangeBudgetPerWeek());     // default 3
  return pendingChangeRepository.findRankedPending(userId, top3).stream()
      .map(pendingChangeMapper::toListItem).toList();
}
```

The `findRankedPending` query (from 01a) orders by `(impactScore DESC, confidence DESC, createdAt ASC)`. The cap is applied via `Pageable.ofSize(3)`. No 4xx is thrown at create time; 4..N proposals just don't surface.

**So `@Transactional(noRollbackFor = ...)` is NOT needed for the budget gate** (no exception is thrown). HOWEVER it IS still needed elsewhere — see step 31 below for the `acceptPendingChange` audit + 4xx case where the audit-write needs to commit even when the 422 surfaces. **Worth user review** — the parent brief says "the budget-gate trigger writes a budget-exceeded audit row before throwing" which doesn't apply per the LLD; the `noRollbackFor` pattern still ships on `acceptPendingChange` for a different reason (the resolved_at write commits even when expiry causes 422).

**Critical surface in the implementing-agent's report**: explicitly note the LLD vs parent-brief inconsistency on the budget gate and confirm the LLD interpretation.

## Behavioural spec

### Trigger 1 — `enqueueImportJob` (async)

1. `@Transactional` (REQUIRED) — single tx for row insert + `JobReadyEvent` publish per [LLD §Concurrency line 876](../../lld/adaptation-pipeline.md).
2. Build `AdaptationJob`: `source = IMPORT`, `priority = ASYNC`, `approval_policy` from `request.catalogue()` (USER → PENDING_CHANGE; SYSTEM → DIRECT), `status = PENDING`, `enqueued_at = now()`, `trace_id = request.parentTraceId().orElseGet(UUID::randomUUID)`.
3. Persist via `adaptationJobRepository.save(job)`.
4. Publish **internal `JobReadyEvent(jobId)`** `AFTER_COMMIT` — a private event consumed by the `@Async` worker (LLD line 772) which routes to `processJob`. Pattern:
   ```java
   @TransactionalEventListener(phase = AFTER_COMMIT)
   @Async
   public void onJobReady(JobReadyEvent event) {
     AdaptationJob job = adaptationJobRepository.findById(event.jobId()).orElseThrow();
     processJob(job);
   }
   ```
   **Note**: This listener does NOT have `@Transactional` — the listener body's only JPA call is `findById`, and `processJob` opens its own transactions per step. **However** if the read is needed, add `@Transactional(REQUIRES_NEW)` per round-7 rule. **Agent verifies whether `findById` outside a tx works** — Spring Data's `JpaRepository.findById` opens its own tx if none exists. **Worth user review.**
5. Return `job.id`.

### Trigger 2 — `enqueueFeedbackJob` (sync)

6. `@Transactional` (REQUIRED). Build `AdaptationJob`: `source = FEEDBACK`, `priority = SYNC`, `approval_policy` from catalogue, `status = RUNNING` (set immediately — we'll process inline).
7. Persist via `adaptationJobRepository.save(job)`.
8. **Call `processJob(job)` SYNCHRONOUSLY** — but `processJob` is not `@Transactional` on the whole method (it's a multi-tx orchestration). **Tx boundary**: the outer `enqueueFeedbackJob` `@Transactional` covers ONLY the row insert. After `save(job)`, **flush + commit** by releasing the outer tx (return + reopen via a helper `@Transactional(REQUIRES_NEW)` method that wraps `processJob`).

    **Pattern**: split into two methods:
    ```java
    @Override
    public AdaptationResultDto enqueueFeedbackJob(FeedbackJobRequest request) {
      UUID jobId = enqueueFeedbackJobRow(request);                   // @Transactional REQUIRED
      AdaptationJob job = adaptationJobRepository.findById(jobId).orElseThrow();
      return processFeedbackJob(job);                                // orchestrator — not @Transactional
    }

    @Transactional
    UUID enqueueFeedbackJobRow(FeedbackJobRequest request) { ... save ... return job.id; }
    ```

9. `processFeedbackJob(job)` calls `processJob(job)`; on `LockTimeoutException` → translate to 409 surface; on any other `AdaptationException` → translate to corresponding 4xx; on success → return the `AdaptationResultDto` built from the job's `AdaptationTrace` (load via `adaptationTraceRepository.findByJobId`).

10. **`feedbackTimeoutMs = 8000`** per LLD line 786. The timeout is enforced by `AdaptationLlmInvoker` via `RecipeAdaptationTask.getTimeoutOverride()` (01e). On timeout, `AiService` throws `AiCallFailedException`; the job FAILS, the method throws to surface "couldn't propose; please retry."

### Trigger 3 — `enqueueDataModelChangeJobs` (async batch)

11. `@Transactional` (REQUIRED). Validate `request.affectedRecipeIds().size() ≤ 5000` per LLD line 643.
12. For each `recipeId`, build `AdaptationJob`: `source = DATA_MODEL_CHANGE`, `priority = BATCH`, `approval_policy = PENDING_CHANGE` (data-model changes propose, never auto-apply per HLD).
13. Bulk save via `adaptationJobRepository.saveAll(jobs)`.
14. **No `JobReadyEvent` publish** — BATCH jobs are picked up by `BatchJobOrchestrator` (step 25), not by the `@Async` worker.
15. Return list of job IDs.

### Trigger 4 — `runPlanTimeRefineJob` (sync)

16. `@Transactional` (REQUIRED) for the insert; same split-method pattern as `enqueueFeedbackJob`.
17. Build `AdaptationJob`: `source = PLAN_TIME`, `priority = SYNC`, `approval_policy = PLAN_OVERLAY`, `parent_decision_id = request.parentDecisionId()`, `trace_id = request.traceId()` (threaded from planner).
18. Persist; call `processJob` via `processFeedbackJob`-equivalent (`processPlanTimeJob`).
19. `LockTimeoutException` → throws (planner reads as infeasibility-escalate per LLD line 880).
20. `AdaptationCharacterBreakException`, `AdaptationHardConstraintViolationException`, etc. → maps to the LLD's outcome shape: `AdaptationResultDto(classification = NO_CHANGE, ...)` per [LLD line 812](../../lld/adaptation-pipeline.md) "the planner reads this as the loop's infeasibility signal — picks a different candidate plan from its top-N or stops refining." **The planner doesn't see a 5xx — it sees a result with `classification = NO_CHANGE`.**

### Pending-change lifecycle — `acceptPendingChange`

21. `acceptPendingChange(UUID pcId, AcceptPendingChangeRequest request, UUID actorUserId)` — **`@Transactional(REQUIRED, noRollbackFor = {PendingChangeExpiredException.class, PendingChangeNotPendingException.class, PendingChangeSupersededException.class})`** per [agent-prompt-template line 256](../../../../ai-workflow/templates/agent-prompt-template.md):

    **Reason**: when the row is expired/non-pending/superseded, we want to update `resolved_at = now()` and surface the 4xx error WITH the resolution timestamp committed. Without `noRollbackFor`, the update rolls back when the exception bubbles, leaving the row in PENDING state and the user sees a 4xx but the row never gets cleaned up.

22. Validation ladder:
    - Load pending change; missing → 404 `PendingChangeNotFoundException`.
    - `actorUserId != pendingChange.userId` → 404 (don't leak).
    - `status != PENDING` → write `resolved_at = now()`, throw 422 `PendingChangeNotPendingException` (LLD line 627).
    - `expires_at < now()` → flip status to `EXPIRED`, write `resolved_at`, throw 422 `PendingChangeExpiredException`.
    - `superseded_by != null` → throw 409 `PendingChangeSupersededException`.
    - `expectedOptimisticVersion != pendingChange.optimisticVersion` → throw 409 (`OptimisticLockingFailureException` natively from JPA on flush, but pre-checking gives a clearer error).
    - `userEdits != null` → run `@ValidRecipeDiff` re-validation (asserts `baseVersionId` still matches catalogue; ingredient mapping keys exist).

23. Apply via `RecipeWriteApi.saveAdaptedVersion` (LLD line 596). If `userEdits != null`, use the userEdits diff; else use `pendingChange.proposedDiff`. Race-checked: `expectedParentVersionNumber` from `pendingChange.baseVersionId` resolved at write time. On `RecipeVersionConflictException` → wrap in 01c's `RebaseOrchestrator` (or throw to 409 surface — pending-change accepts don't rebase per HLD; **worth user review**, but the LLD line 763 only references rebase for the worker pipeline, not for user-driven accept).

24. Update pending change: `status = ACCEPTED` (or `MODIFIED` if `userEdits != null`), `accepted_version_id = result.versionId`, `user_edits = request.userEdits` (nullable), `resolved_at = now()`, `optimistic_version++`. Publish `PendingChangeAcceptedEvent` `AFTER_COMMIT`.

### Pending-change lifecycle — `rejectPendingChange`

25. `rejectPendingChange(UUID pcId, RejectPendingChangeRequest request, UUID actorUserId)` — `@Transactional(REQUIRED, noRollbackFor = PendingChangeNotPendingException.class)` for the same audit-commits-before-4xx reason.
26. Status gate: `status != PENDING` → 422 `PendingChangeNotPendingException` (LLD line 643 — already-rejected is 422, not idempotent 200).
27. Update: `status = REJECTED`, `resolved_at = now()`. Publish `PendingChangeRejectedEvent` `AFTER_COMMIT`.

### `PendingChangesController` — five endpoints

28. Per [LLD §PendingChangesController lines 585-595](../../lld/adaptation-pipeline.md):
    | Method | Path | Body | Response | Status |
    |---|---|---|---|---|
    | GET    | `/api/v1/adaptation/pending-changes` | — | `List<PendingChangeListItemDto>` (top-3 ranked) | 200 |
    | GET    | `/api/v1/adaptation/pending-changes/{id}` | — | `PendingChangeDto` | 200 / 404 |
    | POST   | `/api/v1/adaptation/pending-changes/{id}/accept` | `AcceptPendingChangeRequest` | `PendingChangeDto` | 200 / 400 / 404 / 409 / 422 |
    | POST   | `/api/v1/adaptation/pending-changes/{id}/reject` | `RejectPendingChangeRequest` | `PendingChangeDto` | 200 / 404 / 422 |
    | GET    | `/api/v1/adaptation/recipes/{recipeId}/pending-history?page=&size=` | — | `Page<PendingChangeListItemDto>` | 200 |

29. `CurrentUserResolver` (auth module) resolves `actorUserId` server-side per [LLD line 579](../../lld/adaptation-pipeline.md). Anonymous → 401.

### Trigger 3 listeners

30. Five `@TransactionalEventListener(AFTER_COMMIT)` `@Transactional(REQUIRES_NEW)` methods on a new `@Component AdaptationDataModelListener`:
    - `onRecipeImported(RecipeCreatedEvent event)` — **wait, this is Trigger 1's entry per [LLD line 693](../../lld/adaptation-pipeline.md), NOT Trigger 3**. It calls `enqueueImportJob`. Same listener pattern.
    - `onPreferenceChanged(PreferenceChangedEvent event)` — Trigger 3. Filters affected recipes via `preferenceQueryService.findRecipesAffectedByPreferenceChange(userId, changedField)`. Calls `enqueueDataModelChangeJobs`.
    - `onHardConstraintsChanged(HardConstraintsChangedEvent event)` — Trigger 3. Filters affected recipes via `preferenceQueryService.findRecipesContainingAllergen(userId, allergen)` (or similar). Calls `enqueueDataModelChangeJobs`.
    - `onNutritionTargetsChanged(NutritionTargetsChangedEvent event)` — Trigger 3. Filters affected recipes via `nutritionQueryService.findRecipesViolatingTarget(userId, target)`. Calls `enqueueDataModelChangeJobs`.
    - `onProvisionsBudgetChanged(ProvisionsBudgetChangedEvent event)` — Trigger 3. Filters affected recipes via `provisionsQueryService.findRecipesOverBudget(userId, weeklyBudgetGbp)`. Calls `enqueueDataModelChangeJobs`.

31. **`FeedbackProcessedEvent` is NOT consumed** per [LLD line 696](../../lld/adaptation-pipeline.md) "the feedback module calls `enqueueFeedbackJob` directly per HLD §Job sources ('Feedback is **not** an event'); the result must be returned synchronously to confirm to the user." **No listener for feedback** in 01d.

32. **`PlanTimeRefineDirective` is NOT consumed** per the same LLD line 696 — the planner calls `runPlanTimeRefineJob` directly during Stage D. **No listener.**

33. **Cross-module query-service calls** in each listener body MAY require additional methods that don't exist yet (`findRecipesAffectedByPreferenceChange`, etc.). **Agent verifies the actual method names during impl**; if missing, file a follow-up note in the ticket report. v1 fallback: filter all of the user's USER-catalogue recipes (over-eager but correct). **Worth user review.**

### `BatchJobOrchestrator`

34. `@Component @Scheduled(cron = "${mealprep.adaptation.batch-orchestrator-cron}")` — default `0 30 4 * * *` per LLD line 718.
35. `@Transactional(REQUIRES_NEW)` per round-7 (scheduled methods open their own tx — but since this method orchestrates and the actual work has its own sub-txs, the outermost annotation can be just on the helper that loads the next batch).
36. Pull `findNextPendingJobs(Pageable.ofSize(50))` filtered by `priority = BATCH` (extend the existing query or add a new repo method `findNextPendingBatchJobs(Pageable)`).
37. **Per-job lock check**: for each batch job, call `adaptationJobRepository.findActiveByRecipeId(recipeId)`. If any SYNC/ASYNC job is RUNNING for the same recipe, **defer the BATCH job to the next sweep** — bump `enqueued_at = now()` so it sorts back. Per [LLD line 792](../../lld/adaptation-pipeline.md) "FEEDBACK proceeds first per HLD §Concurrency."
38. For each non-deferred job, call `processJob(job)` serially. **Not async** — v1 batch is serial per LLD line 794 "v1 runs serially via the standard sync API."
39. Total per-cron-run cap: 50 jobs. If more remain, the next cron run picks them up.

### Orphan-poll-fallback

40. `@Component @Scheduled(fixedDelay = 300_000)` (5 minutes; configurable later) per [LLD line 772](../../lld/adaptation-pipeline.md) "a `@Scheduled` poll-fallback re-picks orphan PENDING jobs older than 5 minutes after JVM restart."
41. Query: `SELECT * FROM adaptation_jobs WHERE status = 'PENDING' AND priority IN ('ASYNC', 'SYNC') AND enqueued_at < now() - INTERVAL '5 minutes' ORDER BY enqueued_at LIMIT 20`.
42. For each, publish `JobReadyEvent(jobId)` — the existing `@Async onJobReady` handles it.
43. **Idempotency**: if the job has already started (status RUNNING), `processJob`'s lock check on the recipe handles re-entry. Multiple `JobReadyEvent`s for the same job → second `processJob` finds the row in RUNNING state and aborts (add a guard).

### Custom validators

44. **`@ValidPlannerHint`** — class-level on `PlannerHintRequest`. Asserts:
    - `hintType = PREP_LEAD_TIME` → `payload.lead_time_hours` exists and is a positive integer.
    - `hintType = ABSORPTION_CONFLICT` → `payload.blocked_by` exists as an ingredient mapping key.
    - Other hint types: payload shape free-form (no validation).
    Lives at `adaptation/validation/ValidPlannerHint.java` + `PlannerHintValidator.java`.

45. **`@ValidRecipeDiff`** — class-level on `AcceptPendingChangeRequest.userEdits` (when non-null). Asserts:
    - Diff JSON references the same `baseVersionId` as the pending change.
    - Each ingredient `mapping_key` exists in the catalogue's known universe (cached via `IngredientKeyValidator` from the nutrition module — agent verifies the actual location).
    Lives at `adaptation/validation/ValidRecipeDiff.java` + `RecipeDiffValidator.java`.

### Exception mapping

46. **All new exceptions** declared in 01a already map via the project-wide `GlobalExceptionHandler` (or per-module `AdaptationExceptionHandler` — agent confirms which pattern in 01a). 01d doesn't add new mappings; the controller's exceptions propagate naturally.

## Database

**None.** All schema landed in 01a; 01d is logic + HTTP only.

**Optional**: agent MAY ship a new repository method `AdaptationJobRepository.findNextPendingBatchJobs(Pageable)` if extending the existing `findNextPendingJobs` JPQL is cleaner. Not a schema change.

## OpenAPI updates

### Append to `src/main/resources/openapi/paths/adaptation.yaml` (NEW FILE — adaptation module's first path file)

```yaml
adaptationPendingChanges:
  get:
    tags: [Adaptation]
    operationId: listPendingChangesForUser
    summary: 'Top-3 ranked pending changes for the authenticated user.'
    security: [{ cookieAuth: [] }]
    responses:
      '200':
        description: 'List of up to 3 pending change list items, ranked by impact and confidence.'
        content:
          application/json:
            schema:
              type: array
              items: { $ref: '../schemas/adaptation.yaml#/PendingChangeListItemDto' }
      '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
adaptationPendingChange:
  get:
    tags: [Adaptation]
    operationId: getPendingChange
    summary: 'Single pending change detail.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: id
        required: true
        schema: { type: string, format: uuid }
    responses:
      '200':
        description: 'The pending change.'
        content:
          application/json:
            schema: { $ref: '../schemas/adaptation.yaml#/PendingChangeDto' }
      '404': { description: 'Not found or belongs to another user', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
adaptationPendingChangeAccept:
  post:
    tags: [Adaptation]
    operationId: acceptPendingChange
    summary: 'Accept a pending change; writes through RecipeWriteApi.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: id
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/adaptation.yaml#/AcceptPendingChangeRequest' }
    responses:
      '200': { description: 'Accepted.', content: { application/json: { schema: { $ref: '../schemas/adaptation.yaml#/PendingChangeDto' } } } }
      '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '404': { description: 'Not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '409': { description: 'Stale optimistic version OR superseded', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Not pending OR expired', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
adaptationPendingChangeReject:
  post:
    tags: [Adaptation]
    operationId: rejectPendingChange
    summary: 'Reject a pending change.'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: id
        required: true
        schema: { type: string, format: uuid }
    requestBody:
      required: true
      content:
        application/json:
          schema: { $ref: '../schemas/adaptation.yaml#/RejectPendingChangeRequest' }
    responses:
      '200': { description: 'Rejected.', content: { application/json: { schema: { $ref: '../schemas/adaptation.yaml#/PendingChangeDto' } } } }
      '404': { description: 'Not found', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
      '422': { description: 'Not pending', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
adaptationRecipePendingHistory:
  get:
    tags: [Adaptation]
    operationId: listRecipePendingHistory
    summary: 'All pending changes ever proposed for a recipe (including resolved).'
    security: [{ cookieAuth: [] }]
    parameters:
      - in: path
        name: recipeId
        required: true
        schema: { type: string, format: uuid }
      - in: query
        name: page
        schema: { type: integer, minimum: 0, default: 0 }
      - in: query
        name: size
        schema: { type: integer, minimum: 1, maximum: 100, default: 20 }
    responses:
      '200':
        description: 'Page of pending change list items, latest first.'
        content:
          application/json:
            schema: { $ref: '../schemas/adaptation.yaml#/PendingChangeListItemDtoPage' }
```

### NEW `src/main/resources/openapi/schemas/adaptation.yaml`

Schema definitions for `PendingChangeDto`, `PendingChangeListItemDto`, `PendingChangeListItemDtoPage`, `AcceptPendingChangeRequest`, `RejectPendingChangeRequest`. Use **flat Page<T> shape with `additionalProperties: true`**, **inline `nullable: true`** for nullable fields. All YAML descriptions with `,` `:` `'` single-quoted.

### Append to entry `src/main/resources/openapi/openapi.yaml`

Under `paths:`, append a `# adaptation` block:

```yaml
  /api/v1/adaptation/pending-changes:
    $ref: 'paths/adaptation.yaml#/adaptationPendingChanges'
  /api/v1/adaptation/pending-changes/{id}:
    $ref: 'paths/adaptation.yaml#/adaptationPendingChange'
  /api/v1/adaptation/pending-changes/{id}/accept:
    $ref: 'paths/adaptation.yaml#/adaptationPendingChangeAccept'
  /api/v1/adaptation/pending-changes/{id}/reject:
    $ref: 'paths/adaptation.yaml#/adaptationPendingChangeReject'
  /api/v1/adaptation/recipes/{recipeId}/pending-history:
    $ref: 'paths/adaptation.yaml#/adaptationRecipePendingHistory'
```

Under `components.schemas:`, append the adaptation schemas.

## Edge-case checklist

- [ ] `enqueueImportJob` happy path → row inserted, `JobReadyEvent` published `AFTER_COMMIT`, jobId returned
- [ ] `enqueueImportJob` for USER catalogue → approval_policy = PENDING_CHANGE
- [ ] `enqueueImportJob` for SYSTEM catalogue → approval_policy = DIRECT
- [ ] `enqueueFeedbackJob` happy path → row inserted in initial tx, then processJob runs, AdaptationResultDto returned (loads from trace post-success)
- [ ] `enqueueFeedbackJob` on LockTimeoutException → 409 surfaces; job FAILED with `failure_excerpt = "lock-timeout"`
- [ ] `enqueueFeedbackJob` `AiUnavailableException` → 503 surfaces; job FAILED(AI_UNAVAILABLE); `AdaptationJobFailedEvent` published
- [ ] `enqueueDataModelChangeJobs` with `affectedRecipeIds.size() == 5001` → 400 validation error
- [ ] `enqueueDataModelChangeJobs` with 50 recipe IDs → 50 jobs inserted with `priority = BATCH`; no JobReadyEvent (batch orchestrator picks up)
- [ ] `runPlanTimeRefineJob` happy path → returns AdaptationResultDto with `classification != NO_CHANGE`; substitution written via `RecipeWriteApi.saveAdaptedSubstitution`
- [ ] `runPlanTimeRefineJob` infeasibility → returns `AdaptationResultDto(classification = NO_CHANGE, ...)`; planner reads as infeasibility (no 5xx)
- [ ] `runPlanTimeRefineJob` `LockTimeoutException` → throws; planner Stage D treats as infeasibility-escalate
- [ ] `acceptPendingChange` happy path → row transitions ACCEPTED; `accepted_version_id` populated; `PendingChangeAcceptedEvent` published `AFTER_COMMIT`; `RecipeUpdatedEvent` from RecipeWriteApi fires (catalogue side)
- [ ] `acceptPendingChange` with `userEdits` → row transitions MODIFIED; `userEdits` JSONB persisted
- [ ] `acceptPendingChange` on expired row → 422 `pending-change-expired`; row's `status = EXPIRED` AND `resolved_at` committed (verify by JdbcTemplate read after 422 surfaces — the `noRollbackFor` keeps the audit write)
- [ ] `acceptPendingChange` on already-rejected → 422 `pending-change-not-pending`; `resolved_at` is the prior reject time (not re-stamped)
- [ ] `acceptPendingChange` with stale `expectedOptimisticVersion` → 409 OptimisticLock
- [ ] `acceptPendingChange` with `userEdits` referencing different `baseVersionId` → 400 `@ValidRecipeDiff` failure
- [ ] `rejectPendingChange` happy path → row REJECTED; `PendingChangeRejectedEvent` published
- [ ] `rejectPendingChange` on already-rejected → 422
- [ ] `GET /pending-changes` returns at most 3 items, ranked by `(impactScore DESC, confidence DESC, createdAt ASC)`
- [ ] `GET /pending-changes` with 5 PENDING rows for user → 3 surface; 4th and 5th exist but aren't returned
- [ ] `GET /pending-changes/{id}` for other-user's row → 404 (don't leak)
- [ ] `GET /recipes/{recipeId}/pending-history` returns paginated list including ACCEPTED/REJECTED rows
- [ ] **`@TransactionalEventListener` propagation rule**: all five listener methods compile + load context without the round-7 error; context-load test specifically asserts no `"@TransactionalEventListener method must not be annotated with @Transactional"` error
- [ ] `onPreferenceChanged` happy path: published event → listener fires → `enqueueDataModelChangeJobs` called → N rows inserted
- [ ] `onRecipeImported` happy path: published event → listener fires → `enqueueImportJob` called → row inserted with `source = IMPORT`
- [ ] `BatchJobOrchestrator` cron: 100 BATCH rows enqueued → first run processes 50, second run processes 50; concurrent SYNC job on same recipe → BATCH job for that recipe deferred to next sweep
- [ ] Orphan-poll: a PENDING job older than 5 minutes → `JobReadyEvent` re-published → async worker picks up
- [ ] `@ValidPlannerHint` happy path: `PREP_LEAD_TIME` + `payload.lead_time_hours = 24` → valid
- [ ] `@ValidPlannerHint` failure: `PREP_LEAD_TIME` without `payload.lead_time_hours` → constraint violation surfaces in 422
- [ ] `@ValidRecipeDiff` happy path: diff references the right `baseVersionId` → valid
- [ ] `@ValidRecipeDiff` failure: diff references a different `baseVersionId` → 400 constraint violation
- [ ] OpenAPI request/response shapes match the swagger-request-validator filter in IT
- [ ] `PendingChangeListItemDtoPage` follows the flat Page<T> shape with `additionalProperties: true`

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/adaptation/api/controller/PendingChangesController.java

MOD   src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java               (wire 4 trigger entry methods + acceptPendingChange + rejectPendingChange — remove UOEs)

NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/AdaptationDataModelListener.java   (5 @TransactionalEventListener methods)
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/AdaptationImportListener.java      (onRecipeImported)
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/BatchJobOrchestrator.java          (@Scheduled cron)
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/OrphanJobPollFallback.java         (@Scheduled fixedDelay)
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/JobReadyEvent.java                  (internal event record)
NEW   src/main/java/com/example/mealprep/adaptation/domain/service/internal/JobReadyEventListener.java          (@Async @TransactionalEventListener; calls processJob)

NEW   src/main/java/com/example/mealprep/adaptation/validation/ValidPlannerHint.java
NEW   src/main/java/com/example/mealprep/adaptation/validation/PlannerHintValidator.java
NEW   src/main/java/com/example/mealprep/adaptation/validation/ValidRecipeDiff.java
NEW   src/main/java/com/example/mealprep/adaptation/validation/RecipeDiffValidator.java

MOD   src/main/java/com/example/mealprep/adaptation/api/dto/PlannerHintRequest.java                            (annotate @ValidPlannerHint on the class)
MOD   src/main/java/com/example/mealprep/adaptation/api/dto/AcceptPendingChangeRequest.java                    (annotate @ValidRecipeDiff on the userEdits field)

NEW   src/main/resources/openapi/paths/adaptation.yaml                                                          (5 new path-items)
NEW   src/main/resources/openapi/schemas/adaptation.yaml                                                        (PendingChange* + request schemas)
MOD   src/main/resources/openapi/openapi.yaml                                                                    (add `# adaptation` block under paths + components.schemas)

NEW   src/test/java/com/example/mealprep/adaptation/AdaptationServiceTriggerEntriesTest.java                   (each trigger entry happy path)
NEW   src/test/java/com/example/mealprep/adaptation/PendingChangeLifecycleTest.java                            (accept/reject ladder including noRollbackFor verification)
NEW   src/test/java/com/example/mealprep/adaptation/PendingChangesControllerIT.java                            (5 endpoints; MockMvc; OpenAPI validator filter active)
NEW   src/test/java/com/example/mealprep/adaptation/AdaptationDataModelListenerIT.java                         (each listener fires on its event; @Transactional(REQUIRES_NEW) confirmed by context-load + happy path)
NEW   src/test/java/com/example/mealprep/adaptation/BatchJobOrchestratorIT.java                                 (50-cap, per-recipe defer, serial execution)
NEW   src/test/java/com/example/mealprep/adaptation/OrphanJobPollFallbackIT.java                                (5-minute-old PENDING jobs re-picked)
NEW   src/test/java/com/example/mealprep/adaptation/PlannerHintValidatorTest.java
NEW   src/test/java/com/example/mealprep/adaptation/RecipeDiffValidatorTest.java
NEW   src/test/java/com/example/mealprep/adaptation/Trigger2FeedbackFlowIT.java                                 (full e2e: feedback request → AdaptationResultDto)
NEW   src/test/java/com/example/mealprep/adaptation/Trigger3DataModelFlowIT.java                                (HardConstraintsChangedEvent → batch orchestrator processes affected recipes)
NEW   src/test/java/com/example/mealprep/adaptation/Trigger4PlanTimeFlowIT.java                                 (COST_DELTA directive → substitution row written)
```

**Files this ticket does NOT touch**:

- `emitPlannerHint` body (still UOE in 01d) → **01f**
- `sweepExpiredPendingChanges` body (still returns 0 from 01b) → **01f**
- Admin controllers (`AdaptationAdminController`, `AdapterRunHistoryController`) → **01f**
- `RecipeAdaptationTask`, `AdaptationContextAssembler` final form → **01e**
- `NutritionalKnowledgeService` real impl → **01e**
- `FingerprintRefresher`, `PlannerHintEmitter` → **01f**
- ArchUnit `ModuleBoundaryArchTest` → **01f**
- Migrations (none — 01a shipped schema)

## Dependencies

- **Hard dependency**: `adaptation-pipeline-01b` (merged) — interfaces, DTOs, mappers, event records, `AdaptationServiceImpl` skeleton.
- **Hard dependency**: `adaptation-pipeline-01c` (merged) — `processJob` private method, all internal helpers (`PendingChangeStore`, `RebaseOrchestrator`, validation gates, `AdaptationTraceWriter`).
- **Hard dependency**: `core-01a` (merged) — `LockService`, sealed events, `MealPrepException`.
- **Hard dependency**: `recipe-01f` (merged) — `RecipeWriteApi.saveAdaptedVersion` for pending-change accept; `RecipeCreatedEvent` for Trigger 1 listener.
- **Hard dependency**: `preference-01b` (merged) — `PreferenceChangedEvent`, `HardConstraintsChangedEvent`, `PreferenceQueryService` (the `findRecipesAffectedBy*` methods MAY need filing as follow-ups if missing).
- **Hard dependency**: `nutrition-01a` + `nutrition-01h` (merged) — `NutritionTargetsChangedEvent`, query-service helpers.
- **Hard dependency**: `provisions-01*` (merged) — `ProvisionsBudgetChangedEvent`.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`.
- **Soft dependency**: `adaptation-pipeline-01e` (later, but 01d works without — Noop factory throws cleanly).
- **Sibling tickets**: `adaptation-pipeline-01c` shares the `AdaptationServiceImpl.java` MOD — scope wall stated in 01c.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit)
- [ ] All edge-case items above ticked
- [ ] **Per [decisions/0010 §round-7](../../../../ai-workflow/decisions/0010-wave2-round7-transactionaleventlistener-propagation.md)**: every `@TransactionalEventListener` listener has `@Transactional(propagation = REQUIRES_NEW)` OR `NOT_SUPPORTED` (the former for the five Trigger 3 listeners; latter not used); context-load smoke test asserts the rule
- [ ] **Per [agent-prompt-template line 256](../../../../ai-workflow/templates/agent-prompt-template.md)**: `acceptPendingChange` + `rejectPendingChange` use `@Transactional(noRollbackFor = ...)` for the right exception classes; IT verifies audit-write commits before 4xx
- [ ] `BatchJobOrchestrator` cron expression matches `config.batchOrchestratorCron()` default `0 30 4 * * *`
- [ ] Orphan-poll runs every 5 minutes; idempotent (a PENDING-then-RUNNING-then-DONE job not re-picked)
- [ ] OpenAPI lint green; flat Page<T>; inline nullable; single-quoted descriptions
- [ ] No regression on existing tests
- [ ] No pom.xml dependency adds
- [ ] No file outside the adaptation-pipeline module touched (incl. NO change to preference/nutrition/provisions/recipe/feedback/planner src/)

Squash-merge with: `feat(adaptation): 01d — trigger entries + pending-change controllers + listeners + batch orchestrator + orphan-poll + 2 custom validators`

## What's NOT in scope

- `emitPlannerHint` impl → **01f**
- `sweepExpiredPendingChanges` impl → **01f**
- `AdaptationAdminController`, `AdapterRunHistoryController` → **01f**
- `RecipeAdaptationTask`, `AdaptationContextAssembler`, prompt file → **01e**
- `NutritionalKnowledgeService` real impl → **01e**
- `FingerprintRefresher`, `PlannerHintEmitter` → **01f**
- ArchUnit `ModuleBoundaryArchTest` → **01f**
- Anthropic Batches API integration → deferred per LLD line 794
- The Notification module's listener on `AdaptationJobFailedEvent(AI_UNAVAILABLE)` — **Notification module's concern**; 01d just publishes the event with the right reason
- The frontend conversational suggestion-box-alongside-diff flow → frontend LLD per [LLD line 956](../../lld/adaptation-pipeline.md)
