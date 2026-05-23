# Ticket: feedback — 01i Retry-Stuck-Classifications Async Sweep

## Summary

Replace the `throw new UnsupportedOperationException("feedback-01g impl pending — see ticket")` at [`src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java:390-393`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java) with the **`@Scheduled` retry sweep** the LLD designs ([`lld/feedback.md:514`](../../lld/feedback.md) "every 2 minutes", [`lld/feedback.md:575`](../../lld/feedback.md) the 5-min/24h thresholds, [`lld/feedback.md:907`](../../lld/feedback.md) the `FeedbackAsyncSweepIT` contract). This is the recovery half of the classifier's graceful-degrade model ([`lld/feedback.md:570-581`](../../lld/feedback.md), [`style-guide.md §AI Service — Graceful Degradation`](../../lld/style-guide.md)): when the AI is unavailable, `FeedbackClassificationListener` reverts the entry to `RECEIVED` ([`FeedbackClassificationListener.java:133-140, 207-217`](../../src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationListener.java)) and walks away — without this sweep, that entry sits in `RECEIVED` forever.

Numbered `01i` (feedback is at 01a–01h after the reverters ticket; this is a standalone scheduled-job leaf in the same phase as `expireOldClarificationQueries`, which it directly mirrors).

Ships:
- A real `retryStuckClassifications()` body — a sweep that re-classifies entries stuck in `CLASSIFYING`/`RECEIVED` for > 5 minutes, and escalates entries stuck > 24h to `FAILED`.
- The `@Scheduled(fixedDelay = ...)` trigger wiring (the LLD says "every 2 minutes").

Closes: **C3** — the last remaining `UnsupportedOperationException` deferral in the feedback module's service. After this, the only `UnsupportedOperationException` throw left in `src/main/java` is the preference applier's `NoopStub` (A1).

**Dependency / ordering**: standalone. Depends only on the merged classification path (`feedback-01c`/`01d` — `FeedbackClassificationListener.classifyEntry` + the router) and the merged repository sweep query (already present — see §Implementation). No new tables.

## Behavioural spec

### The method exists; only the body is a stub

The interface is fixed by [`lld/feedback.md:513-514`](../../lld/feedback.md): `void retryStuckClassifications();` — an admin/sweep entry point, NOT REST-exposed, called from `@Scheduled`. The current body:

```java
@Override
public void retryStuckClassifications() {
  throw new UnsupportedOperationException("feedback-01g impl pending — see ticket");
}
```

Note it currently carries **no `@Scheduled`** annotation (unlike its sibling `expireOldClarificationQueries` at [`FeedbackServiceImpl.java:401-415`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java), which IS `@Scheduled(cron = "0 0 4 * * *", zone = "UTC")`). This ticket adds the schedule trigger and the body.

### The sweep query already exists

[`FeedbackEntryRepository.java:34-36`](../../src/main/java/com/example/mealprep/feedback/domain/repository/FeedbackEntryRepository.java) ships exactly the query this sweep needs:

```java
/** Async sweep: anything stuck in CLASSIFYING beyond the threshold is retried by 01g. */
List<FeedbackEntry> findBySubmissionStatusInAndCreatedAtBefore(
    Collection<SubmissionStatus> statuses, Instant before);
```

No new repository method is required for the basic sweep. **GOTCHA**: this query filters on `createdAt`, not on `lastClassifiedAt`. For the 5-min retry threshold the LLD's intent is "stuck for > 5 min" — `createdAt` is the safe proxy for a never-progressed `RECEIVED` entry, but a `CLASSIFYING` entry that has been retried multiple times needs `lastClassifiedAt` to avoid hammering. **Decision (worth implementer review)**: add a sibling query `findBySubmissionStatusInAndLastClassifiedAtBeforeOrLastClassifiedAtIsNull(...)` (or filter in code on the loaded list) so the 5-min window measures time-since-last-attempt, not time-since-creation. `lastClassifiedAt` is stamped on every terminal/clarification transition ([`updateSubmissionStatusAndLastClassifiedAt`, `FeedbackEntryRepository.java:67-73`](../../src/main/java/com/example/mealprep/feedback/domain/repository/FeedbackEntryRepository.java)) but NOT on the `revertToReceived` path ([`FeedbackClassificationListener.java:207-217`](../../src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationListener.java)) — verify and, if needed, stamp it there too so the retry clock is honest.

### Sweep logic

