# Ticket: planner — 01i Mid-week re-opt coordinator + state-based pinning

## Summary

Implements the **`MidWeekReoptCoordinator`** that re-runs Stage A→B→C scoped to remaining slots of an active plan. Triggered by 01k's listeners; this ticket is the algorithm + state-pinning + idempotency, NOT the listeners. Per `lld/planner.md §Mid-week re-optimisation`.

The coordinator reads the active plan, computes the **pinning set** (slots that must not change), composes a fresh `PlanCompositionContext` with only the remaining slots, invokes the existing Stage A→C pipeline (from 01d/01g), and emits a `ReoptSuggestedEvent` with a `MealPrepPlanReoptSuggestion` aggregate the user can accept/reject. Does NOT mutate the plan in-place; the suggestion is held until the user accepts via 01j's REST API.

## Behavioural spec

1. New class `MidWeekReoptCoordinator` at `com.example.mealprep.planner.domain.service.internal.reopt.MidWeekReoptCoordinator`. `@Component`, package-private. Constructor-injects `PlanRepository`, `PlanCompositionContextBuilder` (helper from 01j; if not yet shipped, factor as constructor dep with a TODO), `BeamSearchEngine`, `RollupBuilder`, `StageCInvoker`, `MealPrepPlanReoptSuggestionRepository`, `ApplicationEventPublisher`, `DecisionLogWriter` (from 01l; treat as `@Lazy` or check for null).

2. **Public method** `Optional<UUID> requestReopt(UUID planId, ReoptTriggerKind trigger, UUID triggerEventId, UUID traceId)`. Returns the suggestion id if a re-opt happened (non-trivial diff); empty if pinning left no degrees of freedom or no better plan emerged.

3. **Active-plan precondition** — load by id, assert `status IN (ACCEPTED, GENERATED, IN_PROGRESS)` per the state machine in 01b. Reject `REJECTED/SUPERSEDED/ABANDONED/COMPLETED` with `PlanNotReoptableException` (400 mapped in `PlannerExceptionHandler`).

4. **Idempotency on `triggerEventId`** — query `MealPrepPlanReoptSuggestion WHERE plan_id=? AND trigger_event_id=?`. If exists, return that suggestion's id without re-running. This makes the listener-side retry safe.

5. **Pinning rule**: a slot is pinned (cannot change) if ANY of:
   - `state IN (COOKED, EATEN, SKIPPED)` (user has acted on it)
   - `state = PROVISIONED` AND today >= slot's `slotDate - lockHoursBeforeSlot` (default 24h; configurable via `planner.mid-week.lockHoursBeforeSlot`)
   - explicit per-slot `pinned=true` flag (user manually pinned)
   - the slot is the trigger's affected slot AND the trigger is `INGREDIENT_OUT_OF_STOCK` (the listener already replaced it; don't second-guess)

6. **No-degrees-of-freedom guard**: if all remaining slots are pinned, log INFO + return `Optional.empty()` without writing a decision-log row or publishing an event. The trigger gets a `ReoptSkippedEvent` (new event in `01b` events list — add to 01b's event registry retroactively via small follow-up note in this ticket's "Migration notes" — or have 01k emit it instead).

7. **Context narrowing** — build `PlanCompositionContext` whose `slotSkeletons` contains ONLY the non-pinned slots; whose `recipePool` is fetched fresh (recipes that became available since the original plan generation will appear); whose `traceId` is the new traceId passed in.

8. **Run Stage A → B → C** using the existing helpers — same code path as initial generation (in 01j). The Stage C prompt receives the ORIGINAL plan's pinned slots as immutable context so the LLM understands constraints.

9. **Diff materiality** — compare Stage C's chosen plan to the ORIGINAL plan's slot assignments. Compute `affectedSlotIds = chosen.assignments.keySet() ∩ unpinnedSlotIds WHERE chosen.recipe != original.recipe`. If empty, return `Optional.empty()` (no material change; do not write a suggestion).

10. **Write `MealPrepPlanReoptSuggestion`** — new entity (see `01b` events: `ReoptSuggestedEvent.suggestionId` references this). Fields: `id, planId, triggerKind, triggerEventId, traceId, decisionId, createdAt, expiresAt (createdAt + 24h), summary, status (PENDING|ACCEPTED|REJECTED|EXPIRED), proposedAssignments (jsonb: List<{slotId, oldRecipeId, newRecipeId, reason}>)`. **If the entity doesn't exist yet**: this ticket creates it via migration `V<ts>__planner_reopt_suggestion.sql`.

11. **Decision-log row** — per LLD's loop-decision-log convention, write one row at `kind=mid_week_reopt` with `inputs={planId, trigger, triggerEventId, pinnedSlotCount, unpinnedSlotCount}` and `outputs={suggestionId, affectedSlotIds, summary}`. 01l owns the writer; if 01l hasn't shipped, log a WARN and skip.

