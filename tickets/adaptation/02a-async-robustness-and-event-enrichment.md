# Ticket: adaptation — 02a Async robustness + `RecipeCreatedEvent` enrichment (+ e2e adaptation stub)

## Summary

Three related defects surfaced while stabilising the E2E suite. The adapt-on-create path (Trigger 1
— **by design** per [adaptation-pipeline.md:776](../../lld/adaptation-pipeline.md): every
`RecipeCreatedEvent` enqueues a `RECIPE_ADAPTATION` job, "usually NO_CHANGE") exposes them whenever a
suite/run creates enough recipes:

- **#1 — terminal-state leak (prod reliability).** `AdaptationLlmInvoker.invoke`
  ([`AdaptationLlmInvoker.java:37`](../../src/main/java/com/example/mealprep/adaptation/domain/service/internal/AdaptationLlmInvoker.java))
  catches **only** `AiUnavailableException`. Any other `ai.exception.*` — notably
  `AiInvalidResponseException` (malformed model output, a real prod failure mode) — propagates **raw**
  out of `processJob` (which only catches `AdaptationAiUnavailableException`,
  [`AdaptationServiceImpl.java:620-625`](../../src/main/java/com/example/mealprep/adaptation/domain/service/AdaptationServiceImpl.java))
  and is logged-and-dropped by `JobReadyEventListener`. The job was already flipped to RUNNING (Step 1
  lock-acquire) so it is **never marked terminal**; the orphan poller only re-picks `PENDING`
  ([`OrphanJobPollFallback.java:54`](../../src/main/java/com/example/mealprep/adaptation/domain/service/internal/OrphanJobPollFallback.java)),
  so these jobs **leak forever** as stuck-RUNNING rows.

- **#3 — placeholder `userId` (prod correctness).** `RecipeCreatedEvent`
  ([event](../../src/main/java/com/example/mealprep/recipe/event/RecipeCreatedEvent.java)) carries only
  `(recipeId, catalogue, traceId, occurredAt)` — it drops `userId` and `dataQuality` even though
  `RecipeServiceImpl.createRecipeInternal` has both at publish time. So `AdaptationImportListener`
  ([:46-56](../../src/main/java/com/example/mealprep/adaptation/domain/service/internal/AdaptationImportListener.java))
  runs adaptation with a **placeholder `userId = recipeId`** and a hardcoded `AI_GENERATED` quality →
  user-recipe adaptation loads the wrong (non-existent) user's context and can't actually personalise.

- **#4 — unbounded default async executor (systemic robustness).** The app has `@EnableAsync` but **no
  bounded default executor**; because it defines custom pools (discovery, feedback), Spring Boot's
  auto-configured bounded `applicationTaskExecutor` backs off, so every **bare `@Async`** silently
  falls back to the unbounded `SimpleAsyncTaskExecutor` (new thread per task). Three listeners use
  bare `@Async`: `JobReadyEventListener` (adaptation), `RecipeEmbeddingListener` (recipe→embedding),
  `PreferenceDeltaBatchTrigger` (feedback→preference delta) — so a recipe-creation burst spawns
  unbounded threads on multiple pipelines at once.

- **#2 — e2e AI double completeness (test fidelity).** `TestAiService` has no canned `RECIPE_ADAPTATION`
  response, so a by-design prod behaviour (adapt-on-create, usually NO_CHANGE) becomes a hard
  `AiInvalidResponseException` for every recipe in e2e. The suite has never faithfully exercised
  adapt-on-create — it silently fails it as background noise.

**Out of scope** → [02b](02b-trigger1-cost-discipline.md): whether adapt-on-create *should* fire so
eagerly (deterministic pre-filter, BATCH for bulk). This ticket fixes the mechanics; 02b is the
design refinement.

## Behavioural spec

### #1 — terminalise all AI failures in the worker

Invariant: **no exception may leave `processJob` with the job in a non-terminal state.** Broaden the
caught set so `AiInvalidResponseException`, `AiInvalidRequestException`, `AiCostBudgetExceededException`
(and any other `ai.exception.*`) route to `handleFailure(job, <reason>, msg, startMillis)` (which
marks the job FAILED + publishes `AdaptationJobFailedEvent`), exactly as `AdaptationAiUnavailableException`
does today.

