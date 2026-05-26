# Ticket: grocery — 01g Scheduled Jobs (refresh / status-poll / archival) + Cost-Cap Guardrails — OPTIONAL, LOWEST v1 PRIORITY

> **OPTIONAL / LOWEST PRIORITY FOR v1.** This ticket ships the three `@Scheduled` background jobs +
> the cost-cap pre-flight. **No `@pending` E2E scenario depends on it** — GROC-31 (weekly refresh)
> and GROC-35 (archival) are in the pathway catalogue but are NOT in
> [`grocery.feature`](../../src/e2e/resources/features/grocery/grocery.feature) (no scenarios, not
> even `@pending`). The on-demand refresh + order lifecycle (01c/01e/01f) already cover the
> interactive surface. **Build this last, or defer it entirely past v1** — the manual + on-demand
> paths are the always-available core; scheduled automation is convenience on top of the
> already-deferred provider automation.

## Summary

The background-job layer. Per [LLD §Flow 6 (scheduled) lines 941-953](../../lld/grocery.md),
[LLD §`runScheduledBackgroundRefresh` lines 638-639](../../lld/grocery.md),
[LLD §state machine `reconciled → archived` line 796](../../lld/grocery.md),
[LLD §`PriceFreshnessGuardrails` lines 52-53, 953](../../lld/grocery.md),
[LLD §Concurrency §scheduled fan-out line 981](../../lld/grocery.md),
[LLD §Configuration §SchedulerConfig line 998](../../lld/grocery.md). Ships:

- **Weekly price refresh** — `@Scheduled(cron = "${mealprep.grocery.scheduler.refresh-cron:0 0 4 * * SUN}")`;
  for each user with `scheduled_refresh_enabled`, refresh the top-N most-used ingredients via
  on-demand quote (01c), `@Async` per-user.
