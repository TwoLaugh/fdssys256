package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit test for {@link VarietyGate} — max-repeat hard gate. */
class VarietyGateTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final VarietyGate gate = new VarietyGate(PlanTestData.scoringProperties());

  @Test
  void empty_plan_passes_vacuously() {
    assertThat(
            gate.passes(
                PlanTestData.candidatePlan(WEEK, List.of()),
                PlanTestData.minimalContext(List.of(), List.of())))
        .isTrue();
  }

  @Test
  void recipe_at_max_repeat_two_passes() {
    UUID r = UUID.randomUUID();
    List<SlotAssignment> as =
        List.of(
            PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 1, 2));
    assertThat(
            gate.passes(
                PlanTestData.candidatePlan(WEEK, as),
                PlanTestData.minimalContext(List.of(), List.of())))
        .isTrue();
  }

  @Test
  void recipe_exceeding_max_repeat_fails() {
    UUID r = UUID.randomUUID();
    List<SlotAssignment> as =
        List.of(
            PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 1, 2),
            PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 2, 2));
    assertThat(
            gate.passes(
                PlanTestData.candidatePlan(WEEK, as),
                PlanTestData.minimalContext(List.of(), List.of())))
        .isFalse();
  }
}
