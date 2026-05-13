# Ticket: discovery — 01f Sync Admin Endpoint (`runJobSync`) + 408/502 Error Mappings + ArchUnit Service-Injection Rule

## Summary

Final discovery slice — ships the **synchronous-cold-start path** for the planner per [`lld/discovery.md`](../../lld/discovery.md) §Flow 3 (lines 541-543), §REST table line 438 (`POST /admin/jobs/sync` → 200/408/502), §Error responses (lines 446-457: `DiscoveryAllSourcesUnavailableException` 502, `DiscoveryJobTimeoutException` 408). Ships:

- **`runJobSync(UUID userId, StartDiscoveryJobRequest request, Duration timeout): DiscoveryJobDto`** — public method on `DiscoveryService` (signature already declared in 01b but body throws `UnsupportedOperationException`). 01f replaces the throw with the real impl: enqueues the job via the existing 01b `startJob` flow, then blocks on a per-job `CompletableFuture` keyed by `jobId` for up to `timeout`. The runner (01d) completes the future on terminal transition.
- **Per-job `CompletableFuture` coordination** — new `Map<UUID, CompletableFuture<DiscoveryJobStatus>> syncWaiters` on `DiscoveryJobRunner` (from 01d). On terminal transition the runner calls `syncWaiters.remove(jobId).complete(terminalStatus)`. On runner crash (orphan sweep), the future never completes — the sync caller hits the timeout and returns the latest-known DTO.
- **`DiscoveryAdminController`** — `POST /admin/jobs/sync` endpoint. Already declared as deferred in 01b's OpenAPI surface (the path was reserved but not implemented). 01f adds the OpenAPI block + the controller method.
- **Two new exception types** under `discovery/exception/`: `DiscoveryAllSourcesUnavailableException` (502) and `DiscoveryJobTimeoutException` (408). Both extend `DiscoveryException` from 01a.
- **Three new `@ExceptionHandler` methods** appended to `DiscoveryExceptionHandler` from 01b: the 408 + 502 handlers, plus an explicit `MethodArgumentNotValidException` mapping if not already covered globally.
- **Trigger validation** — `runJobSync` rejects when called for triggers other than `COLD_START` per [LLD line 471](../../lld/discovery.md): "Service-layer validation: `runJobSync` rejects when called for triggers other than `COLD_START` (defence in depth)." 422 `DiscoveryConstraintInvalidException` with detail `"runJobSync only supports COLD_START trigger"`.
- **ArchUnit rule** — append `DiscoveryService` injected only by `planner.*` and `recipe.*` to `DiscoveryBoundaryTest`. Per [LLD line 619](../../lld/discovery.md). Today there are no consumers in those modules yet; the rule is vacuously true.
- **`DiscoveryRecipeIngestedListener` test stub** — verifies that future consumers can subscribe to `DiscoveryRecipeIngestedEvent`. **No real listener** — Spring drops unhandled events.

This is the last discovery ticket. After 01f merges, the module is feature-complete for v1.

## Sync flow detail (LLD line 541-543)

