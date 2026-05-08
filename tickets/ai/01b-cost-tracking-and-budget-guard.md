# Ticket: ai — 01b Cost Tracking + Budget Guard

## Summary

Layer per-user cost-cap enforcement onto the AI dispatcher delivered in 01a. Calls now pre-check against a rolling-window budget; if exceeded, throw and emit `CostBudgetExceededEvent`. `AiCostTrackingService` exposes the read-side for admin dashboards (the actual REST endpoint lands in 01d). No public API change to existing 01a callers.

## Read these first

- [`tickets/ai/01a-dispatcher-and-anthropic.md`](01a-dispatcher-and-anthropic.md) — the foundation this layers onto. The `AiServiceImpl.execute` signature stays the same; you wrap the inner Anthropic call with the budget check.
- [`lld/ai.md`](../../lld/ai.md) §`AiCostTrackingService`, §Configuration (cost-cap properties), §Business Logic Flows (cost cap + budget exceeded path), §Events.
- [`lld/style-guide.md`](../../lld/style-guide.md) — conventions.
- [`lld/implementation-playbook.md`](../../lld/implementation-playbook.md) §Verification model.

Shape references:
- `auth/domain/service/internal/LoginThrottleService.java` — windowed-count query against an audit table; same shape as the budget guard's spend lookup against `ai_call_log`
- `auth/exception/LoginThrottledException.java` + `GlobalExceptionHandler` 429 handler — for the budget-exceeded 429 path

## Behavioural spec

### `AiCostTrackingService` (public interface)

```java
public interface AiCostTrackingService {
  /** Pence (not micropence) spent by this user in the rolling N-day window. */
  BigDecimal pencesSpentBy(UUID userId, Duration window);

  /** Per-task-type breakdown for the same window. */
  Map<TaskType, BigDecimal> pencesSpentByUserPerTaskType(UUID userId, Duration window);

  /** Global last-24h spend — admin observability. */
  BigDecimal pencesSpentGlobalLast24h();
}
```

### `CostBudgetGuard` (internal)

1. Configuration: `mealprep.ai.budget.daily-pence-per-user` (default 50p), `mealprep.ai.budget.window-hours` (default 24).
2. Before every `AiServiceImpl.execute`, query `ai_call_log` for sum of `cost_micro_pence WHERE user_id = ? AND status = 'SUCCEEDED' AND created_at > now() - window`. Convert to pence.
3. If `spent + estimatedCostOfThisCall >= dailyLimit`: throw `AiCostBudgetExceededException(userId, spent, limit)`. Publish `CostBudgetExceededEvent` AFTER_COMMIT.
4. Estimated cost of THIS call: lookup table by `(model_tier, request_token_estimate)`. Use a coarse upper bound; precision lands in the actual call's logged cost.

### `CostCalculator` (internal)

5. Computes `cost_micro_pence` for a completed call from `(model_id, request_tokens, response_tokens)`. Uses Anthropic's published rates per [`lld/ai.md` §Configuration](../../lld/ai.md). Constants per `ModelTier`. Returns `long` micropence.
6. Public method: `long compute(String modelId, int requestTokens, int responseTokens)`.

### Wiring into `AiServiceImpl`

7. Inject `CostBudgetGuard` and `CostCalculator` into `AiServiceImpl`. `execute` becomes:
   ```
   1. record PENDING (existing 01a)
   2. budgetGuard.checkOrThrow(userId, taskType, modelTier)   // NEW
   3. anthropic call (existing)
   4. cost = costCalculator.compute(modelId, reqTokens, respTokens)   // NEW (was hardcoded/zero in 01a)
   5. update SUCCEEDED with cost (existing, now with real value)
   ```
8. The PENDING row is recorded BEFORE the budget check so failed-budget calls still appear in the call log with `status=FAILED, error_kind=BUDGET_EXCEEDED`. Operationally important: ops must be able to see cost spikes that triggered the cap.

