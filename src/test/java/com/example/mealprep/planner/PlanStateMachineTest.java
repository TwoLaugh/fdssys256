package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.planner.domain.entity.PinnedReason;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.domain.service.internal.lifecycle.PlanStateMachine;
import com.example.mealprep.planner.exception.InvalidPlanStateTransitionException;
import com.example.mealprep.planner.exception.InvalidSlotStateTransitionException;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pure-logic unit test for {@link PlanStateMachine}. Covers every allowed plan transition, every
 * allowed slot transition, every terminal-state rejection, and the {@code derivePinnedReason}
 * mapping per LLD §Entities, §Flow 3, §Flow 5, and §Pinning Rules.
 */
class PlanStateMachineTest {

  private final PlanStateMachine sm = new PlanStateMachine();

  @Nested
  class PlanTransitions {

    @Test
    void draftToGenerated_allowed() {
      assertThat(sm.isPlanTransitionAllowed(PlanStatus.DRAFT, PlanStatus.GENERATED)).isTrue();
      assertThatCode(() -> sm.assertPlanTransitionAllowed(PlanStatus.DRAFT, PlanStatus.GENERATED))
          .doesNotThrowAnyException();
    }

    @Test
    void generatedToActive_allowed() {
      assertThat(sm.isPlanTransitionAllowed(PlanStatus.GENERATED, PlanStatus.ACTIVE)).isTrue();
    }

    @Test
    void generatedToRejected_allowed() {
      assertThat(sm.isPlanTransitionAllowed(PlanStatus.GENERATED, PlanStatus.REJECTED)).isTrue();
    }

    @Test
    void activeToSuperseded_allowed() {
      assertThat(sm.isPlanTransitionAllowed(PlanStatus.ACTIVE, PlanStatus.SUPERSEDED)).isTrue();
    }

    @Test
    void activeToAbandoned_allowed() {
      assertThat(sm.isPlanTransitionAllowed(PlanStatus.ACTIVE, PlanStatus.ABANDONED)).isTrue();
    }

    @Test
    void activeToCompleted_allowed() {
      assertThat(sm.isPlanTransitionAllowed(PlanStatus.ACTIVE, PlanStatus.COMPLETED)).isTrue();
    }

    @Test
    void generatedToSuperseded_rejected() {
      assertThat(sm.isPlanTransitionAllowed(PlanStatus.GENERATED, PlanStatus.SUPERSEDED)).isFalse();
      assertThatThrownBy(
              () -> sm.assertPlanTransitionAllowed(PlanStatus.GENERATED, PlanStatus.SUPERSEDED))
          .isInstanceOf(InvalidPlanStateTransitionException.class)
          .hasMessageContaining("GENERATED")
          .hasMessageContaining("SUPERSEDED");
    }