12. **Publish `ReoptSuggestedEvent`** (already in 01b's event list) AFTER_COMMIT. Listeners can route to notifications (out of scope for 01i).

13. **`@Transactional`** on `requestReopt` — propagation `REQUIRED` (called from 01k's listeners which run in `REQUIRES_NEW`; here we participate). NO `@Transactional` on the helper methods.

14. **Bounded re-opt budget** — config `planner.mid-week.maxSuggestionsPerPlan` (default 3). If a plan already has 3 PENDING+REJECTED suggestions, the next `requestReopt` writes a decision-log row noting the rejection-by-budget and returns `Optional.empty()`. Prevents trigger thrashing.

15. **Trace propagation** — the `triggerEventId` MUST flow into the new `decisionId` chain via `parent_decision_id` on the decision-log row. The listener (01k) passes its own decision id forward.

## Files this ticket touches

```
src/main/resources/db/migration/V20260620120000__planner_reopt_suggestion.sql       new
src/main/java/com/example/mealprep/planner/domain/entity/MealPrepPlanReoptSuggestion.java        new
src/main/java/com/example/mealprep/planner/domain/repository/MealPrepPlanReoptSuggestionRepository.java       new
src/main/java/com/example/mealprep/planner/domain/service/internal/reopt/MidWeekReoptCoordinator.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/reopt/PinningSetCalculator.java        new
src/main/java/com/example/mealprep/planner/exception/PlanNotReoptableException.java        new
src/main/java/com/example/mealprep/planner/config/PlannerProperties.java        modified  (add mid-week sub-config)
src/test/java/com/example/mealprep/planner/MidWeekReoptCoordinatorTest.java        new  unit, stub Stage A→C helpers
src/test/java/com/example/mealprep/planner/MidWeekReoptFlowIT.java        new  end-to-end with real Postgres + TestAiService
```

## Dependencies

- **Hard dependency**: `planner-01a` (merged) — `Plan`, `MealPrepSlot`, `SlotState` enum.
- **Hard dependency**: `planner-01b` (merged) — `PlanStateMachine` (read-only here; we don't transition the plan, only suggest).
- **Hard dependency**: `planner-01d` (merged) — `BeamSearchEngine`, `PlanCompositionContext`.
- **Hard dependency**: `planner-01f` (merged) — `RollupBuilder`.
- **Hard dependency**: `planner-01g` (merged) — `StageCInvoker`.
- **Soft dependency**: `planner-01j` — `PlanCompositionContextBuilder`. If not yet merged, factor it as a constructor dep + ship a minimal in-line builder in 01i tests.
- **Soft dependency**: `planner-01l` — `DecisionLogWriter`. Treat null-tolerantly until it lands.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] All 15 invariants ticked
- [ ] Pinning-set test covers: cooked/eaten/skipped, lock-hours-window, explicit pin, ingredient-out-of-stock trigger affected slot
- [ ] Idempotency test: same `triggerEventId` twice returns same suggestion id
- [ ] No-degrees-of-freedom test: all pinned → empty Optional
- [ ] Diff-materiality test: stage C returns identical plan → empty Optional
- [ ] Budget test: 4th request with 3 PENDING already → empty Optional + decision-log note

Squash-merge with: `feat(planner): 01i — MidWeekReoptCoordinator + pinning + suggestion entity + decision-log row`

## What's NOT in scope

- The listeners that CALL `requestReopt` → **planner-01k**
- The REST endpoints to view/accept/reject suggestions → **planner-01j** (the `PlansController.acceptReoptSuggestion` endpoint)
- Notifications routing → out of wave 3
- The full decision-log row writer → **planner-01l** (this ticket calls into it null-tolerantly)
- Materiality threshold beyond "same recipe" — score-delta materiality could be added later; v1 is recipe-identity comparison

## Gotchas to bake in

- **`@TransactionalEventListener + @Transactional`** is NOT used here — this ticket is invoked FROM listeners (in 01k). The listeners use `REQUIRES_NEW`; we use `REQUIRED` to join. Round-7 retro rule.
- **Don't load the entire recipe catalogue** for the re-opt context — `RecipePoolSnapshot` (from 01d) supports filtering by household + active constraints. Reuse the existing pool builder.
- **Pin computation must read `slot.state`, NOT trust the plan-level status** — a plan can be `ACCEPTED` while individual slots are `COOKED`. The state machine of slots is independent.
- **Suggestion expiry**: 24h is a default; the sweep is in 01k or a separate cron. For 01i, ship the `expiresAt` field and a `scheduled` boolean flag; the sweep ticket (`01l` or follow-up) flips expired to `EXPIRED`.