- **Hourly order-status poll** — `@Scheduled` advances active `CONFIRMED` orders to `DELIVERED`
  (calls 01e's `markDelivered`); runs the `provider_unavailable` 24-hour retry-then-auto-cancel sweep.
- **Daily archival sweep** — `reconciled → archived` at 12 months past `reconciled_at`.
- **`PriceFreshnessGuardrails`** — the cost-cap-aware `preflight(userId, kind) → ALLOW|SKIP|BLOCK`
  decision + the `CostBudgetExceededEvent` listener that pauses scheduled refresh.
- **`runScheduledBackgroundRefresh`** body (01c shipped the stub).

**Unblocks (E2E):** **none `@pending`.** Completes GROC-31 (scheduled refresh), GROC-35 (archival),
and the GROC-27/GROC-28 scheduled-retry / daily-cap-skip variations from the pathway catalogue —
useful behaviour, but no `@pending` tag flips. The `SchedulerIT` (LLD line 1037) is the coverage.

## LLD-divergence notes

### Depends on the `ai` module's cost-cap service — verify the shipped surface

LLD line 949 / 953 has the scheduled refresh pre-flight `AiCostBudgetService.hasDailyCapacity(userId)`
([`lld/ai.md`](../../lld/ai.md)) and consume `CostBudgetExceededEvent` (published by the ai module).
**Verify both exist on the shipped `ai` module surface** before building. If the cost-cap service
isn't cross-module accessible, 01g's guardrail degrades to "always ALLOW on-demand, always SKIP
scheduled when a feature flag is off" and flags the missing dependency. **Worth user review.**

### Real provider automation is still deferred — the scheduled refresh runs against the Fake in tests

The weekly refresh calls `refreshOnDemand(useProviderQuote=true)` which calls `provider.quote`. With
no real `TescoGroceryProvider` (deferred), the scheduled refresh has **no real provider to quote in
production** — it's exercised against `FakeGroceryProvider` in `SchedulerIT` only. This reinforces
why 01g is optional/deferrable: it's automation layered on top of the already-deferred provider
automation. **Worth user review** — shipping the scheduler with no real provider means it's
effectively dormant until the Tesco ticket lands; consider deferring 01g until then.

## Behavioural spec

### Weekly refresh (LLD lines 947-952)

`@Scheduled(cron = "${mealprep.grocery.scheduler.refresh-cron:0 0 4 * * SUN}")`. For each user with
`scheduled_refresh_enabled = true` (`GroceryProviderStateRepository.findAllByScheduledRefreshEnabledTrue`):
1. **Pre-flight** `PriceFreshnessGuardrails.preflight(userId, SCHEDULED)`: `AiCostBudgetService.hasDailyCapacity(userId)`
   false → `SKIP` + log INFO (the HLD's "scheduled refresh is the FIRST thing skipped when the daily
   cap is approached"). Monthly cap → `BLOCK` (all users skip until reset).
2. Top-N most-used keys via `RecipeQueryService.getTopUsedIngredientKeys(userId, lookbackWeeks=12,
   n=refresh_top_n_ingredients)` (verify the method on the shipped recipe surface; default n from
   `GroceryProviderState.refresh_top_n_ingredients`, 50).
3. `refreshOnDemand(userId, keys, useProviderQuote=true)` (01c).
`@Async` per-user (LLD line 981) so one user's refresh doesn't block the scheduler thread; each
user's refresh is its own bounded transaction.

### Hourly order-status poll (LLD line 911, 923)

`@Scheduled(cron = "${mealprep.grocery.scheduler.order-status-cron:0 0 * * * *}")`:
- Active `CONFIRMED` orders → `refreshStatus`/`markDelivered` when the provider reports delivery
  (advances `CONFIRMED → DELIVERED`, triggering 01f's substitution persistence + reconcile-or-block).
- `PROVIDER_UNAVAILABLE` orders → re-run `quote` once an hour for up to
  `GroceryConfig.order.providerUnavailableRetryHours` (24); after 24 failed hours →
  auto-cancel with `cancel_reason = "provider_unavailable_24h"` (LLD line 923, GROC-27 persistent
  variation).

### Daily archival sweep (LLD line 796)

`@Scheduled(cron = "${mealprep.grocery.scheduler.archive-cron:0 0 5 * * *}")`: `reconciled → archived`
where `reconciled_at < now − 12 months`. Archived orders excluded from default `getMyOrders` queries
(the repo's `findAllByUserIdAndStatusNotIn` already excludes `ARCHIVED`). Manual archiving is not
exposed (LLD line 796). GROC-35 boundary: exactly 12 months → archived; 11 months → not.

### `PriceFreshnessGuardrails` (LLD lines 52-53, 953)

`preflight(userId, RefreshKind kind) → ALLOW | SKIP | BLOCK`. Daily-cap-approached → `SKIP` for
`SCHEDULED`, `ALLOW` for `ON_DEMAND` (the user invoked it deliberately — `AiUnavailableException`
surfaces naturally per 01c). Monthly cap → `BLOCK` everywhere. The `onCostBudgetExceeded`
listener (LLD line 849) sets `scheduled_refresh_enabled = false` for affected user state until the
cap resets:

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
@Transactional(propagation = REQUIRES_NEW)
public void onCostBudgetExceeded(CostBudgetExceededEvent event) { /* pause scheduled refresh */ }
```

## Edge-case checklist

- [ ] Weekly refresh runs only for `scheduled_refresh_enabled = true` users
- [ ] Daily cap approached → scheduled refresh SKIPs (logged INFO); on-demand still ALLOWed
- [ ] Monthly cap → BLOCK everywhere; `onCostBudgetExceeded` flips `scheduled_refresh_enabled = false`
- [ ] Fewer than N distinct recent ingredients → smaller batch (GROC-31 variation)
- [ ] `@Async` per-user — one user's failure doesn't abort the run; each is its own transaction
- [ ] Hourly poll advances `CONFIRMED → DELIVERED` on provider-reported delivery (triggers 01f flow)
- [ ] `PROVIDER_UNAVAILABLE` retried hourly; after 24h → auto-cancel `provider_unavailable_24h` (GROC-27)
- [ ] Archival: exactly 12 months past `reconciled_at` → `ARCHIVED`; 11 months → not (GROC-35 boundary)
- [ ] Archived excluded from default `getMyOrders`
- [ ] `SchedulerIT`: status job + archive sweep + refresh-respects-flag-and-cap, all against `FakeGroceryProvider`
- [ ] Missing `ai` cost-cap surface → guardrail degrades gracefully + flagged (no hard crash)

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryScheduledJobs.java       (@Scheduled refresh + status + archive)
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/PriceFreshnessGuardrails.java
NEW   src/main/java/com/example/mealprep/grocery/domain/service/internal/CostBudgetExceededListener.java
MOD   src/main/java/com/example/mealprep/grocery/domain/service/internal/GroceryServiceImpl.java          (fill runScheduledBackgroundRefresh)
MOD   src/main/resources/application.yml                                                                  (scheduler cron defaults already in GroceryConfig; confirm)
NEW   src/test/java/com/example/mealprep/grocery/PriceFreshnessGuardrailsTest.java
NEW   src/test/java/com/example/mealprep/grocery/SchedulerIT.java
MOD   src/test/java/com/example/mealprep/grocery/testdata/GroceryTestData.java
```

**Does NOT touch:** the `ai` module (reads `AiCostBudgetService` + consumes `CostBudgetExceededEvent`
via its public surface only); real provider automation (deferred); other-module production code.

## Dependencies

- **Hard:** grocery-01a (config, provider-state repo); grocery-01c (`refreshOnDemand`,
  `PriceAggregator`); grocery-01e (`markDelivered`, `quote`, order lifecycle); grocery-01f
  (`tryReconcile` — the status poll triggers delivery → reconcile).
- **Hard (cross-module, VERIFY):** `ai` module `AiCostBudgetService.hasDailyCapacity` +
  `CostBudgetExceededEvent`; `RecipeQueryService.getTopUsedIngredientKeys`.
- **Deferred (NOT a dependency):** real `TescoGroceryProvider` — the scheduler is effectively dormant
  in production until it lands; consider deferring 01g with it.

## Acceptance / DoD

- [ ] `verify` + `spotless` clean; CI green; all edge cases ticked
- [ ] `SchedulerIT` green against `FakeGroceryProvider`
- [ ] Daily-cap SKIP / monthly-cap BLOCK behaviour correct; on-demand unaffected
- [ ] `GroceryBoundaryTest` passes; no `ai`/recipe production file touched
- [ ] Report whether the `ai` cost-cap surface exists; if not, the degraded guardrail is in place

Squash-merge with: `feat(grocery): 01g — scheduled refresh + order-status poll + archival sweep + cost-cap guardrails (optional)`

## What's NOT in scope

- Real provider automation — the scheduler quotes against `FakeGroceryProvider` only until the Tesco
  ticket lands.
- 12-month observation compaction (LLD line 321) → v2 / a future migration (distinct from the order
  archival sweep this ticket ships).
- Any new interactive endpoint — all three jobs are `@Scheduled`, no REST surface (LLD line 740).
