package com.example.mealprep.planner.domain.service.internal.lifecycle;

import com.example.mealprep.planner.domain.entity.PinnedReason;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.exception.InvalidPlanStateTransitionException;
import com.example.mealprep.planner.exception.InvalidSlotStateTransitionException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Pure-logic state machine for {@link PlanStatus} transitions, {@link SlotState} transitions, and
 * the slot-state → {@link PinnedReason} mapping per LLD §Entities, §Flow 3, §Flow 5, and §Pinning
 * Rules.
 *
 * <p>Exposed as a Spring {@code @Component} purely for DI uniformity — every method is pure and
 * stateless. Idempotency for terminal states (e.g. re-rejecting a {@code REJECTED} plan) is a
 * service-layer concern (handled in 01j); the state machine itself raises {@link
 * InvalidPlanStateTransitionException} on any transition not present in {@link #PLAN_TRANSITIONS}.
 */
@Component
public class PlanStateMachine {

  private static final Map<PlanStatus, Set<PlanStatus>> PLAN_TRANSITIONS =
      Map.of(
          PlanStatus.DRAFT, Set.of(PlanStatus.GENERATED),
          PlanStatus.GENERATED, Set.of(PlanStatus.ACTIVE, PlanStatus.REJECTED),
          PlanStatus.ACTIVE,
              Set.of(PlanStatus.SUPERSEDED, PlanStatus.ABANDONED, PlanStatus.COMPLETED),
          PlanStatus.SUPERSEDED, Set.of(),
          PlanStatus.COMPLETED, Set.of(),
          PlanStatus.REJECTED, Set.of(),
          PlanStatus.ABANDONED, Set.of());

  private static final Map<SlotState, Set<SlotState>> SLOT_TRANSITIONS =
      Map.of(
          SlotState.PLANNED, Set.of(SlotState.COOKING, SlotState.SKIPPED),
          SlotState.COOKING, Set.of(SlotState.COOKED, SlotState.SKIPPED),
          SlotState.COOKED, Set.of(SlotState.EATEN),
          SlotState.EATEN, Set.of(),
          SlotState.SKIPPED, Set.of());

  /** Boolean form. Used by tests and by future request-validation pre-checks. */
  public boolean isPlanTransitionAllowed(PlanStatus current, PlanStatus next) {
    return PLAN_TRANSITIONS.getOrDefault(current, Set.of()).contains(next);
  }

  /**
   * Asserting form. Throws {@link InvalidPlanStateTransitionException} when the transition is not
   * allowed; returns silently otherwise.
   */
  public void assertPlanTransitionAllowed(PlanStatus current, PlanStatus next) {
    if (!isPlanTransitionAllowed(current, next)) {
      throw new InvalidPlanStateTransitionException(current, next);
    }
  }

  /**
   * Returns the set of legal next states for the given slot state. Empty set for terminal states
   * ({@code EATEN}, {@code SKIPPED}). Never returns {@code null} — callers can {@code
   * .contains(...)} safely.
   */
  public Set<SlotState> allowedSlotNextStates(SlotState current) {
    return SLOT_TRANSITIONS.getOrDefault(current, Set.of());
  }

  public boolean isSlotTransitionAllowed(SlotState current, SlotState next) {
    return allowedSlotNextStates(current).contains(next);
  }

  /**
   * Asserting form. Throws {@link InvalidSlotStateTransitionException} when the transition is not
   * allowed; returns silently otherwise.
   */
  public void assertSlotTransitionAllowed(SlotState current, SlotState next) {
    if (!isSlotTransitionAllowed(current, next)) {
      throw new InvalidSlotStateTransitionException(current, next);
    }
  }

  /**
   * Map a {@link SlotState} to the {@link PinnedReason} the re-opt scope builder should use per LLD
   * §Pinning Rules. {@code PLANNED} → empty (regenerable). {@code USER_PINNED} is intentionally not
   * derived here — it's set externally by a future user-pinning flow.
   */
  public Optional<PinnedReason> derivePinnedReason(SlotState state) {
    return switch (state) {
      case EATEN -> Optional.of(PinnedReason.EATEN);
      case COOKED -> Optional.of(PinnedReason.COOKED);
      case COOKING -> Optional.of(PinnedReason.COOKING);
      case SKIPPED -> Optional.of(PinnedReason.SKIPPED);
      case PLANNED -> Optional.empty();
    };
  }
}
