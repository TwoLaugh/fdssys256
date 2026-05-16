package com.example.mealprep.planner.domain.service.internal.beamsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic test for {@link BeamPruner}. Lives in the same package so the package-private record
 * {@link PartialPlan} and class {@link BeamPruner} are directly accessible.
 */
class BeamPrunerTest {

  private static final LocalDate WEEK_START = LocalDate.of(2026, 1, 5);

  private final BeamPruner pruner = new BeamPruner();

  @Test
  void empty_input_returns_empty() {
    assertThat(pruner.retainTop(List.of(), 5)).isEmpty();
  }

  @Test
  void fewer_than_width_returns_all() {
    List<PartialPlan> input =
        List.of(
            plan(BigDecimal.valueOf(0.1), uuidOf(1)),
            plan(BigDecimal.valueOf(0.3), uuidOf(2)),
            plan(BigDecimal.valueOf(0.2), uuidOf(3)));
    List<PartialPlan> kept = pruner.retainTop(input, 5);
    assertThat(kept).hasSize(3);
    assertThat(kept.get(0).currentScore()).isEqualByComparingTo("0.3");
    assertThat(kept.get(1).currentScore()).isEqualByComparingTo("0.2");
    assertThat(kept.get(2).currentScore()).isEqualByComparingTo("0.1");
  }

  @Test
  void top_n_picked_by_score_desc() {
    List<PartialPlan> input = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      // Use BigDecimal.valueOf(int).movePointLeft(1) for exact 0.0 / 0.1 / ... / 0.9.
      input.add(plan(BigDecimal.valueOf(i).movePointLeft(1), uuidOf(i)));
    }
    List<PartialPlan> kept = pruner.retainTop(input, 3);
    assertThat(kept).hasSize(3);
    assertThat(kept.get(0).currentScore()).isEqualByComparingTo("0.9");
    assertThat(kept.get(1).currentScore()).isEqualByComparingTo("0.8");
    assertThat(kept.get(2).currentScore()).isEqualByComparingTo("0.7");
  }

  @Test
  void ties_broken_by_last_recipe_id_ascending_deterministic() {
    UUID lower = uuidOf(1);
    UUID higher = uuidOf(2);
    PartialPlan a = plan(BigDecimal.valueOf(0.5), higher);
    PartialPlan b = plan(BigDecimal.valueOf(0.5), lower);
    List<PartialPlan> kept = pruner.retainTop(List.of(a, b), 1);
    assertThat(kept).hasSize(1);
    SlotAssignment last = kept.get(0).assignments().get(0);
    assertThat(last.recipeId()).isEqualTo(lower);

    // Reversed input order — same deterministic outcome.
    List<PartialPlan> reversed = pruner.retainTop(List.of(b, a), 1);
    assertThat(reversed.get(0).assignments().get(0).recipeId()).isEqualTo(lower);
  }

  private static PartialPlan plan(BigDecimal score, UUID lastRecipeId) {
    SlotAssignment a =
        new SlotAssignment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            WEEK_START,
            SlotKind.BREAKFAST,
            lastRecipeId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    return new PartialPlan(WEEK_START, List.of(a), score);
  }

  private static UUID uuidOf(int seed) {
    return new UUID(0L, seed);
  }
}