> `runJobSync(userId, request, timeout)` — planner cold-start only. Enqueues per Flow 1, then blocks on a per-job `CompletableFuture` keyed by `jobId`; the runner completes it on terminal transition. Deadline expiry returns the latest-known DTO (status may still be `RUNNING`) — job continues in background, planner falls back per [meal-planner.md §Cold start](../../design/meal-planner.md#cold-start). Recommended max timeout 60s. **Worth user review.**

01f ships this exact semantics:
- **Deadline reached, future not complete** → return the latest DTO (re-read from DB to capture any in-flight progress); status may be `RUNNING`. **Do NOT throw `DiscoveryJobTimeoutException`** here — the LLD frames timeout as a normal degraded response, not an error.
- **Future completes with terminal `FAILED` AND all-sources-failed** → return the DTO with `errorSummary` populated. **The 502 mapping happens only if the caller surfaces this to a sync HTTP endpoint** — see invariant 12 below.
- **The 408 mapping** applies when the HTTP endpoint's response should signal timeout-with-partial-progress to an external client. **Decision in 01f**: 408 fires only if the HTTP endpoint's request has a `?strictTimeout=true` query param; default behaviour returns 200 + the partial DTO. **Worth user review.**

## Defers — nothing after 01f for v1

01f closes the discovery module's v1 scope. Out of scope per the LLD's "Out of Scope" section (hard pockets):
- Specific curated source impls beyond `ReferenceCuratedSource` (added per-user-decision)
- HTML extraction templates per source (microdata + JSON-LD beyond v1's stub + site selectors + AI fallback)
- AI prompt for the candidate filter (`CandidateAiFilter` pass-through ships in 01c)
- Trending-preferences-driven scheduled discovery
- Cross-user catalogue sharing
- AI extraction for arbitrary recipe HTML (lives inside `DiscoverySource.fetchRecipe`; 01e ships JSON-LD-only)

## Behavioural spec

### `DiscoveryServiceImpl.runJobSync`

1. Replace the 01b stub (`throws UnsupportedOperationException`) with the real impl. Signature unchanged: `DiscoveryJobDto runJobSync(UUID userId, StartDiscoveryJobRequest request, Duration timeout)`.
2. **Trigger validation** (LLD line 471): `if (request.trigger() != DiscoveryJobTrigger.COLD_START) throw new DiscoveryConstraintInvalidException("runJobSync only supports COLD_START trigger")`. Defence in depth — the controller `POST /admin/jobs/sync` itself only forwards trusted requests, but the service guards against future re-routes.
3. **Cap the timeout**: `Duration effectiveTimeout = timeout.compareTo(properties.syncTimeout()) > 0 ? properties.syncTimeout() : timeout`. `properties.syncTimeout()` from 01a defaults to 60s. The caller can request shorter; longer is silently clamped. **Worth user review** — alternative is to reject `timeout > syncTimeout()` with 400. Chosen clamping for predictability.
4. **Enqueue via existing 01b path**: `DiscoveryJobDto enqueued = startJob(userId, request)`. This persists the row, publishes `DiscoveryJobStartedEvent`, returns the QUEUED DTO. The runner's listener picks it up.
5. **Register the waiter**: `CompletableFuture<DiscoveryJobStatus> waiter = new CompletableFuture<>(); runner.registerSyncWaiter(enqueued.id(), waiter)`. The waiter slot is added BEFORE the listener could fire — there's a race window because the runner is `@Async`, but in practice the listener can't fire until the publishing tx commits AND the async executor picks it up (msec scale). Even if the runner fires first and finds no waiter, the runner's terminal-write path is `syncWaiters.computeIfPresent(jobId, ... .complete)` which is a no-op when absent — meaning a poll fallback would be needed. **Decision**: register the waiter BEFORE `startJob`'s commit, so the listener (which fires AFTER_COMMIT) always finds it. The registration is in-memory; no transaction.
6. **Block on the waiter**: `DiscoveryJobStatus terminal = waiter.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)`.
    - **Success path**: terminal is one of SUCCEEDED / FAILED / PARTIAL.
    - **`TimeoutException`** caught → log INFO `"runJobSync deadline reached for jobId={}; returning latest DTO"`, re-read the job from DB, return its current DTO (status likely still RUNNING). The runner continues in the background.
    - **`InterruptedException`** caught → re-set the interrupt flag, re-read DTO, return. **Don't throw** — the planner needs a DTO even on interrupt.
    - **`ExecutionException`** → unwrap and treat the cause as a runner-side fatal (rare; log ERROR; re-read DTO; return).
7. **Re-read DTO from DB** at the end (whether success or timeout) so the response carries the most-recent counters (`recipesIngested`, `candidatesSeen`, etc.). The waiter only carries the terminal status; the full DTO needs the DB re-read.
8. **Cleanup the waiter map**: `runner.unregisterSyncWaiter(jobId)` in a `finally` block so a timeout doesn't leak the entry.

### `DiscoveryJobRunner` extensions

9. New field on `DiscoveryJobRunner` (from 01d): `Map<UUID, CompletableFuture<DiscoveryJobStatus>> syncWaiters = new ConcurrentHashMap<>()`.
10. New public methods:
    - `void registerSyncWaiter(UUID jobId, CompletableFuture<DiscoveryJobStatus> waiter)` — `syncWaiters.put(jobId, waiter)`.
    - `void unregisterSyncWaiter(UUID jobId)` — `syncWaiters.remove(jobId)`.
11. **Inside `finalise(job)`** (the runner's existing terminal-transition method from 01d), AFTER the DB write but BEFORE the `DiscoveryJobCompletedEvent` publish:
    ```java
    CompletableFuture<DiscoveryJobStatus> waiter = syncWaiters.remove(job.getId());
    if (waiter != null) waiter.complete(job.getStatus());
    ```
12. **`finaliseFailed(jobId, throwable)`** (01d's catch-all for runner crashes) — same pattern: `syncWaiters.computeIfPresent(jobId, ... .complete(FAILED))`.
13. **Orphan sweep** (01d's `sweepOrphans`) — when finalising a stale RUNNING job to FAILED, ALSO complete the waiter if present.

### `DiscoveryAdminController` — `POST /admin/jobs/sync`

14. Append to `DiscoveryAdminController` (existing from 01b):
    ```java
    @PostMapping("/jobs/sync")
    public ResponseEntity<DiscoveryJobDto> runJobSync(
        @AuthenticationPrincipal UUID userId,
        @Valid @RequestBody StartDiscoveryJobRequest request,
        @RequestParam(name = "timeoutSeconds", defaultValue = "60") @Min(1) @Max(300) int timeoutSeconds,
        @RequestParam(name = "strictTimeout", defaultValue = "false") boolean strictTimeout) {
      Duration timeout = Duration.ofSeconds(timeoutSeconds);
      DiscoveryJobDto result = discoveryService.runJobSync(userId, request, timeout);
      // Status-to-HTTP mapping:
      if (result.status() == DiscoveryJobStatus.RUNNING && strictTimeout) {
        throw new DiscoveryJobTimeoutException(result.id(), timeout);
      }
      if (result.status() == DiscoveryJobStatus.FAILED
          && result.sourcesFailed() != null
          && result.sourcesFailed().size() == request.sourceKeys() == null
              ? 0  // when sourceKeys null we don't know the full set without re-resolving; skip the 502 in that case
              : request.sourceKeys().size()) {
        // All sources failed → 502
        throw new DiscoveryAllSourcesUnavailableException(result.id(), result.sourcesFailed());
      }
      return ResponseEntity.ok(result);
    }
    ```
    **Simplification**: the all-sources-failed check needs the resolved-source count, not the requested-source count (the requested set may be null). **Decision**: shift the 502 logic into the service — `runJobSync` returns the DTO; the controller maps status to HTTP code based on a simple table:
    - `SUCCEEDED` / `PARTIAL` → 200
    - `RUNNING` + `strictTimeout=true` → 408 (timeout, partial DTO in body)
    - `RUNNING` + `strictTimeout=false` → 200 (partial DTO is acceptable per LLD line 543)
    - `FAILED` + service set the all-sources-down flag on the DTO → 502
    - `FAILED` + other → 200 (FAILED is a valid terminal; the planner inspects the DTO to decide its fallback)
15. **Augment `DiscoveryJobDto`** with a transient `boolean allSourcesUnavailable` flag? **NO** — DTO contract is stable; adding fields breaks contract tests. **Better**: the service tracks via a separate side channel — the runner sets an `AllSourcesUnavailable` marker (`Map<UUID, Boolean> allSourcesDown` on the runner OR a column on the job row). **Worth user review.** **Simplest** v1: the controller infers from `sourcesFailed.size() > 0 && sourcesSucceeded.size() == 0 && status == FAILED`. Approximate but workable.

### Errors + handler

16. New `DiscoveryAllSourcesUnavailableException extends DiscoveryException` at `discovery/exception/`. Constructor `(UUID jobId, List<String> failedSources)`. Message: `"all sources unavailable for job " + jobId + ": " + failedSources`. **Maps to 502** per LLD line 452.
17. New `DiscoveryJobTimeoutException extends DiscoveryException` at `discovery/exception/`. Constructor `(UUID jobId, Duration timeout)`. Message: `"discovery job " + jobId + " did not reach a terminal state within " + timeout`. **Maps to 408** per LLD line 453.
18. **Append three handlers** to `DiscoveryExceptionHandler` (from 01b). Keep `@Order(Ordered.HIGHEST_PRECEDENCE)`:
    ```java
    @ExceptionHandler(DiscoveryAllSourcesUnavailableException.class)
    public ProblemDetail handleAllSourcesDown(DiscoveryAllSourcesUnavailableException ex) {
      ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
      pd.setType(URI.create(TYPE_PREFIX + "discovery-all-sources-unavailable"));
      pd.setTitle("All discovery sources unavailable");
      return pd;
    }

    @ExceptionHandler(DiscoveryJobTimeoutException.class)
    public ProblemDetail handleTimeout(DiscoveryJobTimeoutException ex) {
      ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.REQUEST_TIMEOUT, ex.getMessage());
      pd.setType(URI.create(TYPE_PREFIX + "discovery-job-timeout"));
      pd.setTitle("Discovery job timeout");
      return pd;
    }
    ```
19. **`MethodArgumentNotValidException`** mapping: per LLD line 456 it produces 400 with `errors[]` extension. The project's `GlobalExceptionHandler` already handles this generically — **verify by grep**. If absent, ADD a discovery-specific handler. **Most projects** handle this globally; 01f assumes so and skips the per-module handler.

### ArchUnit rule — `DiscoveryService` injection boundary

20. Append to `DiscoveryBoundaryTest` (existing):
    ```java
    @ArchTest
    static final ArchRule discoveryServiceInjectedOnlyByPlannerAndRecipe =
        noClasses().that().doNotResideInAnyPackage(
            "com.example.mealprep.discovery..",
            "com.example.mealprep.planner..",
            "com.example.mealprep.recipe..")
        .should().dependOnClassesThat().areAssignableTo(DiscoveryService.class);
    ```
21. **Today the rule is vacuously true** — neither planner nor recipe has wired discovery yet. Future tickets in those modules will inject `DiscoveryService` via the `DiscoveryModule` facade. The rule trips immediately if a new module (e.g. notification, feedback) accidentally pulls discovery in.
22. **`DiscoveryQueryService` is NOT covered by the rule** — admin/debug callers may live anywhere (e.g. a future `admin` UI module). **Worth user review** — alternative is to add a parallel rule for the query service. Rejected for v1.

### OpenAPI

23. Append to `src/main/resources/openapi/paths/discovery.yaml`:
    ```yaml
    discoveryAdminJobsSync:
      post:
        tags: [Discovery]
        operationId: runDiscoveryJobSync
        summary: 'Synchronously run a COLD_START discovery job; blocks up to timeoutSeconds.'
        description: 'Planner cold-start path. Returns the terminal DTO on completion or the partial DTO on timeout. Use strictTimeout=true to receive 408 instead of 200 on deadline expiry.'
        security: [{ cookieAuth: [] }]
        parameters:
          - in: query
            name: timeoutSeconds
            schema: { type: integer, minimum: 1, maximum: 300, default: 60 }
          - in: query
            name: strictTimeout
            schema: { type: boolean, default: false }
        requestBody:
          required: true
          content:
            application/json:
              schema: { $ref: '../schemas/discovery.yaml#/StartDiscoveryJobRequest' }
        responses:
          '200':
            description: 'Job reached a terminal state (or partial DTO on non-strict timeout).'
            content:
              application/json:
                schema: { $ref: '../schemas/discovery.yaml#/DiscoveryJobDto' }
          '400': { description: 'Validation error', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
          '401': { description: 'Unauthenticated', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
          '408': { description: 'Sync timeout (only when strictTimeout=true)', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
          '422': { description: 'runJobSync called with non-COLD_START trigger', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
          '502': { description: 'All discovery sources unavailable', content: { application/problem+json: { schema: { $ref: '../schemas/common.yaml#/ProblemDetail' } } } }
    ```
24. Append to `src/main/resources/openapi/openapi.yaml`:
    ```yaml
      /api/v1/discovery/admin/jobs/sync: { $ref: 'paths/discovery.yaml#/discoveryAdminJobsSync' }
    ```

### Cross-cutting

25. **No new entities, migrations, repos, mappers, configs, properties** in 01f.
26. **No changes to `DiscoveryJobMapper`** — DTO shape unchanged.
27. **No changes to `DiscoveryQueryService`** — read methods unchanged.
28. **No changes to 01c helpers** — `SourceRegistry`, `RobotsTxtGate`, `RateLimiterRegistry`, `ContentFingerprintHasher`, `CandidateAiFilter` — all consumed unchanged by the existing runner.
29. **No changes to 01e source impls** — `GoogleCustomSearchAdapter`, `ReferenceCuratedSource`, `JsonLdRecipeExtractor`, quota tracker — all unchanged.

## Database

**Zero migrations in 01f.**

## OpenAPI updates

One new path-item + one new ref in `openapi.yaml` (see invariants 23-24).

## Verbatim shape snippets

### `DiscoveryServiceImpl.runJobSync` (final)

```java
@Override
public DiscoveryJobDto runJobSync(UUID userId, StartDiscoveryJobRequest request, Duration timeout) {
  if (request.trigger() != DiscoveryJobTrigger.COLD_START) {
    throw new DiscoveryConstraintInvalidException("runJobSync only supports COLD_START trigger");
  }
  Duration effective = timeout.compareTo(properties.syncTimeout()) > 0 ? properties.syncTimeout() : timeout;

  // Enqueue first (publishes DiscoveryJobStartedEvent AFTER_COMMIT)
  DiscoveryJobDto enqueued = startJob(userId, request);

  // Register the waiter; runner.finalise will complete it on terminal transition
  CompletableFuture<DiscoveryJobStatus> waiter = new CompletableFuture<>();
  runner.registerSyncWaiter(enqueued.id(), waiter);

  try {
    waiter.get(effective.toMillis(), TimeUnit.MILLISECONDS);
  } catch (TimeoutException te) {
    log.info("runJobSync deadline reached for jobId={}; returning latest DTO", enqueued.id());
  } catch (InterruptedException ie) {
    Thread.currentThread().interrupt();
    log.info("runJobSync interrupted for jobId={}; returning latest DTO", enqueued.id());
  } catch (ExecutionException ee) {
    log.error("runJobSync waiter failed for jobId={}", enqueued.id(), ee);
  } finally {
    runner.unregisterSyncWaiter(enqueued.id());
  }

  return getJob(enqueued.id())
      .orElseThrow(() -> new DiscoveryJobNotFoundException(enqueued.id()));
}
```

### `DiscoveryJobRunner` waiter extensions

```java
// On DiscoveryJobRunner:
private final Map<UUID, CompletableFuture<DiscoveryJobStatus>> syncWaiters = new ConcurrentHashMap<>();

public void registerSyncWaiter(UUID jobId, CompletableFuture<DiscoveryJobStatus> waiter) {
  syncWaiters.put(jobId, waiter);
}

public void unregisterSyncWaiter(UUID jobId) {
  syncWaiters.remove(jobId);
}

// Inside finalise(job), after DB write:
private void completeWaiterIfPresent(DiscoveryJob job) {
  CompletableFuture<DiscoveryJobStatus> waiter = syncWaiters.remove(job.getId());
  if (waiter != null) {
    waiter.complete(job.getStatus());
  }
}
```

## Edge-case checklist

### Sync flow happy path

- [ ] `POST /admin/jobs/sync` with COLD_START trigger and a fast-completing job → 200 + terminal DTO (SUCCEEDED) within timeout
- [ ] Sync call with PARTIAL terminal → 200 + DTO with `errorSummary` set
- [ ] Sync call with FAILED terminal (single source fails) → 200 + DTO with `errorSummary`

### Timeout

- [ ] Sync call with `timeoutSeconds=2` and a slow job (4s) + `strictTimeout=false` → 200 + DTO with `status=RUNNING`; the job continues in background; subsequent `GET /jobs/{id}` shows progress and eventual terminal
- [ ] Same scenario + `strictTimeout=true` → 408 `discovery-job-timeout` ProblemDetail; the job still continues in background
- [ ] Sync call with `timeoutSeconds=500` (above the 300 cap) → 400 (`@Max(300)`)
- [ ] Service-side `properties.syncTimeout()` of 60s overrides any `timeoutSeconds > 60` → clamped silently; the response arrives at the 60s mark

### Trigger validation

- [ ] Sync call with `trigger=USER_INITIATED` → 422 `discovery-constraint-invalid` with detail mentioning COLD_START
- [ ] Sync call with `trigger=COLD_START` → proceeds

### All-sources-down

- [ ] Sync call where all requested sources throw `DiscoverySourceUnavailableException` → runner finalises FAILED; controller maps to 502 `discovery-all-sources-unavailable`
- [ ] Sync call where 1 of 2 sources fails → PARTIAL terminal; 200 + DTO with `sourcesSucceeded.size()=1, sourcesFailed.size()=1`

### Concurrency

- [ ] Two concurrent `runJobSync` calls with different `userId`s → both proceed independently; each waiter independent
- [ ] Sync call where the runner finalises BEFORE the waiter is registered (theoretical race) → waiter eventually times out; the controller falls back on the DB re-read (which shows terminal status). The 200 response carries the terminal DTO. **Worth user review** — the LLD's "register before publish" pattern requires the listener to fire AFTER `startJob`'s commit; the registration is in memory and happens BEFORE `runJobSync` returns. In practice the listener has no opportunity to fire before the waiter exists. **Verify with an IT.**

### Cancellation during sync

- [ ] User cancels the job mid-sync via `POST /jobs/{id}/cancel` (separate session) → runner sets cancellation flag; on next iteration runner finalises FAILED with `errorSummary = "cancelled by user"`; sync waiter completes; controller returns 200 + FAILED DTO
- [ ] **Edge case**: cancel call arrives before runner has claimed the job → 01b's `cancelJob` for QUEUED runs synchronously; the runner's claim then finds status != QUEUED and exits. Sync waiter never completes — eventually times out at `effectiveTimeout`. Controller returns the DB DTO showing FAILED. **Acceptable** — the user-facing outcome is correct.

### Waiter map hygiene

- [ ] Successful terminal → waiter removed from `syncWaiters` (verify map size returns to 0 in IT)
- [ ] Timeout → `finally` block removes the waiter (verify)
- [ ] Two consecutive sync calls don't leak waiters

### ArchUnit

- [ ] `discoveryServiceInjectedOnlyByPlannerAndRecipe` rule passes today (vacuously — no consumers)
- [ ] A test-only class outside the allowed packages that imports `DiscoveryService` trips the rule (verify by deliberate injection in a `// @SuppressWarnings("ArchUnit")` test? — optional; if tricky to set up cleanly, skip and rely on the rule for future regression)

### OpenAPI

- [ ] Swagger-cli validate passes
- [ ] swagger-request-validator passes on the `POST /admin/jobs/sync` IT
- [ ] 408 response shape has correct ProblemDetail type URI
- [ ] 502 response shape has correct ProblemDetail type URI

### Cross-cutting

- [ ] `DiscoveryExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)`
- [ ] No regression on 01a-01e tests
- [ ] No `pom.xml` adds
- [ ] No other modules' files touched

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/discovery/exception/DiscoveryAllSourcesUnavailableException.java
NEW   src/main/java/com/example/mealprep/discovery/exception/DiscoveryJobTimeoutException.java

MOD   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryServiceImpl.java        (replace runJobSync UnsupportedOperationException stub with real impl; add CompletableFuture coordination + DB re-read)
MOD   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryJobRunner.java           (add syncWaiters map + registerSyncWaiter / unregisterSyncWaiter; complete waiter in finalise + finaliseFailed + orphan sweep paths)

MOD   src/main/java/com/example/mealprep/discovery/api/controller/DiscoveryAdminController.java             (append POST /jobs/sync endpoint method)
MOD   src/main/java/com/example/mealprep/discovery/api/DiscoveryExceptionHandler.java                       (append handlers for the two new exceptions; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))

MOD   src/main/resources/openapi/paths/discovery.yaml                                                       (append discoveryAdminJobsSync path-item)
MOD   src/main/resources/openapi/openapi.yaml                                                               (one path entry under # ===== discovery ===== block)

MOD   src/test/java/com/example/mealprep/discovery/DiscoveryBoundaryTest.java                               (append discoveryServiceInjectedOnlyByPlannerAndRecipe rule)

NEW   src/test/java/com/example/mealprep/discovery/DiscoveryRunJobSyncIT.java                               (Testcontainers IT: happy path, timeout non-strict, timeout strict → 408, all-sources-down → 502, non-COLD_START → 422, waiter map hygiene)
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryRunJobSyncTest.java                            (unit: CompletableFuture coordination with mocked runner; TimeoutException handling; InterruptedException handling; ExecutionException handling)
MOD   src/test/java/com/example/mealprep/discovery/DiscoveryAdminControllerIT.java                          (append: sync endpoint exists, OpenAPI contract validation passes)
```

Count: ~2 NEW + 7 MOD + 2 test = ~11 files. Logic concentration in the `runJobSync` impl + the waiter coordination. Estimated agent runtime **35-45 min** — below the median; the surface area is small but the async coordination needs careful testing.

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' files
- Discovery 01a entities, migrations, mappers, properties, async config (frozen)
- Discovery 01b service signatures (only the body of `runJobSync` changes); controllers from 01b (only `DiscoveryAdminController` gains one method)
- Discovery 01c helpers (frozen — consumed only)
- Discovery 01d runner internals beyond the waiter map additions (the state machine + transitions + scrape-log writes + events + cancellation map are unchanged)
- Discovery 01e source impls (consumed only)
- `pom.xml`

## Dependencies

- **Hard dependency**: `discovery-01a` (merged) — entities, migrations, repos, `DiscoveryProperties.syncTimeout()`, async config, exception root.
- **Hard dependency**: `discovery-01b` (merged) — `DiscoveryServiceImpl.startJob` (consumed by `runJobSync`), `DiscoveryAdminController`, `DiscoveryExceptionHandler`.
- **Hard dependency**: `discovery-01c` (merged) — `DiscoverySource` SPI, registries, helpers.
- **Hard dependency**: `discovery-01d` (merged) — `DiscoveryJobRunner` (extended here with waiter map + register/unregister methods + completion calls in `finalise` / `finaliseFailed` / orphan sweep).
- **Soft dependency**: `discovery-01e` (merged) — source impls. 01f works with 0 enabled sources (412/502 paths exercise that); end-to-end happy-path IT needs at least one impl with a stub `search`.
- **Hard dependency**: `auth-01a` (merged) — `CurrentUserResolver`.
- **No new cross-module dependencies** beyond those already declared.
- **Sibling tickets running in parallel** (Wave 3 round 6, the final round): `planner-01f`, `feedback-01f`, `adaptation-pipeline-01f`. None should touch discovery. Cross-cutting collision points: `openapi.yaml` (different module blocks), `DiscoveryBoundaryTest` (only discovery touches its own boundary test).

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `DiscoveryExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 2 handlers
- [ ] OpenAPI nullable fields use inline `nullable: true`; the new schema has no `$ref + nullable` pair
- [ ] `runJobSync` rejects non-COLD_START trigger with 422 — verified in unit test + IT
- [ ] `runJobSync` blocks on `CompletableFuture.get(timeout)` — not a `Thread.sleep` loop — verified by inspection
- [ ] Waiter map cleared on every path (success / timeout / error) — verified by IT asserting `syncWaiters` reflection or size-via-test-helper
- [ ] ArchUnit `discoveryServiceInjectedOnlyByPlannerAndRecipe` passes (vacuously today)
- [ ] No regression on 01a-01e tests
- [ ] No `pom.xml` adds

## Gotchas embedded (apply during implementation)

- **`CompletableFuture.get(timeout)` blocks the calling thread**: `runJobSync` is called from an HTTP request thread (Tomcat's). A 60s block on Tomcat's default 200-thread pool means up to 200 concurrent sync calls saturate the pool. **Worth user review** — alternative is `@Async` + `DeferredResult<>` for non-blocking. v1 ships blocking because (a) sync is only called for planner cold-start, expected to be rare; (b) the default 60s timeout caps the damage; (c) Spring's DeferredResult adds 30 LOC for marginal v1 benefit.

- **CompletableFuture vs SynchronousQueue**: the waiter map approach uses `CompletableFuture` as a one-shot signalling primitive. An alternative is `SynchronousQueue<DiscoveryJobStatus>` per job — but `CompletableFuture` is the standard Java idiom for "future result, complete-once". Use `CompletableFuture`.

- **Race: runner completes before waiter registers**: the registration happens BEFORE `startJob` commits. `startJob`'s `DiscoveryJobStartedEvent` publishes AFTER_COMMIT. The listener thread can't fire until after commit. So the waiter is ALWAYS registered before the listener could fire. **Verified by inspection**; verify by IT with a slow-start runner stub.

- **`@TransactionalEventListener` + `@Transactional` propagation**: applies to 01d's listener, not 01f. 01f doesn't add any new listeners.

- **HTTP-client adapter location**: N/A in 01f (no new HTTP).

- **SPI-with-Noop pattern**: N/A in 01f.

- **`saveAndFlush` vs `save`**: `runJobSync`'s `startJob` call already uses `saveAndFlush` (from 01b). 01f doesn't change this.

- **OpenAPI `$ref + nullable: true`**: applies to 01f's new schemas; new path-item refs only ProblemDetail and existing schemas. No new schema definitions. **No new sticky trap surfaces.**

- **`@AuthenticationPrincipal UUID userId`**: matches the controller pattern used in 01b. Verify the resolver returns `UUID` directly (some projects return `String` or a custom principal); recipe-01a's controllers use `@AuthenticationPrincipal SessionPrincipal sp; UUID userId = sp.userId();` — adopt that pattern if so.

- **`syncWaiters` concurrent access**: `ConcurrentHashMap` is fine. The race "register after complete" is handled because `CompletableFuture.complete` returns true if the future wasn't already complete; the runner's `syncWaiters.remove(jobId)` happens AFTER `waiter.complete(status)` — if the waiter is registered AFTER the runner's removal attempt, the new entry will never be completed (waiter times out). The fix is `computeIfPresent` in the runner's completion path AND removal-with-result in the service's `finally`. Even simpler: the agent should verify by IT that the waiter map is consistent.

- **Don't trust LLD column widths blindly**: N/A in 01f.

## What's NOT in scope

- Any new entities, migrations, repos, mappers, configs, properties
- Specific curated source impls beyond `ReferenceCuratedSource` (those land per user decision, post-v1)
- HTML extraction templates per source (microdata + per-site selectors + AI fallback) — separate engineering exercise
- Full 5-layer `RecipeExtractionService` from `recipe-extraction-pipeline.md` → separate ticket sequence
- AI candidate filter prompt — `CandidateAiFilter` pass-through ships in 01c
- Notification copy on `DiscoveryJobCompletedEvent` — owned by notification module
- Trending-preferences-driven scheduled discovery (`PreferenceChangedEvent` listener) — natural v2 per LLD line 636
- Cross-user catalogue sharing — out per [README.md §Tier 1](../../design/README.md)
- Frontend "find me more recipes" button / scrape-log explorer — Figma phase

Squash-merge with: `feat(discovery): 01f — sync admin endpoint + 408/502 mappings + service-injection archunit rule`
