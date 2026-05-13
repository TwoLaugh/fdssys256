# Ticket: planner — 01k Event listeners + materiality filters + mid-week trigger paths

## Summary

Wires the planner's reactive surface: listeners for `ProvisionChangedEvent` (provisions-01g), `NutritionIntakeDivergedEvent` (nutrition-01h), `PreferenceUpdatedEvent` (preference-01a), `HouseholdConfigChangedEvent` (household-01b). Each listener applies a **materiality filter** (skip events that don't affect any active plan), then calls `MidWeekReoptCoordinator.requestReopt(...)` from 01i with the appropriate `ReoptTriggerKind`. Also handles `AdaptationJobCompletedEvent` / `AdaptationJobFailedEvent` from the adaptation-pipeline for plans that emitted Stage D directives. Per `lld/planner.md §Event listeners`, §Materiality filters.

## Behavioural spec

1. New class `PlannerEventListener` at `com.example.mealprep.planner.domain.service.internal.listeners.PlannerEventListener`. `@Component`, package-private. Constructor-injects: `PlanRepository`, `MidWeekReoptCoordinator` (01i), `ProvisionMaterialityFilter`, `NutritionMaterialityFilter`, `PreferenceMaterialityFilter`, `HouseholdMaterialityFilter`, `Clock`, `DecisionLogWriter` (01l, null-tolerant), `ApplicationEventPublisher`.

2. **All 4 trigger-listener methods follow this shape** — round-7 retro rule:
   ```java
   @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
   @Transactional(propagation = Propagation.REQUIRES_NEW)
   public void onProvisionChanged(ProvisionChangedEvent event) { ... }
   ```
   **MUST be `REQUIRES_NEW` or `NOT_SUPPORTED`** — `REQUIRED` and `SUPPORTS` fail context-load fast.