1. **Schedule**: annotate `retryStuckClassifications()` with `@Scheduled(fixedDelay = 120000)` (2 min, per LLD line 514) — or a config property `mealprep.feedback.retry-sweep.fixed-delay-ms` defaulting to 120000, mirroring the configurability of other scanners. Keep it on the service method (matches `expireOldClarificationQueries`).
2. **Thresholds (config-overridable, deterministic via the injected `Clock`)**:
   - `STUCK_THRESHOLD = Duration.ofMinutes(5)` — entries older than this in `RECEIVED`/`CLASSIFYING` are re-classified.
   - `ESCALATION_THRESHOLD = Duration.ofHours(24)` — entries older than this are escalated to `FAILED` instead of retried.
   - Held in a `@ConfigurationProperties(prefix = "mealprep.feedback.retry-sweep")` record (`stuckAfter`, `escalateAfter`) — mirror the magnitude-properties pattern from [`tickets/nutrition/01i-feedback-target-adjustment.md` §3](../nutrition/01i-feedback-target-adjustment.md).
3. **Time source**: read `clock.instant()` (the injected `Clock` at [`FeedbackServiceImpl.java:95, 129`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java)) — **never** `Instant.now()` (time-bomb gotcha; the existing `expireOldClarificationQueries` is already `Clock`-correct).
4. **Query**: `findBySubmissionStatusInAndCreatedAtBefore(Set.of(RECEIVED, CLASSIFYING), now − STUCK_THRESHOLD)` (plus the `lastClassifiedAt` refinement from §"sweep query" above).
5. **Per-entry dispatch** (do NOT process the whole list in one tx — mirror the `expireOldClarificationQueries` loop at [`FeedbackServiceImpl.java:408-414`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java), which catches per-item `RuntimeException`, logs WARN, and continues to the next):
   - If `now − entry.createdAt > ESCALATION_THRESHOLD` → escalate to `FAILED`: `entryRepository.updateSubmissionStatusAndLastClassifiedAt(id, FAILED, now)` and publish a terminal `FeedbackProcessedEvent(id, userId, Set.of(), failed=true, clarificationPending=false, traceId, now)` (mirror `FeedbackClassificationListener.markFailed` at [`FeedbackClassificationListener.java:219-234`](../../src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationListener.java) so the user's confirmation view + downstream observers see the terminal state). The entry stays in the table for postmortem per [`lld/feedback.md:576`](../../lld/feedback.md).
   - Else → **re-classify**: re-trigger the classification path. **Two options (worth implementer review)**:
     - **(a) Re-publish `FeedbackSubmittedEvent`** (the same mechanism `answerClarificationQuery` uses to re-trigger — [`FeedbackServiceImpl.java:378-384`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java)). The AFTER_COMMIT `@Async` listener picks it up, flips `RECEIVED → CLASSIFYING` + increments attempts, and runs the AI call off the scheduler thread. **Recommendation: this option** — it reuses the entire proven async pipeline (`FeedbackClassificationListener.onFeedbackSubmitted` → `classifyEntry`), keeps the AI call off the `@Scheduled` thread, and re-uses the SAME `traceId` (`entry.getTraceId()`) so the decision log stays linked (exactly as `answerClarificationQuery` does, [`FeedbackServiceImpl.java:383`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java)). The event MUST be published inside an active tx so the AFTER_COMMIT listener fires (the silent-drop gotcha noted throughout the listener).
     - **(b) Call `classificationListener.classifyEntry(id, userId, traceId)` directly** — synchronous on the sweep thread; risks a slow AI call stalling the scheduler and re-implements the increment/CLASSIFYING flip. **Not recommended.**
6. **Idempotency / self-throttle**: a `CLASSIFYING` entry that is genuinely mid-flight (another thread is in the AI call) must not be double-dispatched. The `createdAt`/`lastClassifiedAt` window plus re-publishing `FeedbackSubmittedEvent` (whose listener flips to `CLASSIFYING` + increments attempts in a `REQUIRES_NEW` tx — [`FeedbackClassificationListener.java:112-124`](../../src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationListener.java)) makes a duplicate dispatch a re-attempt, not a corruption. The 5-min window is wide enough that a genuinely in-flight classification (seconds) is never swept. Document this; no DB lock needed in single-instance v1.
7. **Attempt cap (worth implementer review)**: `classificationAttempts` is incremented on every CLASSIFYING start. Consider escalating to `FAILED` once `classificationAttempts` exceeds a cap (e.g. 12 ≈ 24h at 2-min cadence) as a belt-and-braces alongside the 24h time threshold — but the LLD specifies the **time** threshold (24h) as primary, so keep time as the gate and treat the attempt count as a secondary log signal.

### Parser-failure exclusion

8. Per [`lld/feedback.md:579`](../../lld/feedback.md): a terminal `AiInvalidResponseException`/`AiInvalidRequestException` already transitions the entry straight to `FAILED` (not `RECEIVED`) in the listener ([`FeedbackClassificationListener.java:141-150`](../../src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationListener.java)) — "the prompt is the bug, not the runtime." Because such entries are already `FAILED`, the sweep's `RECEIVED`/`CLASSIFYING` status filter **naturally excludes them** — no extra guard needed. Confirm in a test that a parser-failed entry is never re-classified by the sweep.

### Cross-cutting

9. **No new exception, no new REST surface, no OpenAPI change** — `retryStuckClassifications` is `@Scheduled`-only, exactly like `expireOldClarificationQueries`.
10. **Self-invocation gotcha**: `expireOldClarificationQueries` delegates each item to a sibling bean (`ClarificationExpirer`) for its `REQUIRES_NEW` boundary ([`FeedbackServiceImpl.java:399, 410`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java)) to dodge Spring's self-invocation proxy gotcha. If the re-publish option (5a) is used, no inner-tx-per-item is needed (the publish rides the sweep method's tx and the async listener owns the real work) — but the escalate-to-FAILED branch DOES write + publish, so it needs an active tx. **Decision**: make `retryStuckClassifications()` `@Transactional` for the escalation writes + event publish (so AFTER_COMMIT listeners fire), and re-publish `FeedbackSubmittedEvent` for the retry branch within that same tx. If per-item isolation is wanted (one poison entry shouldn't roll back the others' escalations), delegate each item to a sibling `@Component` with `@Transactional(REQUIRES_NEW)` — mirror the `ClarificationExpirer` pattern. **Recommendation: a sibling `StuckClassificationRetrier` bean** with a `REQUIRES_NEW` `retryOne(feedbackId)` for parity with the existing sweep and per-item isolation.
11. **`@EnableScheduling`** is already on (the clarification sweep + the notification scanners run) — no config change.