    @Test
    void activeToGenerated_rejected_noRollback() {
      assertThat(sm.isPlanTransitionAllowed(PlanStatus.ACTIVE, PlanStatus.GENERATED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
        value = PlanStatus.class,
        names = {"SUPERSEDED", "COMPLETED", "REJECTED", "ABANDONED"})
    void terminalStates_haveNoAllowedNext(PlanStatus terminal) {
      for (PlanStatus next : PlanStatus.values()) {
        assertThat(sm.isPlanTransitionAllowed(terminal, next))
            .as("transition from terminal %s to %s should be rejected", terminal, next)
            .isFalse();
      }
    }

    @Test
    void rejectedToRejected_throws_idempotencyIsControllerConcern() {
      // The state machine is pure; controller (01j) catches idempotency before delegating.
      assertThatThrownBy(
              () -> sm.assertPlanTransitionAllowed(PlanStatus.REJECTED, PlanStatus.REJECTED))
          .isInstanceOf(InvalidPlanStateTransitionException.class);
    }

    @Test
    void assertPlanTransitionAllowed_throwsWithBothStatesInMessage() {
      assertThatThrownBy(() -> sm.assertPlanTransitionAllowed(PlanStatus.DRAFT, PlanStatus.ACTIVE))
          .isInstanceOf(InvalidPlanStateTransitionException.class)
          .hasMessage("plan transition not allowed: DRAFT -> ACTIVE");
    }

    @Test
    void exceptionExposesCurrentAndNext() {
      InvalidPlanStateTransitionException ex =
          new InvalidPlanStateTransitionException(PlanStatus.ACTIVE, PlanStatus.DRAFT);
      assertThat(ex.current()).isEqualTo(PlanStatus.ACTIVE);
      assertThat(ex.next()).isEqualTo(PlanStatus.DRAFT);
    }
  }

  @Nested
  class SlotTransitions {

    @Test
    void plannedToCooking_allowed() {
      assertThat(sm.isSlotTransitionAllowed(SlotState.PLANNED, SlotState.COOKING)).isTrue();
    }

    @Test
    void plannedToSkipped_allowed() {
      assertThat(sm.isSlotTransitionAllowed(SlotState.PLANNED, SlotState.SKIPPED)).isTrue();
    }

    @Test
    void cookingToCooked_allowed() {
      assertThat(sm.isSlotTransitionAllowed(SlotState.COOKING, SlotState.COOKED)).isTrue();
    }

    @Test
    void cookingToSkipped_allowed() {
      assertThat(sm.isSlotTransitionAllowed(SlotState.COOKING, SlotState.SKIPPED)).isTrue();
    }

    @Test
    void cookedToEaten_allowed() {
      assertThat(sm.isSlotTransitionAllowed(SlotState.COOKED, SlotState.EATEN)).isTrue();
    }

    @Test
    void plannedToCooked_rejected_mustPassThroughCooking() {
      assertThat(sm.isSlotTransitionAllowed(SlotState.PLANNED, SlotState.COOKED)).isFalse();
    }

    @Test
    void eatenToCooking_rejected_terminal() {
      assertThat(sm.isSlotTransitionAllowed(SlotState.EATEN, SlotState.COOKING)).isFalse();
    }

    @Test
    void skippedToEaten_rejected_terminal() {
      assertThat(sm.isSlotTransitionAllowed(SlotState.SKIPPED, SlotState.EATEN)).isFalse();
    }

    @Test
    void eatenToEaten_rejected() {
      assertThat(sm.isSlotTransitionAllowed(SlotState.EATEN, SlotState.EATEN)).isFalse();
      assertThatThrownBy(() -> sm.assertSlotTransitionAllowed(SlotState.EATEN, SlotState.EATEN))
          .isInstanceOf(InvalidSlotStateTransitionException.class);
    }

    @Test
    void allowedSlotNextStates_eaten_isEmpty() {
      assertThat(sm.allowedSlotNextStates(SlotState.EATEN)).isEmpty();
    }

    @Test
    void allowedSlotNextStates_skipped_isEmpty() {
      assertThat(sm.allowedSlotNextStates(SlotState.SKIPPED)).isEmpty();
    }

    @Test
    void allowedSlotNextStates_planned_containsCookingAndSkipped() {
      assertThat(sm.allowedSlotNextStates(SlotState.PLANNED))
          .containsExactlyInAnyOrder(SlotState.COOKING, SlotState.SKIPPED);
    }

    @Test
    void assertSlotTransitionAllowed_throwsWithBothStatesInMessage() {
      assertThatThrownBy(() -> sm.assertSlotTransitionAllowed(SlotState.PLANNED, SlotState.EATEN))
          .isInstanceOf(InvalidSlotStateTransitionException.class)
          .hasMessageContaining("PLANNED")
          .hasMessageContaining("EATEN");
    }

    @ParameterizedTest
    @EnumSource(
        value = SlotState.class,
        names = {"EATEN", "SKIPPED"})
    void terminalSlotStates_allTransitionsRejected(SlotState terminal) {
      for (SlotState next : SlotState.values()) {
        assertThat(sm.isSlotTransitionAllowed(terminal, next))
            .as("terminal %s -> %s must be rejected", terminal, next)
            .isFalse();
      }
    }
  }

  @Nested
  class PinningDerivation {

    @Test
    void eaten_mapsToEaten() {
      assertThat(sm.derivePinnedReason(SlotState.EATEN)).contains(PinnedReason.EATEN);
    }

    @Test
    void cooked_mapsToCooked() {
      assertThat(sm.derivePinnedReason(SlotState.COOKED)).contains(PinnedReason.COOKED);
    }

    @Test
    void cooking_mapsToCooking() {
      assertThat(sm.derivePinnedReason(SlotState.COOKING)).contains(PinnedReason.COOKING);
    }

    @Test
    void skipped_mapsToSkipped() {
      assertThat(sm.derivePinnedReason(SlotState.SKIPPED)).contains(PinnedReason.SKIPPED);
    }

    @Test
    void planned_mapsToEmpty_regenerable() {
      assertThat(sm.derivePinnedReason(SlotState.PLANNED)).isEqualTo(Optional.empty());
    }

    @Test
    void everySlotStateExceptPlanned_hasNonEmptyReason() {
      Arrays.stream(SlotState.values())
          .filter(s -> s != SlotState.PLANNED)
          .forEach(
              s ->
                  assertThat(sm.derivePinnedReason(s))
                      .as("derivePinnedReason(%s) must be present", s)
                      .isPresent());
    }
  }
}
