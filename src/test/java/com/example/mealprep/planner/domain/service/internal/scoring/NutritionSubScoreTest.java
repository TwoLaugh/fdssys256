package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.CalorieTargetDto;
import com.example.mealprep.nutrition.api.dto.MacroTargetDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

  // ---- mutation-killing additions -------------------------------------------------------------

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private static CalorieTargetDto calorieTarget(int dailyTarget, EnforcementDirection dir) {
    return new CalorieTargetDto(dailyTarget, 0, 0, "daily", dir);
  }

  private static TargetsDto targets(
      UUID user,
      CalorieTargetDto calories,
      MacroTargetDto protein,
      MacroTargetDto carbs,
      MacroTargetDto fat,
      MacroTargetDto fibre,
      MacroTargetDto satFat) {
    return new TargetsDto(
        UUID.randomUUID(),
        user,
        Goal.MAINTAIN,
        calories,
        protein,
        carbs,
        fat,
        fibre,
        satFat,
        null,
        List.of(),
        List.of(),
        List.of(),
        null,
        List.of(),
        Instant.parse("2026-01-01T00:00:00Z"),
        0L);
  }

  private static PlanCompositionContext ctxFor(
      UUID user, MealSlotSkeleton skel, TargetsDto targets) {
    return PlanTestData.scoringContext(
        List.of(skel),
        List.of(),
        null,
        Map.of(
            user,
            new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(user, null, null)),
        Map.of(user, targets));
  }

  private static CandidatePlan oneSlotPlan(MealSlotSkeleton skel) {
    return PlanTestData.candidatePlan(
        WEEK, List.of(PlanTestData.assignment(skel.slotId(), UUID.randomUUID(), WEEK, 0, 2)));
  }

  /**
   * Kills the L76 ConditionalsBoundary/NegateConditionals on {@code calories().dailyTarget() > 0}:
   * with a LOWER_FLOOR calorie target &gt; 0 and zero actual nutrition the calorie deviation is -1
   * → penalty 1 → score 0. If the {@code > 0} guard were negated/relaxed the calorie macro would be
   * skipped and the vacuous mean would flip to 1.0.
   */
  @Test
  void calorie_lower_floor_target_with_zero_actual_scores_zero() {
    UUID user = UUID.randomUUID();
    MealSlotSkeleton skel =
        PlanTestData.skeletonFor(WEEK, 0, com.example.mealprep.core.types.SlotKind.DINNER, 30);
    TargetsDto t =
        targets(
            user,
            calorieTarget(2000, EnforcementDirection.LOWER_FLOOR),
            null,
            null,
            null,
            null,
            null);
    assertThat(calc.compute(oneSlotPlan(skel), ctxFor(user, skel, t)))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  /**
   * Calories target of exactly 0 must be excluded (the {@code dailyTarget() > 0} guard). With NO
   * other macros configured the scores list is empty and the vacuous mean is 1.0. A boundary
   * mutation ({@code >= 0}) would include a 0-target calorie macro and divide-by-zero or score
   * differently.
   */
  @Test
  void calorie_target_zero_is_excluded_vacuous_one() {
    UUID user = UUID.randomUUID();
    MealSlotSkeleton skel =
        PlanTestData.skeletonFor(WEEK, 0, com.example.mealprep.core.types.SlotKind.DINNER, 30);
    TargetsDto t =
        targets(
            user, calorieTarget(0, EnforcementDirection.LOWER_FLOOR), null, null, null, null, null);
    assertThat(calc.compute(oneSlotPlan(skel), ctxFor(user, skel, t)))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  /**
   * Targets row present but every macro/calorie null → scores list empty → vacuous 1.0. Kills the
   * L90 NullReturnVals mutant ({@code return BigDecimal.ONE} → {@code return null}); a null return
   * would NPE inside the caller's {@code isEqualByComparingTo}.
   */
  @Test
  void targets_row_with_no_configured_macros_is_vacuous_one() {
    UUID user = UUID.randomUUID();
    MealSlotSkeleton skel =
        PlanTestData.skeletonFor(WEEK, 0, com.example.mealprep.core.types.SlotKind.DINNER, 30);
    TargetsDto t = targets(user, null, null, null, null, null, null);
    assertThat(calc.compute(oneSlotPlan(skel), ctxFor(user, skel, t)))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  /**
   * Five LOWER_FLOOR macros (protein/carbs/fat/fibre/satFat) all with zero actual → each scores 0 →
   * mean 0. Removing any one {@code addMacro(...)} VoidMethodCall (L83-87) shrinks the scores list;
   * because every contribution is 0 the mean stays 0, so to make those VoidMethodCall mutants
   * detectable we pair them with an UPPER_LIMIT calorie macro that scores 1.0. The mean of
   * {1,0,0,0,0,0}=1/6; dropping any zero macro changes the denominator → mean changes.
   */
  @Test
  void each_macro_contributes_to_the_mean() {
    UUID user = UUID.randomUUID();
    MealSlotSkeleton skel =
        PlanTestData.skeletonFor(WEEK, 0, com.example.mealprep.core.types.SlotKind.DINNER, 30);
    MacroTargetDto floor = macro("100", EnforcementDirection.LOWER_FLOOR);
    TargetsDto t =
        targets(
            user,
            calorieTarget(2000, EnforcementDirection.UPPER_LIMIT), // 0 actual ≤ limit → 1.0
            floor,
            floor,
            floor,
            floor,
            floor);
    // scores = {calories 1.0, protein 0, carbs 0, fat 0, fibre 0, satFat 0} → 1/6
    BigDecimal expected =
        BigDecimal.ONE.divide(BigDecimal.valueOf(6), 6, java.math.RoundingMode.HALF_UP);
    assertThat(calc.compute(oneSlotPlan(skel), ctxFor(user, skel, t)))
        .isEqualByComparingTo(expected);
  }

  /**
   * Drop one macro vs the previous test: with only 4 LOWER_FLOOR macros + the UPPER_LIMIT calorie,
   * the mean is 1/5 not 1/6. This anchors the exact denominator so removing/keeping any single
   * {@code addMacro} call is observable (the L83-87 VoidMethodCall mutants change the divisor).
   */
  @Test
  void mean_denominator_tracks_number_of_configured_macros() {
    UUID user = UUID.randomUUID();
    MealSlotSkeleton skel =
        PlanTestData.skeletonFor(WEEK, 0, com.example.mealprep.core.types.SlotKind.DINNER, 30);
    MacroTargetDto floor = macro("100", EnforcementDirection.LOWER_FLOOR);
    TargetsDto t =
        targets(
            user,
            calorieTarget(2000, EnforcementDirection.UPPER_LIMIT),
            floor,
            floor,
            floor,
            floor,
            null); // satFat omitted → 5 scores, not 6
    BigDecimal expected =
        BigDecimal.ONE.divide(BigDecimal.valueOf(5), 6, java.math.RoundingMode.HALF_UP);
    assertThat(calc.compute(oneSlotPlan(skel), ctxFor(user, skel, t)))
        .isEqualByComparingTo(expected);
  }

  /**
   * BOTH_BOUNDED direction default-arm coverage: 20% over a BOTH_BOUNDED target → |0.2| penalty →
   * 0.8. Pins the {@code directionScore} BOTH_BOUNDED branch precisely.
   */
  @Test
  void direction_score_both_bounded_twenty_percent_under_is_point_eight() {
    assertThat(
            NutritionSubScore.directionScore(
                new BigDecimal("80"), new BigDecimal("100"), EnforcementDirection.BOTH_BOUNDED))
        .isEqualByComparingTo(new BigDecimal("0.8"));
  }

  /** Zero target (not null) also short-circuits to 1.0 — kills the {@code compareTo == 0} guard. */
  @Test
  void direction_score_zero_target_is_one() {
    assertThat(
            NutritionSubScore.directionScore(
                new BigDecimal("100"), BigDecimal.ZERO, EnforcementDirection.UPPER_LIMIT))
        .isEqualByComparingTo(BigDecimal.ONE);
  }
}
