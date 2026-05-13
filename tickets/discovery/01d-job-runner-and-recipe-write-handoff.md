# Ticket: discovery — 01d `DiscoveryJobRunner` Async Lifecycle + `RecipeWriteApi.saveImportedRecipe` Handoff + Orphan Sweep + Cancellation Flag

## Summary

The **largest discovery ticket** — wires the async job runner that consumes `DiscoveryJobStartedEvent` (published by 01b's `startJob`), drives the QUEUED → RUNNING → terminal state machine per [`lld/discovery.md`](../../lld/discovery.md) §Flow 2 (lines 517-539), and persists successful fetches via the recipe module's `RecipeWriteApi.saveImportedRecipe`. Per [LLD §Business Logic Flows](../../lld/discovery.md), §Concurrency and Transactions (lines 555-567), §Failure Modes (lines 571-588). Ships:

- **`DiscoveryJobRunner`** in `discovery/domain/service/internal/`. `@Component`. The single async listener method `@Async("discoveryRunnerExecutor") @TransactionalEventListener(phase = AFTER_COMMIT)` on `DiscoveryJobStartedEvent`. Per-step transactions (each runner step opens its own short tx — the runner method itself has NO outer `@Transactional`, only the steps).
- **State machine**: claim (QUEUED → RUNNING via optimistic-version), search, AI-filter (pass-through call), fetch loop (robots → rate-limit → fetchRecipe → hard-constraint filter → fingerprint dedup → persist), finalise (SUCCEEDED / PARTIAL / FAILED).
- **Hard-constraint filter integration** — calls `preference.HardConstraintFilterService.check(userId, ingredientMappingKeys)` from preference-01b (already merged) on every extracted `ParsedRecipe` against `constraints.mustExcludeIngredientMappingKeys` per [LLD line 528](../../lld/discovery.md). **The deterministic safety net.**
- **Content-fingerprint dedup** — calls `ContentFingerprintHasher` from 01c then `scrapeLogRepository.existsByContentFingerprintAndOccurredAtAfter(fingerprint, now - lookbackDays)` (new repo method) per [LLD line 529](../../lld/discovery.md).
- **Hand-off to recipe write API** — calls `recipe.spi.RecipeWriteApi.saveImportedRecipe(userId, command)` per [LLD line 530](../../lld/discovery.md). **THIS METHOD DOES NOT YET EXIST** in `recipe-01f`'s `RecipeWriteApi` — see "Cross-module hard dependency" below.
- **Per-fetch scrape-log row** written eagerly per [LLD line 195](../../lld/discovery.md): "per-fetch rows are written eagerly so partial logs survive a runner crash."
- **`DiscoveryRecipeIngestedEvent`** published per successful ingest; **`DiscoveryJobCompletedEvent`** published at terminal transition. Both AFTER_COMMIT.
- **Orphan sweep** — `@Scheduled` 5-minute cadence finalises `RUNNING` jobs with `startedAt < now - heartbeatTimeout`. Also wires the `runOrphanSweep` admin endpoint from 01b — replaces 01b's placeholder.
- **In-memory cancellation flag** — `Map<UUID, AtomicBoolean> cancellationRequests` checked between fetch-loop iterations. 01b's `cancelJob` for RUNNING jobs (currently 422) is updated to set the flag and return 200.

**The runner is the load-bearing piece of the whole discovery module.** 01a/01b/01c are scaffolding; 01d is where discovery does its actual work.

## Cross-module hard dependency: `RecipeWriteApi.saveImportedRecipe`

**`RecipeWriteApi.saveImportedRecipe(UUID userId, SaveImportedRecipeCommand command)` does NOT yet exist** in recipe-01f's `RecipeWriteApi` SPI (which currently ships `saveAdaptedVersion`, `saveAdaptedBranch`, `saveAdaptedSubstitution`, `updateNutritionStatus`, `updateCharacterFingerprint`, `updateBranchDivergence`, `storeEmbedding` per recipe-01f). The discovery LLD (line 530) and this discovery ticket assume the method exists.

**Hard dependency**: a new recipe sibling ticket — **predicted ID `recipe-01l-save-imported-recipe-spi`** — must merge before discovery-01d. That ticket ships:
- `RecipeWriteApi.saveImportedRecipe(UUID userId, SaveImportedRecipeCommand command): UUID recipeId` method on the SPI interface
- `SaveImportedRecipeCommand` record carrying `(Catalogue catalogue, DataQuality dataQuality, String sourceType, ParsedRecipe parsedRecipe, UUID traceId)` — discovery-side `ParsedRecipe` shape OR a parallel `RecipePayload` shape the recipe module owns. **The recipe module's exact shape is theirs to define** — discovery-01d adapts whatever the SPI declares.
- The impl on `RecipeServiceImpl` that maps `SaveImportedRecipeCommand` → existing 01a `createRecipe` internal overload (with `dataQuality = WEB_DISCOVERED`, `catalogue = SYSTEM`) → returns the new recipe id.
- A `RecipeImport` row written with `sourceType = WEB_DISCOVERED`, `sourceUrl = command.parsedRecipe().canonicalUrl()`, `extractionMethod = command.parsedRecipe().extractionMethod()`.
- `RecipeCreatedEvent` fires (already from 01a's `createRecipe`).

**01d cannot ship until `recipe-01l-save-imported-recipe-spi` merges first.** **Flag this to the parent** at the top of the agent report — the parent decides whether to land the recipe-01l prerequisite first, or split discovery-01d into:
- **discovery-01d-runner-skeleton** (this ticket, but with a `// TODO recipe-01l` stub for the persist step; ingest never actually writes a recipe — the scrape row gets `status = EXTRACTION_FAILED` with `reason = "saveImportedRecipe SPI not yet merged"`). Lets the runner state machine + scrape log + events + orphan sweep + cancellation land NOW and unblocks the discovery tests.
- **discovery-01d-real-handoff** (post-recipe-01l, flips the TODO to the real call). 5-minute follow-up.

**Recommended split** if recipe-01l isn't already in flight: 01d ships the skeleton; the persist-step swap-in is a 5-minute follow-up after recipe-01l merges.

**Worth user review.**

## Sibling cross-cutting touches

- `preference.HardConstraintFilterService.check(userId, ingredientMappingKeys)` from **preference-01b (merged)** — invariant 16. **Already in the codebase**; no new dependency.
- `core.decisionlog.DecisionLogService` from **core-01 (merged)** — invariant 30. Optional; the runner writes a single decision-log row per terminal transition per the project-wide loop discipline. **LLD silent on decision-log writes for discovery**; the [implementation-playbook line 28](../../lld/implementation-playbook.md) "Decision-log smoke" gate suggests every optimisation-loop module writes one. **Worth user review.**

## Defers still after 01d

- Sync admin endpoint + `runJobSync` + `CompletableFuture` coordination + 408 / 502 mappings → **discovery-01f**
- Curated source seed (`R__discovery_seed_source_registry.sql` with ~25-30 INSERTs) + reference curated `DiscoverySource` impl + Google CSE adapter → **discovery-01e**

## Behavioural spec

### Listener wiring

1. `DiscoveryJobRunner` is `@Component` in `discovery/domain/service/internal/`. **Package-private** (only the runner's `run` method is wired as a bean per [LLD line 50](../../lld/discovery.md)).
2. **Single listener method** `run(DiscoveryJobStartedEvent event)`:
    ```java
    @Async("discoveryRunnerExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void run(DiscoveryJobStartedEvent event) { ... }
    ```
    **NO `@Transactional` on the method itself** — per LLD line 560 "the runner deliberately does not run the whole job in one transaction — each candidate is a fresh tx so a partial run survives a crash." The per-step transactions are nested via service-method calls (each step delegates to a `@Transactional` helper method).
3. **`@TransactionalEventListener` + `@Transactional` gotcha** per [agent-prompt-template.md](../../../ai-workflow/templates/agent-prompt-template.md): if you DO add `@Transactional` to the listener method, propagation MUST be `REQUIRES_NEW` or `NOT_SUPPORTED`. **The chosen pattern (no `@Transactional` on the listener method) is valid**; the listener body's individual `@Transactional` helper methods provide the tx scope.
4. **Async execution** on `discoveryRunnerExecutor` (declared in 01a). The bean ships from 01a; this ticket consumes it.

### Step 1 — Claim

5. **Claim via optimistic-version** per [LLD line 521](../../lld/discovery.md). New `@Transactional` helper `claim(UUID jobId): Optional<DiscoveryJob>`:
    - `jobRepository.findById(jobId)` — if missing, log WARN and return empty (orphaned event).
    - If `job.status != QUEUED`, log INFO `"job {} not QUEUED (status={}); another runner claimed it or it was cancelled"` and return empty.
    - `job.setStatus(RUNNING)`, `job.setStartedAt(Instant.now())`. Save. **Optimistic-version mismatch** (`OptimisticLockingFailureException`) → log INFO `"job {} claim race; another runner won"` and return empty.
    - On success, return `Optional.of(job)`. The `@Version` increment is captured in the save.

### Step 2 — Search phase

6. **Resolve sources** for the job: call `sourceRegistry.resolveEnabledByKey(Arrays.asList(job.getSourcesRequested()))`. **Read-once at the top of the run** per LLD line 193 — pin the result.
7. **Filter out circuit-broken sources**: per LLD line 565, `failureStreak >= 5 && lastFailureAt > now - 1h` → skip the source, log INFO, add to `sourcesFailed` array with reason `circuit_breaker_open`.
8. **Per-source parallelism**: up to `Math.min(resolved.size(), 4)` parallel `CompletableFuture.supplyAsync(... discoveryRunnerExecutor)` calls. Each one: acquire rate-limit token, call `source.search(query)`, capture `List<DiscoveryCandidate>` or the exception.
9. **`DiscoverySourceUnavailableException`** from `search` → `recordFailure(source.key())` via SourceRegistry → append to `sourcesFailed`, write a summary scrape-log row, continue.
10. **`tryAcquire` returns false** → write a `RATE_LIMITED` scrape-log row with `candidate_url = source.key() + ":search"`, skip the source's search.
11. **Cap candidates per source** at `constraints.maxRecipesPerSource` (default 20 per LLD line 253).
12. **Merge candidates** across sources into one `List<DiscoveryCandidate>`. Increment `job.candidatesSeen += merged.size()` (persist in a follow-up step's tx).

### Step 3 — AI candidate filter

13. **Call `candidateAiFilter.filter(merged, constraints, job.userId)`** — v1 is the pass-through from 01c.
14. **`AiUnavailableException`** is non-fatal (skip-and-flag per LLD line 523, line 573): log warning `"AI candidate filter unavailable; proceeding unfiltered"`, `candidatesAfterFilter = candidatesSeen`, continue.
15. **Update `job.candidatesAfterFilter`** via a `@Transactional` save.

### Step 4 — Fetch loop

16. **Per surviving candidate** (sequential within a source, parallel across sources is the LLD's intent but **01d ships sequential across the whole list** — simpler, predictable, and 01e adds at most 2 source impls in v1). **Worth user review** — parallel across sources is a 5-line change once impls land.
17. **Cancellation check**: per iteration, check `cancellationRequests.get(jobId)`. If present and `.get() == true`, finalise the job as `FAILED` with `errorSummary = "cancelled by user"` and STOP the loop.
18. **Robots.txt gate**: `source.robotsTxtUri()` empty → `robotsTxtOutcome = SKIPPED`, proceed. Non-empty → `robotsTxtGate.check(candidate.candidateUrl(), source.user_agent)`:
    - `DISALLOWED` → write scrape row `(status=ROBOTS_DISALLOWED, robotsTxtOutcome=DISALLOWED, skipReason=ROBOTS_DISALLOWED)`. Skip.
    - `UNAVAILABLE` AND `source.respectRobotsTxt = true` → write scrape row `(status=SKIPPED, robotsTxtOutcome=UNAVAILABLE, skipReason=ROBOTS_DISALLOWED)`. Per LLD line 525 "polite-by-default skip". Skip.
    - `UNAVAILABLE` AND `source.respectRobotsTxt = false` → proceed (operator override).
    - `ALLOWED` → proceed.
19. **Rate-limit token**: `rateLimiterRegistry.tryAcquire(source.key())`. False → write scrape row `(status=RATE_LIMITED, skipReason=RATE_LIMITED)`. Skip.
20. **Quota check**: `recipesIngested >= job.requestedCount` → write `JOB_QUOTA_REACHED` scrape row and skip remaining candidates (terminate the loop in next iteration).
21. **`source.fetchRecipe(candidate)`** call. Three failure modes per [LLD line 527](../../lld/discovery.md):
    - `ExtractionFailedException` → scrape row `(status=EXTRACTION_FAILED, error_class, error_message)`. Skip.
    - Returned `ParsedRecipe` with `extractionConfidence < 0.5` → scrape row `(status=EXTRACTION_FAILED, skipReason=LOW_CONFIDENCE, extraction_method, extraction_confidence)`. Skip.
    - Any other unchecked exception → scrape row `(status=EXTRACTION_FAILED, error_class, error_message)`. Log WARN. Skip.
22. **Hard-constraint filter** per [LLD line 528](../../lld/discovery.md): `hardConstraintFilterService.check(job.userId, parsedRecipe.ingredients().stream().map(ParsedIngredient::ingredientMappingKey).filter(Objects::nonNull).toList())`. Result `passes = false` → scrape row `(status=HARD_CONSTRAINT_VIOLATION, skipReason=HARD_CONSTRAINT)`. Skip.
    - **Discovery's stricter policy** than user-initiated URL import (recipe-01b) — per LLD line 583 "autonomous fetch raises the safety bar." Even if the user's hard-constraint filter is also called by recipe-01b on URL import, discovery applies it as a DETERMINISTIC SAFETY NET regardless of the AI filter or the source. Never trusts upstream filtering.
23. **Content fingerprint**:
    - `String fingerprint = contentFingerprintHasher.fingerprint(parsedRecipe)`.
    - `scrapeLogRepository.existsByContentFingerprintAndOccurredAtAfter(fingerprint, now - properties.duplicateLookbackDays() days)` → if true, scrape row `(status=DUPLICATE, content_fingerprint, skipReason=DUPLICATE)`. Skip.
24. **Persist via `RecipeWriteApi.saveImportedRecipe`**:
    - Build `SaveImportedRecipeCommand` with `(catalogue=SYSTEM, dataQuality=WEB_DISCOVERED, sourceType=WEB_DISCOVERED, parsedRecipe, traceId=job.traceId())`.
    - `UUID recipeId = recipeWriteApi.saveImportedRecipe(job.userId, command)`. **This call is OUTSIDE any discovery tx** per [LLD line 561](../../lld/discovery.md): "Top-level transactions in the recipe module (per the recipe LLD). The runner does not pass a transaction to it." The recipe module opens its own tx.
    - **Exception from the recipe module** → scrape row `(status=EXTRACTION_FAILED, error_class=ex.class.simpleName, error_message)`. **Job continues** per LLD line 585. Do NOT fail the whole job on a single recipe-side write failure.
    - On success, write a `SUCCESS` scrape row `(status=SUCCESS, recipe_id, content_fingerprint, extraction_method, extraction_confidence, latency_ms)`.
    - Increment `recipesIngested` in the same tx that writes the scrape row (single helper method, `@Transactional`).
    - **Update source bookkeeping**: `sourceRegistry.recordSuccess(source.key())` — resets failure streak (separate tx from 01c).
25. **Publish `DiscoveryRecipeIngestedEvent`** AFTER_COMMIT after each successful ingest:
    ```java
    eventPublisher.publishEvent(new DiscoveryRecipeIngestedEvent(
        jobId, job.getUserId(), recipeId, source.key(), parsedRecipe.canonicalUrl(),
        parsedRecipe.extractionConfidence(), job.getTraceId(), Instant.now()));
    ```
26. **Quota check after each ingest**: `if (recipesIngested == requestedCount) STOP loop`.

### Step 5 — Finalise

27. **Terminal state** per [LLD lines 533-536](../../lld/discovery.md):
    - All requested sources succeeded (at least one ingest each) AND quota met → `SUCCEEDED`.
    - Some succeeded, some failed → `PARTIAL`. `errorSummary` lists failed sources and last error class.
    - All sources failed → `FAILED`. `errorSummary` is the cross-source error summary.
28. Update job row: `status, completedAt = Instant.now(), errorSummary, sourcesSucceeded, sourcesFailed`. Save in `@Transactional`.
29. **Publish `DiscoveryJobCompletedEvent`** AFTER_COMMIT:
    ```java
    eventPublisher.publishEvent(new DiscoveryJobCompletedEvent(
        job.getId(), job.getUserId(), job.getStatus(),
        job.getRecipesIngested(), job.getCandidatesSeen(),
        Arrays.asList(job.getSourcesSucceeded()), Arrays.asList(job.getSourcesFailed()),
        job.getErrorSummary(), job.getTraceId(), Instant.now()));
    ```
30. **Decision-log row** (optional per "Worth user review" above): `DecisionLogService.log(scope="discovery", traceId, inputs={ jobId, userId, sourcesRequested, requestedCount }, outputs={ status, recipesIngested, sourcesFailed }, occurredAt)`. If `core-01` exposes the service, write the row; otherwise skip.

### Step 6 — Re-entry idempotence

31. Re-entry on the same `jobId` (e.g. event published twice, or a runner restart re-triggers) short-circuits at the claim step (job already RUNNING or terminal). Per [LLD line 539](../../lld/discovery.md).

### Cancellation flag (in-memory)

32. New `Map<UUID, AtomicBoolean> cancellationRequests = new ConcurrentHashMap<>()` field on `DiscoveryJobRunner`.
33. New public method `void requestCancellation(UUID jobId)` on the runner. Called by `DiscoveryServiceImpl.cancelJob` for RUNNING jobs (replaces 01b's 422 branch).
34. Modify `DiscoveryServiceImpl.cancelJob` (from 01b):
    - QUEUED → flips to FAILED as before. Also calls `runner.requestCancellation(jobId)` for safety (no-op since the runner hasn't started; the map gets a flag the runner clears).
    - RUNNING → call `runner.requestCancellation(jobId)`; **return the latest DTO with status = RUNNING** (the actual transition to FAILED happens when the runner sees the flag at the next iteration). The endpoint returns 200 with status RUNNING; the caller polls.
    - Terminal → continue to throw 422.
35. **Cleanup of the cancellation map**: on terminal transition (Step 5), `cancellationRequests.remove(jobId)`. Otherwise the map grows unbounded.

### Orphan sweep

36. New `@Scheduled(fixedDelay = 5*60*1000, initialDelay = 5*60*1000)` method `sweepOrphans()` on `DiscoveryJobRunner` (OR on a separate `DiscoveryOrphanSweepScheduler` — see "Scheduler placement" below).
37. Per [LLD lines 549-551](../../lld/discovery.md): `findOrphanRunning(now - heartbeatTimeout)` returns jobs whose `startedAt` is older than `properties.heartbeatTimeout()` (default 10m). For each:
    - Set `status = FAILED, completedAt = now, errorSummary = "runner crashed; resumed by sweep"`. Save in `@Transactional`.
    - Publish `DiscoveryJobCompletedEvent` AFTER_COMMIT.
38. **`OrphanSweepResult { int resumedCount }`** record. Returned by both the `@Scheduled` method (logged) and the admin endpoint.
39. **Replace 01b's `runOrphanSweep` placeholder** in `DiscoveryServiceImpl` with a delegation to the runner: `return runner.sweepOrphansNow()`. Same method body as `@Scheduled` but invokable on demand.

### Scheduler placement

40. **Decision**: keep `sweepOrphans` ON `DiscoveryJobRunner`. Pros: one class handles the job lifecycle; the cancellation map is co-located with the sweep. Cons: the runner becomes "the runner + the sweeper." **Worth user review** — alternative is a separate `DiscoveryOrphanSweepScheduler`. Rejected for v1 — fewer beans, simpler wiring. Move out if the runner crosses ~400 LOC.
41. **`@EnableScheduling`**: verify it's on a project-wide `@Configuration` (auth-01a or core-01 likely enables it). If not, ADD `@EnableScheduling` to `DiscoveryAsyncConfig` from 01a — one-line addition. **The agent should grep first.**

### Helper repository method (new on `DiscoveryScrapeLogRepository`)

42. Append to the existing `DiscoveryScrapeLogRepository` (from 01a):
    ```java
    boolean existsByContentFingerprintAndOccurredAtAfter(String fingerprint, Instant cutoff);
    ```
43. **No migration change** — the index `idx_discovery_scrape_log_fingerprint` from 01a already supports this query.

### Events

44. New `DiscoveryRecipeIngestedEvent` record at `discovery/event/DiscoveryRecipeIngestedEvent.java` per [LLD lines 493-498](../../lld/discovery.md) verbatim.
45. New `DiscoveryJobCompletedEvent` record at `discovery/event/DiscoveryJobCompletedEvent.java` per [LLD lines 485-491](../../lld/discovery.md) verbatim.
46. Both have an `Instant occurredAt` field set at publish time.

## Database

```
NO new Flyway migrations.
```

All schema landed in 01a. 01d adds one repo method on the existing scrape-log repo; no schema change.

## OpenAPI updates

**Zero OpenAPI changes in 01d.** The runner is internal; the admin orphan-sweep endpoint is already declared in 01b's spec. 01d only swaps the placeholder impl for the real one.

## Verbatim shape snippets

### `DiscoveryJobRunner` skeleton

```java
@Component
@RequiredArgsConstructor
class DiscoveryJobRunner {
  private static final Logger log = LoggerFactory.getLogger(DiscoveryJobRunner.class);

  private final DiscoveryJobRepository jobRepository;
  private final DiscoverySourceRepository sourceRepository;
  private final DiscoveryScrapeLogRepository scrapeLogRepository;
  private final SourceRegistry sourceRegistry;
  private final RobotsTxtGate robotsTxtGate;
  private final RateLimiterRegistry rateLimiterRegistry;
  private final ContentFingerprintHasher fingerprintHasher;
  private final CandidateAiFilter candidateAiFilter;
  private final RecipeWriteApi recipeWriteApi;
  private final HardConstraintFilterService hardConstraintFilter;
  private final ApplicationEventPublisher eventPublisher;
  private final DiscoveryProperties properties;
  private final ObjectMapper objectMapper;

  private final Map<UUID, AtomicBoolean> cancellationRequests = new ConcurrentHashMap<>();

  @Async("discoveryRunnerExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void run(DiscoveryJobStartedEvent event) {
    UUID jobId = event.jobId();
    log.info("discovery job {} started (trigger={}, sources={})",
        jobId, event.trigger(), event.sourcesRequested());
    try {
      Optional<DiscoveryJob> claimed = claim(jobId);
      if (claimed.isEmpty()) return;
      DiscoveryJob job = claimed.get();

      DiscoveryConstraints constraints = objectMapper.treeToValue(
          job.getConstraintsJson(), DiscoveryConstraints.class);
      List<DiscoverySource> resolved = filterCircuitBroken(
          sourceRegistry.resolveEnabledByKey(Arrays.asList(job.getSourcesRequested())));

      List<DiscoveryCandidate> candidates = searchPhase(job, resolved, constraints);
      candidates = aiFilterPhase(job, candidates, constraints);
      fetchPhase(job, resolved, candidates, constraints);
      finalise(job);
    } catch (Throwable t) {
      log.error("discovery job {} runner crashed", jobId, t);
      finaliseFailed(jobId, t);
    } finally {
      cancellationRequests.remove(jobId);
    }
  }

  void requestCancellation(UUID jobId) {
    cancellationRequests.computeIfAbsent(jobId, k -> new AtomicBoolean(false)).set(true);
  }

  // ... claim, filterCircuitBroken, searchPhase, aiFilterPhase, fetchPhase, finalise,
  //      sweepOrphans (@Scheduled), sweepOrphansNow (invokable),
  //      writeScrapeRow (eager per-row write), incrementIngested
}
```

### Per-step transaction boundary

Each step is a `@Transactional` helper inside an internal collaborator (not on the runner method itself — calling `@Transactional`-self-invocation doesn't work due to Spring AOP proxies). Pattern:

```java
@Component
@RequiredArgsConstructor
class DiscoveryJobTransitions {
  private final DiscoveryJobRepository jobRepository;
  private final DiscoveryScrapeLogRepository scrapeLogRepository;

  @Transactional
  public Optional<DiscoveryJob> claim(UUID jobId) {
    DiscoveryJob job = jobRepository.findById(jobId).orElse(null);
    if (job == null || job.getStatus() != DiscoveryJobStatus.QUEUED) return Optional.empty();
    job.setStatus(DiscoveryJobStatus.RUNNING);
    job.setStartedAt(Instant.now());
    try {
      return Optional.of(jobRepository.saveAndFlush(job));
    } catch (OptimisticLockingFailureException ex) {
      log.info("job {} claim race; another runner won", jobId);
      return Optional.empty();
    }
  }

  @Transactional
  public void writeScrapeRow(DiscoveryScrapeLog row) {
    scrapeLogRepository.save(row);
  }

  @Transactional
  public void incrementIngested(UUID jobId) {
    DiscoveryJob job = jobRepository.findById(jobId).orElseThrow();
    job.setRecipesIngested(job.getRecipesIngested() + 1);
    jobRepository.save(job);
  }

  @Transactional
  public void finaliseTo(UUID jobId, DiscoveryJobStatus terminal,
      String errorSummary, String[] succeeded, String[] failed) { ... }
}
```

`DiscoveryJobRunner` injects `DiscoveryJobTransitions` and calls its methods. Spring's AOP proxy applies `@Transactional` on the call (cross-bean), avoiding the self-invocation trap.

### Scrape row builder

```java
DiscoveryScrapeLog row = DiscoveryScrapeLog.builder()
    .id(UUID.randomUUID())
    .jobId(jobId)
    .sourceKey(source.key())
    .candidateUrl(candidate.candidateUrl())
    .canonicalUrl(parsedRecipe != null ? parsedRecipe.canonicalUrl() : null)
    .status(ScrapeOutcome.SUCCESS)
    .robotsTxtOutcome(robotsOutcome)
    .latencyMs((int) Duration.between(fetchStart, Instant.now()).toMillis())
    .contentFingerprint(fingerprint)
    .extractionMethod(parsedRecipe.extractionMethod())
    .extractionConfidence(parsedRecipe.extractionConfidence())
    .recipeId(recipeId)
    .occurredAt(Instant.now())
    .build();
transitions.writeScrapeRow(row);
```

## Edge-case checklist

### Claim

- [ ] Listener fires on a QUEUED job → claim flips to RUNNING, optimistic-version bumps, `startedAt` set
- [ ] Listener fires on a job already in RUNNING (concurrent runner) → claim returns empty, runner exits without effect
- [ ] Listener fires on a non-existent jobId (orphaned event) → warn logged, runner exits

### Search

- [ ] Single source with 0 candidates → `sourcesSucceeded` includes it (search "succeeded" — no candidates is not a failure), `candidatesSeen = 0`, job finalises FAILED (no ingests; LLD line 536 covers this)
- [ ] Single source raising `DiscoverySourceUnavailableException` → recorded as failure, scrape row written, `failureStreak` incremented; finalise FAILED
- [ ] Two sources, one succeeds, one fails → PARTIAL; succeeded source's circuit resets, failed source's streak increments
- [ ] Circuit-broken source (failureStreak >= 5 within 1h) → skipped entirely, not in either succeeded or failed (scrape row with `skipReason = circuit_breaker_open` per invariant 7)
- [ ] `tryAcquire` returns false → `RATE_LIMITED` scrape row for the search attempt, source skipped (treat as failure)

### AI filter

- [ ] Pass-through filter returns input unchanged → `candidatesAfterFilter = candidatesSeen`
- [ ] Pass-through filter throws `AiUnavailableException` → log warning, proceed unfiltered (skip-and-flag per LLD line 573); `candidatesAfterFilter = candidatesSeen` (unfiltered = whole list)

### Fetch loop

- [ ] Robots.txt DISALLOWED on a candidate → scrape row with `ROBOTS_DISALLOWED`, no fetch, candidate skipped
- [ ] Robots.txt UNAVAILABLE + `respectRobotsTxt = true` → SKIPPED scrape row, no fetch
- [ ] Robots.txt UNAVAILABLE + `respectRobotsTxt = false` → fetch proceeds
- [ ] Source with no `robotsTxtUri()` → robots check SKIPPED, fetch proceeds
- [ ] Rate limit token starvation → RATE_LIMITED scrape row, candidate skipped
- [ ] `fetchRecipe` throws `ExtractionFailedException` → `EXTRACTION_FAILED` row, candidate skipped, job continues
- [ ] `fetchRecipe` returns ParsedRecipe with confidence 0.3 (< 0.5) → `EXTRACTION_FAILED` row + `skipReason = LOW_CONFIDENCE`, candidate skipped
- [ ] Hard-constraint filter fails (`passes = false`) → `HARD_CONSTRAINT_VIOLATION` row + `skipReason = HARD_CONSTRAINT`, **NO RECIPE WRITTEN**. This is the deterministic safety net per LLD line 528.
- [ ] Content fingerprint exists within lookback → `DUPLICATE` row + `skipReason = DUPLICATE`, candidate skipped
- [ ] `RecipeWriteApi.saveImportedRecipe` succeeds → `SUCCESS` row + `recipe_id` set + `recipesIngested++` + `DiscoveryRecipeIngestedEvent` published AFTER_COMMIT
- [ ] `RecipeWriteApi.saveImportedRecipe` throws → `EXTRACTION_FAILED` row + error class + error message; **job continues** (per LLD line 585)
- [ ] Quota reached → remaining candidates skipped with `JOB_QUOTA_REACHED`

### Cancellation

- [ ] User calls `cancelJob` on QUEUED job → job flips to FAILED immediately (01b behaviour); 01d updates 01b's branch only for RUNNING
- [ ] User calls `cancelJob` on RUNNING job → 200 returned with status=RUNNING; runner's next iteration check finds flag, finalises FAILED with `errorSummary = "cancelled by user"`
- [ ] Cancellation map cleanup: after finalise, `cancellationRequests.remove(jobId)` runs (verify map size returns to 0 in IT)

### Finalise

- [ ] All sources succeeded, quota met → SUCCEEDED
- [ ] Mixed succeeded/failed → PARTIAL; `errorSummary` contains failed source list
- [ ] All sources failed → FAILED; `errorSummary` contains the last error class
- [ ] `DiscoveryJobCompletedEvent` published AFTER_COMMIT with correct terminal status, recipesIngested, candidatesSeen
- [ ] `completedAt` set to `Instant.now()` at finalise time

### Orphan sweep

- [ ] Job with `status = RUNNING` and `startedAt < now - heartbeatTimeout` → swept to FAILED + `errorSummary = "runner crashed; resumed by sweep"`
- [ ] Job within heartbeat window → untouched
- [ ] `POST /admin/run-orphan-sweep` returns `{ resumedCount: N }` matching the swept count
- [ ] `@Scheduled` cadence verified: `fixedDelay = 5 minutes` (or whatever the project's preferred testing harness allows — IT may stub the schedule)

### Decision log (optional)

- [ ] One `decision_log` row per terminal transition with `scope = "discovery"`, `traceId = job.traceId`, populated `inputs` + `outputs` (if `core-01` exposes the service; else skip)

### Cross-cutting

- [ ] `RecipeWriteApi.saveImportedRecipe` call site is OUTSIDE any discovery `@Transactional` — verify via stack trace inspection in a test (or rely on the absence of `@Transactional` on the runner method + sub-method delegation pattern)
- [ ] `DiscoveryJobRunner.run` has NO `@Transactional`; sub-methods on `DiscoveryJobTransitions` do
- [ ] `@Async("discoveryRunnerExecutor")` resolves the 01a bean
- [ ] If recipe-01l (saveImportedRecipe SPI) is NOT yet merged → the runner ships with the persist step as a stub writing `EXTRACTION_FAILED` rows; agent reports the gap clearly
- [ ] No regression on 01a / 01b / 01c tests
- [ ] No `pom.xml` adds
- [ ] No other modules' files touched (the runner calls `RecipeWriteApi`, `HardConstraintFilterService`, `DecisionLogService` — all interfaces from other modules; no edits)

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryJobRunner.java
NEW   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryJobTransitions.java     (the @Transactional helper class — cross-bean to avoid self-invocation trap)

NEW   src/main/java/com/example/mealprep/discovery/event/DiscoveryRecipeIngestedEvent.java
NEW   src/main/java/com/example/mealprep/discovery/event/DiscoveryJobCompletedEvent.java

MOD   src/main/java/com/example/mealprep/discovery/domain/repository/DiscoveryScrapeLogRepository.java     (append existsByContentFingerprintAndOccurredAtAfter)

MOD   src/main/java/com/example/mealprep/discovery/domain/service/internal/DiscoveryServiceImpl.java        (cancelJob RUNNING branch: replace 422 with runner.requestCancellation + return latest DTO; runOrphanSweep: delegate to runner.sweepOrphansNow())

MOD   src/main/java/com/example/mealprep/discovery/config/DiscoveryAsyncConfig.java                         (add @EnableScheduling if not project-wide; agent grep first)

NEW   src/test/java/com/example/mealprep/discovery/DiscoveryJobRunnerTest.java                              (unit: claim race / search failure / AI filter unavailable / robots disallowed / hard-constraint reject / fingerprint dedup / quota stop / cancellation flag; uses Mockito for RecipeWriteApi, HardConstraintFilterService, sources)
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryRunnerIT.java                                   (Testcontainers IT: two stub DiscoverySource @Component beans — always-success + always-fail — under a test-scoped @Configuration; verify PARTIAL terminal, scrape rows, ingested recipes via mocked or real RecipeWriteApi, events fire AFTER_COMMIT)
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryHardConstraintIT.java                           (IT: peanut-allergy user, peanut-containing parsedRecipe — verify HARD_CONSTRAINT_VIOLATION scrape row and NO recipe write)
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryOrphanSweepTest.java                            (unit + IT: stale RUNNING job → FAILED + event)
NEW   src/test/java/com/example/mealprep/discovery/DiscoveryCancellationIT.java                            (IT: POST /cancel on a RUNNING job → 200; runner sees flag and finalises FAILED)
```

Count: ~5 NEW + 3 MOD + 5 test files = ~13 files. Concentration in the runner (~250 LOC) + the transitions helper (~80 LOC). Estimated agent runtime **55-65 min** — at the top of the band; the agent may report scope strain and the parent may split into 01d-runner + 01d-orphan-sweep-and-cancel if needed.

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java`
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java`
- Other modules' src/ files (the runner CALLS `RecipeWriteApi`, `HardConstraintFilterService`, `DecisionLogService` interfaces but doesn't define or edit them)
- Discovery 01a entities, migrations, mappers (frozen)
- Discovery 01b controllers, exception handler (the `cancelJob` body lives on `DiscoveryServiceImpl`, not the controller — only the impl is modified)
- Discovery 01c SPI, registries, robots gate, fingerprint hasher (frozen — 01d only consumes)
- `src/main/resources/openapi/openapi.yaml` (no API contract changes)
- `pom.xml`

## Dependencies

- **Hard dependency**: `discovery-01a` (merged) — entities, migrations, repos, mappers, DTOs, async config, properties, exception root, `DiscoveryJobStartedEvent` defined in 01b but the listener wires here.
- **Hard dependency**: `discovery-01b` (merged) — `DiscoveryServiceImpl`, `DiscoveryJobStartedEvent`, exception handler, controllers (`runOrphanSweep` placeholder, `cancelJob` 422-on-RUNNING branch).
- **Hard dependency**: `discovery-01c` (merged) — `DiscoverySource` SPI, `SourceRegistry`, `RobotsTxtGate`, `RateLimiterRegistry`, `ContentFingerprintHasher`, `CandidateAiFilter`, `DiscoveryQuery`/`DiscoveryCandidate`/`ParsedRecipe` records, `DiscoverySourceUnavailableException`, `ExtractionFailedException`.
- **Hard dependency**: `preference-01b` (merged) — `HardConstraintFilterService.check`.
- **Hard dependency**: `recipe-01l-save-imported-recipe-spi` (**NOT YET TICKETED — PREDICTED**). Carries `RecipeWriteApi.saveImportedRecipe(UUID userId, SaveImportedRecipeCommand command): UUID`. **Flag to parent at top of agent report.** Without this dependency, 01d ships in skeleton mode (persist step stubs to `EXTRACTION_FAILED` rows) and a follow-up `discovery-01d-real-handoff` flips the stub when recipe-01l merges.
- **Soft dependency**: `core-01` (merged) — `DecisionLogService`. Used in invariant 30 if available; otherwise skipped.
- **Sibling tickets running in parallel** (Wave 3 round 4): `planner-01d`, `feedback-01d`, `adaptation-pipeline-01d`. None should touch any discovery file. Cross-cutting collision points: NONE — 01d touches only discovery module + reads from `preference`/`recipe`/`core` interfaces.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green (build + spotless + OpenAPI lint + ArchUnit gate)
- [ ] All edge-case items above ticked
- [ ] `DiscoveryJobRunner.run` has NO `@Transactional` — verified by grep
- [ ] `DiscoveryJobTransitions` methods have `@Transactional` — verified by grep
- [ ] `@Async("discoveryRunnerExecutor")` annotation present — verified by grep
- [ ] `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` on `run` — verified by grep
- [ ] `@Scheduled(fixedDelay = ...)` on `sweepOrphans` (or its host) — verified by grep
- [ ] `@EnableScheduling` is project-wide (grep first; only ADD to `DiscoveryAsyncConfig` if missing)
- [ ] **If `recipe-01l-save-imported-recipe-spi` not yet merged**: the persist step ships as a stub writing `EXTRACTION_FAILED` rows; agent reports this clearly to the parent
- [ ] Hard-constraint filter is called on EVERY successful extraction before the persist call — verified by IT
- [ ] `DiscoveryRecipeIngestedEvent` published AFTER_COMMIT on each ingest; `DiscoveryJobCompletedEvent` published AFTER_COMMIT at finalise — verified via `@RecordApplicationEvents`
- [ ] In-memory cancellation map cleared on terminal — verified in `DiscoveryCancellationIT`
- [ ] No regression on 01a / 01b / 01c tests
- [ ] No `pom.xml` adds; no other modules touched

## Gotchas embedded (apply during implementation)

- **`@TransactionalEventListener` + `@Transactional` propagation**: per agent-prompt-template, if you put `@Transactional` on a `@TransactionalEventListener` method, propagation MUST be `REQUIRES_NEW` or `NOT_SUPPORTED`. **The chosen pattern (no `@Transactional` on the listener method, individual `@Transactional` helpers in a separate bean)** sidesteps this. DO NOT add `@Transactional` to `DiscoveryJobRunner.run` even after iteration if a test fails — that fails context-load with `@TransactionalEventListener method must not be annotated with @Transactional unless when declared as REQUIRES_NEW or NOT_SUPPORTED` and the whole project's ITs break.

- **Spring AOP self-invocation trap**: `@Transactional` on a method called from `this.method()` in the same class doesn't apply (the proxy isn't on the call). That's why the per-step `@Transactional` methods live on `DiscoveryJobTransitions` — a separate bean. Calling `transitions.claim(jobId)` from the runner crosses the proxy boundary and `@Transactional` takes effect.

- **Pgvector + Hibernate StaleObjectStateException (round 8 retro)**: N/A here (no embeddings in discovery). But the runner's interaction with the recipe module via SPI must not hold a recipe-side `Hibernate Session` — that's the recipe module's tx scope. The SPI call is OUTSIDE any discovery tx (per invariant 24).

- **Async listener test races (round 8 retro)**: tests asserting state after `POST /jobs` must NOT race the async runner. Use either:
  - **Block the listener**: `@MockBean DiscoveryJobRunner runner; doNothing().when(runner).run(any())` — verifies the event fires, runner not invoked.
  - **Loosen state assertion**: `awaitility().atMost(5, SECONDS).until(() -> getJob(id).status() == SUCCEEDED || ...)` — wait for terminal.
  - **Synchronous test mode**: `@TestConfiguration` providing `discoveryRunnerExecutor = Runnable::run` (caller-runs) so the listener executes in the test thread.

- **HTTP-client adapter location**: N/A here directly — `RobotsTxtGate` impl from 01c already lives in `discovery/api/internal/`. `RecipeWriteApi` is a project-internal SPI (no HTTP).

- **`saveAndFlush` vs `save`**: the claim step `saveAndFlush` so the OptimisticLockingFailureException surfaces synchronously. Other transitions `save` is fine — they're inside a tx and flush at commit.

- **`@Scheduled` with `@Async`**: do not combine. The orphan sweep is `@Scheduled` (lightweight; runs on the scheduler thread). The runner is `@Async` (heavy; runs on `discoveryRunnerExecutor`). Two separate concerns.

- **OptimisticLockingFailureException** in the `claim` step is INFO not ERROR — multiple runners racing for one job is expected concurrency, not a bug. Logging at INFO avoids alarm noise.

- **`ConcurrentHashMap` for cancellation flags**: the cancellation map is read on every fetch-loop iteration. Use `ConcurrentHashMap` + `AtomicBoolean` for thread-safety. **Do NOT use `HashMap`** — the runner's listener thread writes (request cancellation) and the runner's own thread reads (loop check); race condition without concurrent collection.

- **Cancellation cleanup**: `cancellationRequests.remove(jobId)` in `finally` block — otherwise the map leaks one entry per job. The IT in `DiscoveryCancellationIT` should assert map size returns to 0 after job terminal.

- **Don't trust LLD column widths blindly**: N/A in 01d (no schema changes).

## What's NOT in scope

- Sync admin endpoint (`POST /admin/jobs/sync`) + `runJobSync` impl with `CompletableFuture` per-job keying + 408 `DiscoveryJobTimeoutException` + 502 `DiscoveryAllSourcesUnavailableException` mappings → **discovery-01f**
- Curated source seed (`R__discovery_seed_source_registry.sql` populated with ~25-30 INSERTs) + reference curated `DiscoverySource` impl + Google Custom Search adapter → **discovery-01e**
- `RecipeWriteApi.saveImportedRecipe` SPI definition + impl in the recipe module → **recipe-01l-save-imported-recipe-spi** (predicted)
- Cross-user authorization in queries (already handled by 01b's `findByIdAndUserId`)
- `DiscoveryRecipeIngestedListener` (no consumer in v1; the event fires; nothing subscribes — Spring drops it). 01d publishes the event so future consumers can wire up.
- Per-source parallel fetch — 01d ships sequential; revisit when 01e adds multiple impls and load profile clarifies
- Decision-log row writes — invariant 30 covers the optional version. **Worth user review.**
- Retention sweep on `discovery_scrape_log` (LLD line 184: "6 months, then aggregated into per-source per-day metrics") — separate `@Scheduled` job; LLD says "not specified in v1"
- ArchUnit rule "`DiscoveryService` injected only by `planner.*` and `recipe.*`" — defer until consumer modules stabilise (planner-01d / recipe-01k or wherever discovery's actual consumers ship)

Squash-merge with: `feat(discovery): 01d — async job runner + recipe-write handoff + hard-constraint safety net + orphan sweep + cancellation flag`