Cleanest seam: in `AdaptationLlmInvoker.invoke`, widen the `catch` to the common `ai.exception` base
(check whether `AiException`/similar exists; if not, catch the specific set) and wrap to an
adaptation-module exception that `processJob` already terminalises. **Mapping**: `AiUnavailableException`
+ `AiCostBudgetExceededException` → the existing AI_UNAVAILABLE/deferrable path; `AiInvalidResponseException`
+ `AiInvalidRequestException` → a terminal reason. Check `JobFailureReason` for a suitable value; only
add a new enum constant if none fits, and if you do, check `NotificationKindResolver`'s
`AdaptationJobFailedEvent` filtering (it keys the block-and-prompt surface on `reason = AI_UNAVAILABLE`)
so a new reason doesn't silently drop notifications. Add a unit test that a malformed-response throw
ends with the job FAILED (not RUNNING) and an `AdaptationJobFailedEvent` published once.

### #3 — enrich `RecipeCreatedEvent`

Add `UUID userId` and `DataQuality dataQuality` to the record (keep `recipeId, catalogue, traceId,
occurredAt`). Update the two publish sites (`RecipeServiceImpl` ~lines 807, 990 — both have `userId` +
the `dataQuality` arg in scope) and `AdaptationImportListener.onRecipeCreated` to pass
`event.userId()` + `event.dataQuality()` into `ImportJobRequest` instead of the `recipeId` placeholder
+ hardcoded `AI_GENERATED`. `DataQuality` already lives in `recipe.domain.entity` and the listener
already imports it — no new module boundary. Verify no ArchUnit boundary regression. Compile will flag
any other construction sites (none found outside `RecipeServiceImpl`).

### #4 — bounded default async executor + audit bare `@Async`

Provide a project-wide bounded default executor so bare `@Async` can never go unbounded: a
`@Configuration` implementing `AsyncConfigurer` (or a `@Primary @Bean("applicationTaskExecutor")
ThreadPoolTaskExecutor`) with sane bounds + `CallerRunsPolicy` backpressure (mirror
[`FeedbackAsyncConfig`](../../src/main/java/com/example/mealprep/feedback/config/FeedbackAsyncConfig.java):
core/max small, bounded queue, CallerRuns). Then audit the 3 bare `@Async` listeners
(`JobReadyEventListener`, `RecipeEmbeddingListener`, `PreferenceDeltaBatchTrigger`): each should run on
a bounded pool — either the new default or a dedicated one. Prefer the least-surprising wiring;
document the choice. Confirm `@EnableAsync` location (`MealPrepApplication`) and that exactly one
default is now resolvable for unqualified `@Async`.

### #2 — e2e adaptation stub (test-support)

Register a canned NO_CHANGE `RECIPE_ADAPTATION` response in `TestAiService` (e2e profile) so
adapt-on-create completes as a faithful no-op. Match the `RecipeAdaptationResponse` shape; ensure the
canned result drives the worker to a clean terminal outcome (no pending-change write for the e2e
recipes — verify against the gate path in `processJob` Step 6/7). If a global default per TaskType
isn't how `TestAiService` works, register it the same way the other TaskTypes are primed.

## Definition of done

- `processJob` leaves the job FAILED (never RUNNING) on every AI failure type; unit test proves it.
- `RecipeCreatedEvent` carries `userId` + `dataQuality`; both publish sites + `AdaptationImportListener`
  use them; no placeholder `userId = recipeId`.
- One bounded default executor; the 3 bare `@Async` listeners run bounded; no unqualified `@Async`
  resolves to `SimpleAsyncTaskExecutor`.
- `TestAiService` returns NO_CHANGE for `RECIPE_ADAPTATION`.
- Compile + Spotless + ArchUnit green; adaptation + recipe module IT subsets green locally; full IT
  suite + e2e smoke green on CI. Full local e2e `not @pending` suite is stable (~140s, no adaptation
  WARN flood, no late-scenario timeouts).
- Pitest: cover the new terminal-failure branch + the executor bean.
