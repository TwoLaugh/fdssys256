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

  // ---- per-household repeat cap derived from the merged lifestyle config ----------------------

  @Test
  void null_merged_config_uses_configured_default_of_two() {
    assertThat(gate.maxRepeat(PlanTestData.minimalContext(List.of(), List.of()))).isEqualTo(2);
  }

  @Test
  void non_batch_household_keeps_the_configured_default() {
    // A merged config that is NOT batch-cooking keeps the variety-tuned default of 2.
    assertThat(gate.maxRepeat(PlanTestData.contextWithLifestyle(null, false))).isEqualTo(2);
  }

  @Test
  void batch_cooking_household_lifts_the_cap_to_a_prep_block() {
    // batchCookingPreferred → cook-once-eat-many: the cap rises to the moderate prep-block size (3).
    assertThat(gate.maxRepeat(PlanTestData.contextWithLifestyle(null, true))).isEqualTo(3);
  }

  @Test
  void meal_prepper_can_repeat_a_dish_the_default_gate_would_reject() {
    // A 3× recipe fails under the default cap of 2 but passes for a batch-cooking household.
    UUID r = UUID.randomUUID();
    List<SlotAssignment> as =
        List.of(
            PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 1, 2),
            PlanTestData.assignment(UUID.randomUUID(), r, WEEK, 2, 2));
    assertThat(
            gate.passes(
                PlanTestData.candidatePlan(WEEK, as), PlanTestData.contextWithLifestyle(null, true)))
        .isTrue();
    // ...and the same plan still fails for a non-batch household (default cap of 2).
    assertThat(
            gate.passes(
                PlanTestData.candidatePlan(WEEK, as),
                PlanTestData.contextWithLifestyle(null, false)))
        .isFalse();
  }
}
