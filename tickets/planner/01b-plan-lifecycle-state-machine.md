# Ticket: planner — 01b Plan Lifecycle State Machine + Transition Guards + Plan Events

## Summary

Pure-logic ticket that layers the **plan + slot state machine** on top of 01a. Ships `PlanStateMachine` (package-private under `planner/domain/service/internal/lifecycle/`), the `PlanGenerationCounter` helper (per-`(household, week)` generation increment), the four new transition exceptions (`InvalidPlanStateTransitionException`, `InvalidSlotStateTransitionException`, `RevertTargetNotInHistoryException`, `ConcurrentGenerationInProgressException`), and **the planner event hierarchy** (`PlannerEvent` sealed marker + `PlanGeneratedEvent`, `PlanAcceptedEvent`, `PlanSupersededEvent`, `PlanCompletedEvent`, `PlanRejectedEvent`, `PlanAbandonedEvent`, `ReoptTriggeredEvent`, `ReoptSuggestedEvent`). Per `lld/planner.md` §Entities (PlanStatus + SlotState transition tables), §Business Logic Flows (allowed transitions), §Events §Published, §Failure Modes, §Concurrency.

01b is a **prereq enabler** — it defines the contracts that 01i (re-opt) and 01j (write controllers) depend on but adds no controller surface of its own. The state machine is a pure function tested in isolation against the LLD's allowed-transition matrix.

**Defers**:
- Calling `PlanStateMachine` from controllers → **planner-01j** (controllers ticket)
- Publishing the planner events at the right transactional moments → **planner-01j** (writes) and **planner-01i** (mid-week re-opt)
- `PlanGenerationCounter` consumption by the generation flow → **planner-01j**
- `@ValidSlotState` Jakarta validator (binds the controller request to the state machine) → **planner-01j**

## LLD divergence — generation counter location

The LLD §Package Layout puts `PlanGenerationCounter` under `internal/lifecycle/`. **01b ships it there.** **Worth user review**: alternative is to fold the logic into `PlannerServiceImpl.generatePlan` since it's a 2-line query (`countByHouseholdIdAndWeekStartDate` + 1). Rejected because (a) the LLD names it as a dedicated helper, (b) a separate class makes the test trivial (`PlanGenerationCounterTest` per LLD §Test Plan line 1383), and (c) the same helper is reused by revert (Flow 4) where the logic must match exactly.

## LLD divergence — `PlanSupersededEvent` payload

LLD §Events line 1109 declares `PlanSupersededEvent(planId, replacedByPlanId, householdId, weekStartDate, traceId, occurredAt)`. **01b ships the record verbatim**. **Worth user review**: the event from the OLD plan's perspective carries `replacedByPlanId` (the new plan's id). The 01i re-opt flow needs to publish this event for the OLD plan when the new plan is promoted. The handler / listener (notification module, grocery module) reads `planId` as "the old plan" and `replacedByPlanId` as "the successor". This shape is what the LLD locks in; 01b doesn't second-guess.

## Behavioural spec

### `PlanStateMachine` — pure transition logic

1. New class `PlanStateMachine` at `com.example.mealprep.planner.domain.service.internal.lifecycle.PlanStateMachine`. **Package-private**. Stateless — a Spring bean by default (`@Component`) so tests can `@Autowired` it directly, but the public API is two pure static-friendly methods (the bean wrapper exists only for DI consistency).
2. **Plan-level transitions** per LLD §Business Logic Flows §Flow 3 + §Entities enum-table:

   ```
   DRAFT      → GENERATED                              (generation flow's first state, post-Stage-D persist)
   GENERATED  → ACTIVE     | REJECTED                  (user accept / reject; revert lands in GENERATED too)
   ACTIVE     → SUPERSEDED | ABANDONED | COMPLETED     (re-opt / user abandon / weekly sweep)
   SUPERSEDED — terminal
   COMPLETED  — terminal
   REJECTED   — terminal
   ABANDONED  — terminal
   ```

   **01b's `assertPlanTransitionAllowed(PlanStatus current, PlanStatus next)`** is a pure method. Returns `void` on success; throws `InvalidPlanStateTransitionException` on violation (with message `"plan transition not allowed: <current> -> <next>"`).