3. **`onProvisionChanged(ProvisionChangedEvent event)`**:
   - Resolve `householdId` from the event (sealed event base; the provision module's event carries `userId` → resolve household via `HouseholdQueryService.getCurrentHouseholdForUser`).
   - Query active plans: `planRepository.findActiveByHouseholdId(householdId)` (plans in GENERATED/ACCEPTED/IN_PROGRESS).
   - For each plan, ask `provisionMaterialityFilter.isMaterial(event, plan)` → true if the changed inventory item appears in any non-pinned remaining slot's recipe ingredients OR (event is `ItemRanOut` AND any remaining slot uses it).
   - For each material plan: `midWeekReoptCoordinator.requestReopt(plan.id, ReoptTriggerKind.PROVISION_CHANGED, event.eventId, traceId)`.
   - Log at INFO with planId, materialResult, suggestionId.

4. **`onNutritionIntakeDiverged(NutritionIntakeDivergedEvent event)`** (from nutrition-01h):
   - Resolve household from `event.userId`.
   - Active plans for the affected ISO week (`event.weekStartDate`): filter `plans.weekStartDate == event.weekStartDate`.
   - Materiality: `nutritionMaterialityFilter.isMaterial(event, plan)` → true if the divergence magnitude exceeds threshold (configurable, default 15%) AND the plan still has ≥3 unplanned/unpinned meals to redistribute over.
   - `requestReopt(..., ReoptTriggerKind.NUTRITION_DIVERGED, ...)`.

5. **`onPreferenceUpdated(PreferenceUpdatedEvent event)`**:
   - Materiality: `preferenceMaterialityFilter.isMaterial(event, plan)` → true if the update touched a hard constraint (allergy add/remove, dietary identity change) OR a high-impact soft preference (>10 delta points on cuisine/protein bucket).
   - **Pure-soft small-delta updates are NON-material** — surfacing them as re-opt would create thrash. Log DEBUG and skip.
   - `requestReopt(..., ReoptTriggerKind.PREFERENCE_CHANGED, ...)`.

6. **`onHouseholdConfigChanged(HouseholdConfigChangedEvent event)`**:
   - Resolve household directly from event.
   - Materiality: changes to slot kinds (added/removed meal type), batch policy, household membership (added/removed user) are material; cosmetic changes (display name, timezone) are not.
   - `requestReopt(..., ReoptTriggerKind.HOUSEHOLD_CONFIG_CHANGED, ...)`.

7. **`onAdaptationJobCompleted(AdaptationJobCompletedEvent event)`** — from adaptation-pipeline (forward dep; if not yet shipped, the listener registers but body no-ops until the event class exists):
   - Filter: only act if the job's `parentDecisionId` traces back to a planner-issued `PlanTimeRefineDirectiveRequest`. Read `event.outcomeTargetId` (the new version/branch id).
   - If the originating plan is still in `GENERATING` (rare — composer should have synchronously awaited), update the plan's slot to use the new recipe version.
   - If the plan is already persisted (the common case — composer awaits sync), this listener is a no-op audit log.
   - **Decision-log row**: `kind=stage_d_outcome` linking the planner's parent decision to the adaptation's child decision.

8. **`onAdaptationJobFailed(AdaptationJobFailedEvent event)`**:
   - If `event.reason == AI_UNAVAILABLE` AND originating plan is `GENERATING`: surface a quality warning. (Composer should have caught this already; defensive log.)
   - Decision-log row `kind=stage_d_failure`.

9. **Materiality filters** — 4 new package-private `@Component` classes in `internal/listeners/`:
   - `ProvisionMaterialityFilter` — reads the affected `recipe_ingredients.ingredient_mapping_key` set from the plan's recipes vs the event's inventory item; constructor injects `RecipeQueryService.getIngredientKeysForRecipeIds`.
   - `NutritionMaterialityFilter` — pure math on the event payload; no DB calls.
   - `PreferenceMaterialityFilter` — reads `event.changedFields` (the preference event must carry these per LLD; if not, fallback to "always material" with a TODO).
   - `HouseholdMaterialityFilter` — reads `event.changedFields`.

10. **Idempotency via `triggerEventId`** — every listener passes the originating event's id to `requestReopt`. 01i's coordinator deduplicates. Retries on listener failure are safe.

11. **Listener failures are LOGGED, not propagated** — wrap the body in a try-catch. A single listener throwing must not block the publisher's transaction (it's already committed; AFTER_COMMIT) but it would still emit a stack trace; we want clean WARN logs. Exception: `AiUnavailableException` from downstream stages bubbles up through `requestReopt` and is caught here as a special case (logged as INFO since it's expected).

12. **Test strategy**:
   - Unit tests per materiality filter with hand-crafted plans + events.
   - One `PlannerEventListenerIT` that publishes each of the 4 source events and asserts:
     - Material event → `requestReopt` called with the right trigger kind.
     - Immaterial event → `requestReopt` NOT called.
     - Cross-household event → no plans queried.
   - Use `@RecordApplicationEvents` to verify the listener observed the event.

13. **Performance** — for households with multiple active plans (rare; v1 supports one active plan per household but the listener doesn't assume), iterate plans serially. Each materiality check is bounded constant time given the plan's slot count.

## Files this ticket touches

```
src/main/java/com/example/mealprep/planner/domain/service/internal/listeners/PlannerEventListener.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/listeners/ProvisionMaterialityFilter.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/listeners/NutritionMaterialityFilter.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/listeners/PreferenceMaterialityFilter.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/listeners/HouseholdMaterialityFilter.java        new
src/main/java/com/example/mealprep/planner/domain/service/internal/listeners/AdaptationCallbackHandler.java        new
src/main/java/com/example/mealprep/planner/config/PlannerProperties.java        modified  (materiality threshold configs)
src/test/java/com/example/mealprep/planner/ProvisionMaterialityFilterTest.java        new
src/test/java/com/example/mealprep/planner/NutritionMaterialityFilterTest.java        new
src/test/java/com/example/mealprep/planner/PreferenceMaterialityFilterTest.java        new
src/test/java/com/example/mealprep/planner/HouseholdMaterialityFilterTest.java        new
src/test/java/com/example/mealprep/planner/PlannerEventListenerIT.java        new  full @SpringBootTest, real Testcontainers
```

## Dependencies

- **Hard dependency**: `planner-01a, 01b, 01i` (merged) — entities + state machine + coordinator.
- **Hard dependency**: `provisions-01g` (merged) — `ProvisionChangedEvent`.
- **Hard dependency**: `nutrition-01h` (merged) — `NutritionIntakeDivergedEvent`.
- **Hard dependency**: `preference-01a` (merged) — `PreferenceUpdatedEvent`.
- **Hard dependency**: `household-01b` (merged) — `HouseholdConfigChangedEvent`.
- **Soft dependency**: `adaptation-pipeline-01b` — `AdaptationJobCompletedEvent`, `AdaptationJobFailedEvent`. If unmerged, the two adaptation-callback handler methods can be stubbed-with-TODO or guarded by `@ConditionalOnClass(AdaptationJobCompletedEvent.class)`.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes
- [ ] All 13 invariants ticked
- [ ] Each materiality filter has unit tests with 3+ scenarios (material, immaterial, edge case)
- [ ] `PlannerEventListenerIT` covers all 4 source events × 2 (material/immaterial) = 8 scenarios
- [ ] Idempotency: publishing the same event twice does NOT create two suggestions
- [ ] All `@TransactionalEventListener` methods are `REQUIRES_NEW` (grep before commit)
- [ ] Cross-household isolation test: event for household A does not trigger reopt on household B's plan

Squash-merge with: `feat(planner): 01k — 4 module event listeners + materiality filters + adaptation-callback handler`

## What's NOT in scope

- The `requestReopt` algorithm itself → **planner-01i**
- The REST endpoints for suggestions → **planner-01j**
- Notification routing (telling the user a re-opt happened) → out of wave 3
- Re-opt cron/scheduled sweeps → follow-up

## Gotchas to bake in

- **Round-7 retro rule (CRITICAL)**: every `@TransactionalEventListener` method here is `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Plain `@Transactional` (REQUIRED) FAILS CONTEXT LOAD across ALL ITs. `NOT_SUPPORTED` is also acceptable but `REQUIRES_NEW` matches the listener's need to query active plans (a JPA read needs a tx).
- **Round-8 retro rule**: if a listener body needs to mutate a planner entity that another module's transaction just touched (rare — happens only on adaptation-callback when the plan is still GENERATING), use a native `@Modifying @Query` UPDATE, NOT `findById + setters + saveAndFlush`. The latter races with Hibernate's lingering dirty-check and throws `StaleObjectStateException`.
- **Listener bodies MUST NOT throw uncaught exceptions**. Wrap the entire body in try-catch; log WARN on unexpected; allow `AiUnavailableException` to pass through to the coordinator's own handling.
- **Event class imports**: prefer fully-qualified class references in `@TransactionalEventListener` when the event class is from a wave-3 sibling that may not yet be merged. Spring resolves the listener lazily; if the class is missing at startup the bean fails. Use `@ConditionalOnClass(AdaptationJobCompletedEvent.class)` on the `AdaptationCallbackHandler` bean class.
- **Don't store mutable state on the listener bean** — Spring beans are singletons. All state lives in the events themselves.
- **`PreferenceUpdatedEvent.changedFields`** may not exist yet on the preference module's event — verify in the preference-01a ticket. If missing, materiality filter has a TODO + defaults to "always material" (safer to over-trigger re-opt than to miss a constraint change).
