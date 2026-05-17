package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link NutritionSubScore}. The LOCKED direction-score formula is exercised directly
 * (deterministic, no recipe nutrition needed); the end-to-end path asserts the no-targets vacuous
 * convention and the missing-nutrition → 0-actual behaviour (ticket items 14-18).
 */
class NutritionSubScoreTest {

  private final NutritionSubScore calc = new NutritionSubScore();

  private static MacroTargetDto macro(String targetG, EnforcementDirection dir) {
    return new MacroTargetDto(new BigDecimal(targetG), null, "daily", dir);
  }

  @Test
  void name_is_nutrition() {
    assertThat(calc.name()).isEqualTo("nutrition");
  }

  @Test
  void direction_score_upper_limit_fifty_percent_over_is_half() {
    assertThat(
            NutritionSubScore.directionScore(
                new BigDecimal("150"), new BigDecimal("100"), EnforcementDirection.UPPER_LIMIT))
        .isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  void direction_score_lower_floor_fifty_percent_under_is_half() {
    assertThat(
            NutritionSubScore.directionScore(
                new BigDecimal("50"), new BigDecimal("100"), EnforcementDirection.LOWER_FLOOR))
        .isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  void direction_score_both_bounded_ten_percent_off_is_point_nine() {
    assertThat(
            NutritionSubScore.directionScore(
                new BigDecimal("110"), new BigDecimal("100"), EnforcementDirection.BOTH_BOUNDED))
        .isEqualByComparingTo(new BigDecimal("0.9"));
  }

  @Test
  void direction_score_null_target_is_one() {
    assertThat(
            NutritionSubScore.directionScore(
                new BigDecimal("100"), null, EnforcementDirection.UPPER_LIMIT))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void direction_score_lower_floor_met_or_exceeded_is_one() {
    assertThat(
            NutritionSubScore.directionScore(
                new BigDecimal("120"), new BigDecimal("100"), EnforcementDirection.LOWER_FLOOR))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void no_targets_row_is_vacuous_one() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    assertThat(
            calc.compute(
                PlanTestData.candidatePlan(java.time.LocalDate.of(2026, 1, 5), List.of()), ctx))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void upper_limit_with_zero_actual_nutrition_scores_one() {
    // recipe nutrition not exposed → actual = 0; UPPER_LIMIT with 0 actual → penalty 0 → 1.0
    UUID user = UUID.randomUUID();
    TargetsDto targets =
        new TargetsDto(
            UUID.randomUUID(),
            user,
            Goal.MAINTAIN,
            null,
            macro("120", EnforcementDirection.UPPER_LIMIT),
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            null,
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            0L);
    var skel =
        PlanTestData.skeletonFor(
            java.time.LocalDate.of(2026, 1, 5),
            0,
            com.example.mealprep.core.types.SlotKind.DINNER,
            30);
    var ctx =
        PlanTestData.scoringContext(
            List.of(skel),
            List.of(),
            null,
            Map.of(
                user,
                new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(
                    user, null, null)),
            Map.of(user, targets));
    UUID recipeId = UUID.randomUUID();
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            java.time.LocalDate.of(2026, 1, 5),
            List.of(
                PlanTestData.assignment(
                    skel.slotId(), recipeId, java.time.LocalDate.of(2026, 1, 5), 0, 2)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void lower_floor_with_zero_actual_nutrition_scores_zero() {
    // LOWER_FLOOR target but actual 0 (no recipe nutrition) → deviation -1 → penalty 1 → score 0
    UUID user = UUID.randomUUID();
    TargetsDto targets =
        new TargetsDto(
            UUID.randomUUID(),
            user,
            Goal.MAINTAIN,
            null,
            macro("100", EnforcementDirection.LOWER_FLOOR),
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            null,
            List.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            0L);
    var skel =
        PlanTestData.skeletonFor(
            java.time.LocalDate.of(2026, 1, 5),
            0,
            com.example.mealprep.core.types.SlotKind.DINNER,
            30);
    var ctx =
        PlanTestData.scoringContext(
            List.of(skel),
            List.of(),
            null,
            Map.of(
                user,
                new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(
                    user, null, null)),
            Map.of(user, targets));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            java.time.LocalDate.of(2026, 1, 5),
            List.of(
                PlanTestData.assignment(
                    skel.slotId(), UUID.randomUUID(), java.time.LocalDate.of(2026, 1, 5), 0, 2)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ZERO);
  }
}