3. **`isPlanTransitionAllowed(current, next)`** is the boolean form (used by tests and controller's pre-check before the actual transition).
4. **Idempotency rule** per LLD §Flow 3: re-rejecting a `REJECTED` plan is **not** a no-op at the state-machine level — the controller decides idempotency separately. State machine raises `InvalidPlanStateTransitionException` on `REJECTED → REJECTED`. The LLD reads "Idempotent — re-rejecting a rejected plan is a no-op" for `rejectPlan`; **01b implements this idempotency at the controller layer (in 01j)**, not in the state machine. The state machine is pure; idempotency is a service-layer concern.

### `PlanStateMachine` — slot transitions

5. **Slot-level transitions** per LLD §Flow 5:

   ```
   PLANNED → COOKING | SKIPPED
   COOKING → COOKED  | SKIPPED
   COOKED  → EATEN
   EATEN, SKIPPED — terminal
   ```

   **`assertSlotTransitionAllowed(SlotState current, SlotState next)`** — pure method, throws `InvalidSlotStateTransitionException`.
6. **`allowedSlotNextStates(SlotState current)`** — returns `Set<SlotState>` of legal next states. Empty set for terminal states. Used by the future `@ValidSlotState` Jakarta validator (in 01j) to return 400 instead of 409 for clearly-illegal transitions at the request-validation stage.

### `PlanStateMachine` — pinning derivation

7. **`derivePinnedReason(SlotState state)`** — pure method returning `Optional<PinnedReason>` per LLD §Pinning Rules table:

   | Slot state | PinnedReason |
   |---|---|
   | `EATEN` | `EATEN` |
   | `COOKED` | `COOKED` |
   | `COOKING` | `COOKING` |
   | `SKIPPED` | `SKIPPED` |
   | `PLANNED` | `Optional.empty()` (regenerable) |

   This helper is consumed by `PinningRules` (01i) and by `markSlotState` (01j). 01b ships the function; both downstream tickets call it. **Worth user review**: alternative is to ship it inside `PinningRules`. Rejected because the same mapping is needed by `markSlotState` which doesn't depend on `PinningRules`; co-locating with `PlanStateMachine` keeps the lifecycle logic in one file.
8. **`USER_PINNED` is set externally** (only by a future user-pinning UI flow). 01b does **not** derive it from any state — it's the only `PinnedReason` whose source isn't `SlotState`.

### `PlanGenerationCounter`

9. New class `PlanGenerationCounter` at `internal/lifecycle/PlanGenerationCounter.java`. `@Component` package-private. Single dependency: `PlanRepository`.
10. **`int nextGenerationFor(UUID householdId, LocalDate weekStartDate)`** — returns `1 + planRepository.countByHouseholdIdAndWeekStartDate(householdId, weekStartDate)`. **`@Transactional(readOnly = true)`**. The `countBy...` method is already in 01a's `PlanRepository`.
11. **`Optional<UUID> currentActivePlanIdFor(UUID householdId, LocalDate weekStartDate)`** — used by the generation flow (01j) to set `replacesPlanId`. **Defer the impl** — this requires `findFirst...AndStatus(ACTIVE)` which 01a deferred. **01b extends 01a's `PlanRepository`** with one new method:
    ```java
    Optional<Plan> findFirstByHouseholdIdAndWeekStartDateAndStatus(UUID householdId, LocalDate weekStartDate, PlanStatus status);
    ```
    Then `currentActivePlanIdFor` does `findFirst... → .map(Plan::getId)`.

### Plan events

12. New sealed marker `PlannerEvent` at `planner/event/PlannerEvent.java` per LLD §Events lines 1086-1095:
    ```java
    public sealed interface PlannerEvent
        extends MealPrepEvent
        permits PlanGeneratedEvent, PlanAcceptedEvent, PlanSupersededEvent,
                PlanCompletedEvent, PlanRejectedEvent, PlanAbandonedEvent,
                ReoptTriggeredEvent, ReoptSuggestedEvent {

      UUID planId();
      UUID traceId();
      Instant occurredAt();
    }
    ```
    `MealPrepEvent` is the project-wide sealed marker from `core.events` per [style-guide §core](../../lld/style-guide.md#module-package-structure). **Verify** it exists; if not, **fall back to `extends nothing`** with a TODO comment — the planner events still ship; the cross-cutting `MealPrepEvent` is a downstream concern.
13. **All 8 event records** per LLD §Events §Published lines 1097-1141:
    - `PlanGeneratedEvent(UUID planId, UUID householdId, LocalDate weekStartDate, int generation, TriggerKind trigger, UUID triggerEventId, UUID decisionId, boolean coldStart, boolean aiAugmented, boolean qualityWarning, UUID traceId, Instant occurredAt) implements PlannerEvent`
    - `PlanAcceptedEvent(UUID planId, UUID householdId, LocalDate weekStartDate, UUID traceId, Instant occurredAt) implements PlannerEvent`
    - `PlanSupersededEvent(UUID planId, UUID replacedByPlanId, UUID householdId, LocalDate weekStartDate, UUID traceId, Instant occurredAt) implements PlannerEvent`
    - `PlanCompletedEvent(UUID planId, UUID householdId, LocalDate weekStartDate, UUID traceId, Instant occurredAt) implements PlannerEvent`
    - `PlanRejectedEvent(UUID planId, UUID householdId, LocalDate weekStartDate, String reason, UUID traceId, Instant occurredAt) implements PlannerEvent`
    - `PlanAbandonedEvent(UUID planId, UUID householdId, LocalDate weekStartDate, String reason, UUID traceId, Instant occurredAt) implements PlannerEvent`
    - `ReoptTriggeredEvent(UUID planId, UUID householdId, LocalDate weekStartDate, ReoptTriggerKind trigger, UUID triggerEventId, UUID traceId, Instant occurredAt) implements PlannerEvent`
    - `ReoptSuggestedEvent(UUID planId, UUID householdId, LocalDate weekStartDate, UUID suggestionId, ReoptTriggerKind trigger, UUID triggerEventId, List<UUID> affectedSlotIds, String summary, UUID traceId, Instant occurredAt) implements PlannerEvent`

    All records are `public`. All `permits` entries on `PlannerEvent` match these record names exactly.
14. **No event publication in 01b**. The records exist; the actual `publishEvent(...)` calls live in the writes flows (01i, 01j) and the listener flow (01k).

### Exceptions

15. New exceptions under `planner/exception/`:
    - `InvalidPlanStateTransitionException extends PlannerException` — `409`, `type = .../invalid-plan-state-transition`. Constructor: `(PlanStatus current, PlanStatus next)` → message `"plan transition not allowed: " + current + " -> " + next`.
    - `InvalidSlotStateTransitionException extends PlannerException` — `409`, `type = .../invalid-slot-state-transition`. Constructor: `(SlotState current, SlotState next)`.
    - `ConcurrentGenerationInProgressException extends PlannerException` — `409`, `type = .../concurrent-generation`. Constructor: `(UUID householdId, LocalDate weekStartDate)` → message `"plan generation already in progress for household " + householdId + " week " + weekStartDate`. **Not thrown in 01b** (lock is acquired in 01j), but defined here because the type is part of the state-machine surface.
    - `RevertTargetNotInHistoryException extends PlannerException` — `422`, `type = .../revert-target-invalid`. Constructor: `(UUID targetPlanId)` → message `"target plan " + targetPlanId + " is not in the caller's household history"`. **Not thrown in 01b** (revert flow lives in 01j), but defined here.
16. **Append four new `@ExceptionHandler` methods** to the existing `PlannerExceptionHandler` (from 01a). Keep `@Order(Ordered.HIGHEST_PRECEDENCE)`. Map each new exception to its ProblemDetail.

## Database

**Zero migrations.** 01b is pure logic. The `PlanRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus` query uses the existing `idx_planner_plans_household_week_status` index from 01a.

## OpenAPI updates

**No new endpoints.** 01b adds no controller surface. The four new exception types eventually surface via 01j/01i flows; their ProblemDetail `type` URIs are documented in `openapi.yaml`'s `components.responses` section under the relevant endpoint's response codes — those edits land in 01j when the endpoints land. **01b does not modify `openapi.yaml`.**

## Verbatim shape snippets

### `PlanStateMachine`

```java
package com.example.mealprep.planner.domain.service.internal.lifecycle;

@Component
public class PlanStateMachine {

  private static final Map<PlanStatus, Set<PlanStatus>> PLAN_TRANSITIONS = Map.of(
      PlanStatus.DRAFT,      Set.of(PlanStatus.GENERATED),
      PlanStatus.GENERATED,  Set.of(PlanStatus.ACTIVE, PlanStatus.REJECTED),
      PlanStatus.ACTIVE,     Set.of(PlanStatus.SUPERSEDED, PlanStatus.ABANDONED, PlanStatus.COMPLETED),
      PlanStatus.SUPERSEDED, Set.of(),
      PlanStatus.COMPLETED,  Set.of(),
      PlanStatus.REJECTED,   Set.of(),
      PlanStatus.ABANDONED,  Set.of()
  );

  private static final Map<SlotState, Set<SlotState>> SLOT_TRANSITIONS = Map.of(
      SlotState.PLANNED, Set.of(SlotState.COOKING, SlotState.SKIPPED),
      SlotState.COOKING, Set.of(SlotState.COOKED, SlotState.SKIPPED),
      SlotState.COOKED,  Set.of(SlotState.EATEN),
      SlotState.EATEN,   Set.of(),
      SlotState.SKIPPED, Set.of()
  );

  public boolean isPlanTransitionAllowed(PlanStatus current, PlanStatus next) {
    return PLAN_TRANSITIONS.getOrDefault(current, Set.of()).contains(next);
  }

  public void assertPlanTransitionAllowed(PlanStatus current, PlanStatus next) {
    if (!isPlanTransitionAllowed(current, next)) {
      throw new InvalidPlanStateTransitionException(current, next);
    }
  }

  public Set<SlotState> allowedSlotNextStates(SlotState current) {
    return SLOT_TRANSITIONS.getOrDefault(current, Set.of());
  }

  public boolean isSlotTransitionAllowed(SlotState current, SlotState next) {
    return allowedSlotNextStates(current).contains(next);
  }

  public void assertSlotTransitionAllowed(SlotState current, SlotState next) {
    if (!isSlotTransitionAllowed(current, next)) {
      throw new InvalidSlotStateTransitionException(current, next);
    }
  }

  public Optional<PinnedReason> derivePinnedReason(SlotState state) {
    return switch (state) {
      case EATEN   -> Optional.of(PinnedReason.EATEN);
      case COOKED  -> Optional.of(PinnedReason.COOKED);
      case COOKING -> Optional.of(PinnedReason.COOKING);
      case SKIPPED -> Optional.of(PinnedReason.SKIPPED);
      case PLANNED -> Optional.empty();
    };
  }
}
```

### `PlanGenerationCounter`

```java
package com.example.mealprep.planner.domain.service.internal.lifecycle;

@Component
@RequiredArgsConstructor
class PlanGenerationCounter {
  private final PlanRepository planRepository;

  @Transactional(readOnly = true)
  public int nextGenerationFor(UUID householdId, LocalDate weekStartDate) {
    return 1 + planRepository.countByHouseholdIdAndWeekStartDate(householdId, weekStartDate);
  }

  @Transactional(readOnly = true)
  public Optional<UUID> currentActivePlanIdFor(UUID householdId, LocalDate weekStartDate) {
    return planRepository
        .findFirstByHouseholdIdAndWeekStartDateAndStatus(householdId, weekStartDate, PlanStatus.ACTIVE)
        .map(Plan::getId);
  }
}
```

## Edge-case checklist

### Plan transitions

- [ ] `DRAFT → GENERATED` allowed
- [ ] `GENERATED → ACTIVE` allowed
- [ ] `GENERATED → REJECTED` allowed
- [ ] `ACTIVE → SUPERSEDED` allowed
- [ ] `ACTIVE → ABANDONED` allowed
- [ ] `ACTIVE → COMPLETED` allowed
- [ ] `GENERATED → SUPERSEDED` rejected (must go ACTIVE first or be rejected)
- [ ] `ACTIVE → GENERATED` rejected (no rollback to GENERATED via state machine; revert uses a fresh plan)
- [ ] Every terminal status (`SUPERSEDED`, `COMPLETED`, `REJECTED`, `ABANDONED`) → any next: throws
- [ ] `REJECTED → REJECTED` throws (idempotency lives in the controller, not the state machine)
- [ ] `assertPlanTransitionAllowed` throws `InvalidPlanStateTransitionException` with both `current` and `next` in the message

### Slot transitions

- [ ] `PLANNED → COOKING` allowed
- [ ] `PLANNED → SKIPPED` allowed
- [ ] `COOKING → COOKED` allowed
- [ ] `COOKING → SKIPPED` allowed
- [ ] `COOKED → EATEN` allowed
- [ ] `PLANNED → COOKED` rejected (must pass through COOKING)
- [ ] `EATEN → COOKING` rejected (terminal)
- [ ] `SKIPPED → EATEN` rejected (terminal)
- [ ] `EATEN → EATEN` rejected
- [ ] `allowedSlotNextStates(EATEN)` returns empty set
- [ ] `allowedSlotNextStates(SKIPPED)` returns empty set
- [ ] `assertSlotTransitionAllowed` throws `InvalidSlotStateTransitionException` with both states

### Pinning derivation

- [ ] `derivePinnedReason(EATEN)` → `Optional.of(EATEN)`
- [ ] `derivePinnedReason(COOKED)` → `Optional.of(COOKED)`
- [ ] `derivePinnedReason(COOKING)` → `Optional.of(COOKING)`
- [ ] `derivePinnedReason(SKIPPED)` → `Optional.of(SKIPPED)`
- [ ] `derivePinnedReason(PLANNED)` → `Optional.empty()`

### `PlanGenerationCounter`

- [ ] First plan for `(household, week)` → `nextGenerationFor` returns 1
- [ ] After persisting 2 plans → `nextGenerationFor` returns 3
- [ ] `currentActivePlanIdFor` returns the only `ACTIVE` plan id when one exists
- [ ] `currentActivePlanIdFor` returns `Optional.empty()` when only `GENERATED` plans exist
- [ ] `currentActivePlanIdFor` returns `Optional.empty()` when only terminal plans exist

### Events

- [ ] `PlannerEvent` sealed; permits all 8 records
- [ ] Each record implements `PlannerEvent` and exposes `planId()`, `traceId()`, `occurredAt()` (via record components matching the interface methods)
- [ ] `MealPrepEvent` parent is honoured (or replaced with `extends nothing` with TODO if `core.events.MealPrepEvent` isn't yet on classpath)

### Exception handler

- [ ] `PlannerExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)` after appending 4 new `@ExceptionHandler` methods
- [ ] `InvalidPlanStateTransitionException` → 409 `invalid-plan-state-transition` ProblemDetail
- [ ] `InvalidSlotStateTransitionException` → 409 `invalid-slot-state-transition`
- [ ] `ConcurrentGenerationInProgressException` → 409 `concurrent-generation`
- [ ] `RevertTargetNotInHistoryException` → 422 `revert-target-invalid`

### Cross-cutting

- [ ] No N+1 — `PlanGenerationCounter.nextGenerationFor` issues exactly one COUNT SQL; `currentActivePlanIdFor` issues exactly one SELECT
- [ ] `PlanRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus` uses `idx_planner_plans_household_week_status` (verify via `EXPLAIN` or test annotation; this is a perf check, not a correctness one)
- [ ] No `pom.xml` dependency adds
- [ ] No other modules' files touched
- [ ] `PlannerBoundaryTest` (from 01a) still passes — no new sub-packages outside `domain/service/internal/lifecycle/` and `event/` and `exception/`

## Files this ticket touches

```
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/lifecycle/PlanStateMachine.java
NEW   src/main/java/com/example/mealprep/planner/domain/service/internal/lifecycle/PlanGenerationCounter.java

NEW   src/main/java/com/example/mealprep/planner/event/PlannerEvent.java
NEW   src/main/java/com/example/mealprep/planner/event/PlanGeneratedEvent.java
NEW   src/main/java/com/example/mealprep/planner/event/PlanAcceptedEvent.java
NEW   src/main/java/com/example/mealprep/planner/event/PlanSupersededEvent.java
NEW   src/main/java/com/example/mealprep/planner/event/PlanCompletedEvent.java
NEW   src/main/java/com/example/mealprep/planner/event/PlanRejectedEvent.java
NEW   src/main/java/com/example/mealprep/planner/event/PlanAbandonedEvent.java
NEW   src/main/java/com/example/mealprep/planner/event/ReoptTriggeredEvent.java
NEW   src/main/java/com/example/mealprep/planner/event/ReoptSuggestedEvent.java

NEW   src/main/java/com/example/mealprep/planner/exception/InvalidPlanStateTransitionException.java
NEW   src/main/java/com/example/mealprep/planner/exception/InvalidSlotStateTransitionException.java
NEW   src/main/java/com/example/mealprep/planner/exception/ConcurrentGenerationInProgressException.java
NEW   src/main/java/com/example/mealprep/planner/exception/RevertTargetNotInHistoryException.java

MOD   src/main/java/com/example/mealprep/planner/api/PlannerExceptionHandler.java                          (append 4 @ExceptionHandler methods; KEEP @Order(Ordered.HIGHEST_PRECEDENCE))
MOD   src/main/java/com/example/mealprep/planner/domain/repository/PlanRepository.java                     (add findFirstByHouseholdIdAndWeekStartDateAndStatus method)

NEW   src/test/java/com/example/mealprep/planner/PlanStateMachineTest.java
NEW   src/test/java/com/example/mealprep/planner/PlanGenerationCounterTest.java
NEW   src/test/java/com/example/mealprep/planner/PlanGenerationCounterIT.java
MOD   src/test/java/com/example/mealprep/planner/testdata/PlanTestData.java                                (append: testGeneratedPlan, testActivePlan builders)
```

Count: ~20 files. Pure logic + records + exceptions. Estimated agent runtime 25-35 min.

**Files this ticket does NOT modify**:
- `src/main/java/com/example/mealprep/config/GlobalExceptionHandler.java` — exceptions in `PlannerExceptionHandler`.
- `src/test/java/com/example/mealprep/archunit/ModuleBoundaryTest.java` — module rule in `PlannerBoundaryTest`.
- Migrations / entities / controllers / DTOs — all from 01a, untouched.
- `openapi.yaml`, `paths/planner.yaml`, `schemas/planner.yaml` — no new endpoints in 01b.
- Other modules — none touched.

## Gotchas to bake in

1. **`@Order(Ordered.HIGHEST_PRECEDENCE)` on the existing advice MUST be preserved** after appending the 4 new handlers. The annotation lives at the class level; do NOT accidentally remove it during the edit.
2. **Sealed interface permits must match record names exactly**. If you rename `PlanGeneratedEvent`, also update the `permits` clause on `PlannerEvent`. A misalignment is a compile error.
3. **`Set.of()` returns immutable empty set**. Don't use `null` for terminal states' next-set; downstream code does `.contains(...)` and NPEs on null.
4. **`Map.of()` rejects duplicate keys**. `PLAN_TRANSITIONS` and `SLOT_TRANSITIONS` both use `Map.of()` — safe because each key appears once. If a transition matrix grows beyond ~10 entries, use `Map.ofEntries(...)` or a static initialiser.
5. **`MealPrepEvent` may not exist yet** — verify at `com.example.mealprep.core.events.MealPrepEvent`. If absent, `PlannerEvent` `extends nothing` with a TODO comment. Don't block the ticket on a `core`-module concern; the planner family still sealed-marks correctly with `PlannerEvent` as its own root.
6. **Switch expressions on enums**: the `derivePinnedReason` switch covers every `SlotState` value — adding a new state later makes the switch non-exhaustive (compile error). That's a feature; don't add a `default ->` clause.
7. **Idempotency lives in the controller, not the state machine**. `rejectPlan` calling `assertPlanTransitionAllowed(REJECTED, REJECTED)` throws — that's correct behaviour for the state machine. The controller (01j) catches the post-condition `if (plan.status == REJECTED) return mapper.toDto(plan);` BEFORE invoking the state machine.
8. **`PlanGenerationCounter` is `@Transactional(readOnly = true)`** — pure read. No write semantics.
9. **`@RequiredArgsConstructor` from Lombok**: don't use `@Autowired` field injection; constructor injection only.

## Dependencies

- **Hard dependency**: `planner-01a` (merged) — `Plan`, `PlanStatus`, `SlotState`, `PinnedReason`, `TriggerKind`, `ReoptTriggerKind`, `PlanRepository`, `PlannerExceptionHandler`, `PlannerException`.
- **Hard dependency**: `core` wave 1 module (merged) — `MealPrepEvent` sealed marker (if present; else fallback).
- **Sibling tickets running in parallel** (Wave 3 round 2 — assumed sequencing): potentially `planner-01c` (read API), `planner-01d` (beam search). Neither touches `lifecycle/`, `event/`, or `exception/` directly. Only collision point is `PlannerExceptionHandler.java` — coordinate by appending in the correct order if both 01b and 01c add handler methods. 01b appends 4; sibling tickets should append below 01b's block.

## Acceptance / DoD

- [ ] `./mvnw -Dspotless.check.skip=true verify` passes locally on agent's worktree
- [ ] `./mvnw spotless:apply` then `./mvnw spotless:check` clean
- [ ] CI green
- [ ] All edge-case items above ticked
- [ ] `PlannerExceptionHandler` retains `@Order(Ordered.HIGHEST_PRECEDENCE)`
- [ ] `PlannerEvent` is a sealed interface; the 8 records use `permits`
- [ ] `PlanStateMachine` is a Spring `@Component` (allows DI in future ticket consumers) but exposes pure-function semantics
- [ ] No `pom.xml` dependency adds
- [ ] No other modules' files touched
- [ ] No regression on 01a tests

Squash-merge with: `feat(planner): 01b — PlanStateMachine + PlanGenerationCounter + 8 plan events + 4 transition exceptions`

## What's NOT in scope

- Calling `PlanStateMachine` from controllers / services — pure logic only; consumers wire it later
- Publishing events at the right transactional moments — that's the consumer's job (01i, 01j, 01k)
- `@ValidSlotState` Jakarta validator — request-level pre-check that maps to 400 instead of 409; lands in 01j
- `PlanStateMachine.markCompletedIfTerminal(...)` weekly-sweep helper — lands in 01k alongside the `@Scheduled` sweep
- Cross-aggregate guards (e.g. "is the plan still active?" before allowing a slot transition) — service-layer concern, lives in 01j's `markSlotState`
- Any controller endpoint surface — 01b ships zero endpoints
- `MealCookedEvent` consumption (the cook listener that drives `markSlotState`) — lives in provisions / planner internal listener; **out of scope** for the state-machine ticket