### Events

12. **Published**: `FeedbackProcessedEvent` (terminal, on 24h escalation — `failed=true`) and `FeedbackSubmittedEvent` (re-classify trigger, on the retry branch). **Consumed**: none directly (the re-published `FeedbackSubmittedEvent` is consumed by the existing `FeedbackClassificationListener`).

## Database

```
(none — no schema changes. Uses the existing feedback_entries table + the existing findBySubmissionStatusInAndCreatedAtBefore query. The optional lastClassifiedAt-based query is a new repo method, not a schema change.)
```

## OpenAPI updates

**No OpenAPI changes.** `retryStuckClassifications` is a `@Scheduled` internal job (no HTTP surface), exactly like `expireOldClarificationQueries`.

## Edge-case checklist

- [ ] **Stuck RECEIVED re-classified**: an entry in `RECEIVED` created 6 min ago (`AiUnavailable` simulated on the first attempt) → next sweep re-publishes `FeedbackSubmittedEvent` → listener flips to `CLASSIFYING`, increments attempts, runs the (now-healthy) classifier → routes. (This is the [`FeedbackAsyncSweepIT`](../../src/test/java/com/example/mealprep/feedback) contract, [`lld/feedback.md:907`](../../lld/feedback.md).)
- [ ] **Stuck CLASSIFYING re-classified**: an entry left in `CLASSIFYING` (e.g. a crashed worker) older than 5 min → swept and re-dispatched.
- [ ] **Fresh entry not swept**: an entry created 2 min ago is NOT picked up (under the 5-min threshold).
- [ ] **24h escalation to FAILED**: an entry stuck in `RECEIVED` for > 24h → escalated to `FAILED`, `lastClassifiedAt` stamped, terminal `FeedbackProcessedEvent(failed=true)` published exactly once; the row remains for postmortem.
- [ ] **Boundary**: an entry at exactly 24h − ε is retried; at 24h + ε is escalated (deterministic via `Clock.fixed`).
- [ ] **Parser-failure excluded**: an entry already `FAILED` from `AiInvalidResponseException` is NEVER re-classified (status filter excludes it). Verified.
- [ ] **Already-progressed not touched**: an entry that moved to `ROUTED`/`CLARIFICATION_PENDING`/`PARTIALLY_FAILED` between query and dispatch is a no-op (status filter / re-check excludes it).
- [ ] **Same traceId**: the re-published `FeedbackSubmittedEvent` carries `entry.getTraceId()` (NOT a fresh UUID) — the decision log stays linked across attempts (mirrors `answerClarificationQuery`).
- [ ] **Clock-driven**: the sweep reads `clock.instant()`, never `Instant.now()` (grep-verified).
- [ ] **Failure isolation**: an exception dispatching one entry is caught + logged WARN + the sweep continues to the next (mirrors `expireOldClarificationQueries`).
- [ ] **AFTER_COMMIT publish**: the escalation `FeedbackProcessedEvent` and the retry `FeedbackSubmittedEvent` are published inside an active tx (no silent drop).
- [ ] **No double-dispatch of in-flight**: a genuinely mid-AI-call `CLASSIFYING` entry (seconds old) is never swept (5-min window); a re-published event for a swept entry is an idempotent re-attempt, not corruption.
- [ ] **Cross-tenant**: the sweep is system-wide (no user scope) but each re-classification is scoped to the entry's own `userId`.
- [ ] **`FeedbackAsyncSweepIT`** (new, named per [`lld/feedback.md:907`](../../lld/feedback.md)): stuck RECEIVED (> 5 min, AiUnavailable) re-enters the classifier on the next sweep; stuck > 24h escalates to FAILED.