### Cross-cutting

9. New exception `AiCostBudgetExceededException` extends `AiException`. Carries `(UUID userId, BigDecimal spent, BigDecimal limit, Duration window)`. Add 429 handler to `GlobalExceptionHandler` with `Retry-After` set to seconds until the oldest counted call exits the window (mirror `LoginThrottledException` handler shape).
10. Add `BUDGET_EXCEEDED` to the `CallErrorKind` enum from 01a.
11. `CostBudgetExceededEvent(userId, spent, limit, window, traceId, occurredAt)` — implements `ScopeChangedEvent` with `scopeKind="ai-budget"`, `scopeId=userId`. Listeners in later modules will throttle the user's other AI features.

## Configuration additions

```properties
mealprep.ai.budget.daily-pence-per-user=50
mealprep.ai.budget.window-hours=24
mealprep.ai.budget.enabled=true                  # disable for dev/test convenience
```

## Edge-case checklist

- [ ] First-time user (zero spend) passes the check
- [ ] Call exactly AT the limit is rejected (limit is exclusive: `spent + estimate >= limit` → reject)
- [ ] FAILED calls don't count against budget (only SUCCEEDED rows are summed) — verified
- [ ] Budget-exceeded call appears in `ai_call_log` with `status=FAILED, error_kind=BUDGET_EXCEEDED`
- [ ] `CostBudgetExceededEvent` published once per rejected call, AFTER_COMMIT
- [ ] `Retry-After` computed from oldest-counted-call exit time, never zero (min 1s)
- [ ] Configuration `mealprep.ai.budget.enabled=false` short-circuits the check (test/dev convenience)
- [ ] Mutation score ≥70% on `CostBudgetGuard`, `CostCalculator`

## Files this ticket touches

```
src/main/java/com/example/mealprep/ai/domain/service/AiCostTrackingService.java       new (interface)
src/main/java/com/example/mealprep/ai/domain/service/internal/CostCalculator.java     new
src/main/java/com/example/mealprep/ai/domain/service/internal/CostBudgetGuard.java    new
src/main/java/com/example/mealprep/ai/domain/service/internal/AiServiceImpl.java      modified (wire guard + calculator into execute)
src/main/java/com/example/mealprep/ai/domain/entity/CallErrorKind.java                modified (add BUDGET_EXCEEDED)
src/main/java/com/example/mealprep/ai/domain/repository/AiCallLogRepository.java      modified (add windowed-sum query)
src/main/java/com/example/mealprep/ai/event/CostBudgetExceededEvent.java              new
src/main/java/com/example/mealprep/ai/exception/AiCostBudgetExceededException.java    new
src/main/java/com/example/mealprep/ai/config/AiProperties.java                        modified (add budget sub-record)
src/main/resources/application.properties                                             modified (add 3 budget defaults)
src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java                 modified (429 handler)
src/test/java/com/example/mealprep/ai/CostBudgetGuardTest.java                        new (unit)
src/test/java/com/example/mealprep/ai/CostCalculatorTest.java                         new (unit; per-tier rate fixtures)
src/test/java/com/example/mealprep/ai/AiServiceImplTest.java                          modified (add budget-exceeded cases; constructor params changed)
src/test/java/com/example/mealprep/ai/CostBudgetIT.java                               new (Testcontainers; full flow incl. event publication)
```

## Dependencies

- **Hard dependency**: `ai-01a` — modifies `AiServiceImpl.execute`, the constructor, and the `CallErrorKind` enum from 01a. Must merge after 01a.
- **No dependency on 01c or 01d** — can ship concurrently with either after 01a lands. 01c modifies a different method (`AiServiceImpl.embed`); 01d touches different files entirely.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally
- [ ] CI green on the PR
- [ ] Mutation score ≥70% on the budget-related classes
- [ ] Edge-case checklist all ticked

Squash-merge with: `feat(ai): 01b — cost tracking + budget guard with 429 + Retry-After`