## Files this ticket touches

```
MOD   src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java                          (implement retryStuckClassifications + @Scheduled trigger; inject the retrier/publisher)
NEW   src/main/java/com/example/mealprep/feedback/domain/service/internal/StuckClassificationRetrier.java          (sibling bean — REQUIRES_NEW per-item, mirrors ClarificationExpirer)   [recommended option]
NEW   src/main/java/com/example/mealprep/feedback/config/FeedbackRetrySweepProperties.java                         (@ConfigurationProperties — stuckAfter / escalateAfter / fixedDelay)
MOD   src/main/java/com/example/mealprep/feedback/domain/repository/FeedbackEntryRepository.java                   (ONLY IF the lastClassifiedAt-based query refinement is taken)
MOD   src/main/java/com/example/mealprep/feedback/domain/service/internal/FeedbackClassificationListener.java      (ONLY IF revertToReceived must stamp lastClassifiedAt for an honest retry clock)
MOD   src/main/resources/application.properties                                                                    (retry-sweep defaults: fixed-delay 120000, stuck-after 5m, escalate-after 24h)

NEW   src/test/java/com/example/mealprep/feedback/StuckClassificationRetrierTest.java                              (unit — retry vs escalate vs skip, Clock.fixed)
NEW   src/test/java/com/example/mealprep/feedback/FeedbackAsyncSweepIT.java                                        (Testcontainers — the LLD-named sweep IT)
MOD   src/test/java/com/example/mealprep/feedback/FeedbackServiceImplTest.java                                     (replace the UnsupportedOperationException assertion with sweep-behaviour assertions)
```

Total: ~3 new + 2-3 mods. Estimated agent runtime 0.5-1 day (small — the query + async pipeline already exist; the work is the threshold logic, the `Clock.fixed` IT, and the re-publish wiring).

## Dependencies

- **Hard dependency**: `feedback-01c`/`01d` (merged) — `FeedbackClassificationListener`, `classifyEntry`, the router, the AFTER_COMMIT `FeedbackSubmittedEvent` listener, `FeedbackProcessedEvent`, `findBySubmissionStatusInAndCreatedAtBefore`.
- **Soft**: `feedback-01e` (merged) — `answerClarificationQuery`'s re-publish pattern is the template for the retry branch.
- Standalone w.r.t. `01h` (reverters), `nutrition/01i`, etc.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] **Run the full feedback module IT suite locally with Docker** + spotless before pushing. Docker IS available locally. Run ITs in ~3-class batches (Hikari pool-exhaustion flake on big sweeps).
- [ ] CI green (build + spotless + ArchUnit)
- [ ] All edge-case items above ticked
- [ ] grep confirms the `UnsupportedOperationException("feedback-01g impl pending")` throw at `FeedbackServiceImpl.java:392` is gone.
- [ ] PR description traces: AiUnavailable on first attempt → entry RECEIVED → 2-min sweep at T+6min re-publishes `FeedbackSubmittedEvent` (same traceId) → classifier healthy → ROUTED; a sibling entry stuck > 24h → FAILED + terminal event.

## What's NOT in scope

- **The classifier / confidence gate / router** — merged (`feedback-01c`/`01d`); this ticket only re-triggers them.
- **Distributed scheduling** (ShedLock / multi-instance leader election) — single-instance v1; a future ops ticket if horizontal scaling lands.
- **A Micrometer retry/escalation counter** — Actuator/Micrometer is not yet a project dependency (same deferral as the correction counter TODO at [`FeedbackServiceImpl.java:210-211`](../../src/main/java/com/example/mealprep/feedback/domain/service/FeedbackServiceImpl.java)); add when the metrics foundation lands.
- **A user-facing "still working on it" push** beyond the existing confirmation-view banner ([`lld/feedback.md:577`](../../lld/feedback.md)) — notification module concern.
- **The clarification-expiry sweep** (`expireOldClarificationQueries`) — already implemented; untouched except as the structural template.

Squash-merge with: `feat(feedback): 01i — retryStuckClassifications scheduled sweep (re-classify >5min, escalate >24h to FAILED)`
