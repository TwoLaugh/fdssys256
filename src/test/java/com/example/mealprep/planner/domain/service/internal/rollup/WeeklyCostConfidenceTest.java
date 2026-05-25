package com.example.mealprep.planner.domain.service.internal.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.config.PlannerProperties;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link WeeklyCostConfidence} — confidence-weighted mean (presence proxy: priced =
 * 1.0, unpriced = 0.0, sub-threshold clamps to 0).
 */
class WeeklyCostConfidenceTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final WeeklyCostConfidence calc =
      new WeeklyCostConfidence(PlanTestData.scoringProperties());

  @Test
  void zero_ingredients_yields_zero() {
    assertThat(
            calc.compute(
                PlanTestData.candidatePlan(WEEK, List.of()),
                PlanTestData.minimalContext(List.of(), List.of())))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void all_priced_ingredients_yield_one() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe =
        PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice", "tofu"));
    SupplierProductDto rice = PlanTestData.supplierProduct("rice", new BigDecimal("0.10"));
    SupplierProductDto tofu = PlanTestData.supplierProduct("tofu", new BigDecimal("0.20"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")),
            Map.of("rice", rice, "tofu", tofu),
            List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));

    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void half_priced_ingredients_yield_one_half() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe =
        PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice", "caviar"));
    SupplierProductDto rice = PlanTestData.supplierProduct("rice", new BigDecimal("0.10"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of("rice", rice), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));

    // 1 priced + 1 unpriced over 2 ingredients → 0.5
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.5"));
  }

  // ---- mutation-killing additions -------------------------------------------------------------

  private static PlannerProperties propsWithCostThreshold(BigDecimal threshold) {
    return new PlannerProperties(
        java.time.DayOfWeek.MONDAY,
        20,
        5,
        3,
        50,
        new BigDecimal("1.5"),
        java.time.Duration.ofSeconds(30),
        PlanTestData.uniformWeights(),
        new PlannerProperties.ScoringTuning(
            PlanTestData.defaultTuning().variety(),
            PlanTestData.defaultTuning().provisions(),
            new PlannerProperties.ScoringTuning.CostTuning(threshold)),
        java.time.Duration.ofSeconds(20),
        3,
        5,
        2,
        PlanTestData.defaultMidWeek(),
        PlanTestData.defaultMateriality(),
        PlanTestData.defaultColdStart());
  }

  /**
   * With the confidence threshold set to exactly 1.0, a priced ingredient's proxy confidence (1.0)
   * is NOT below the threshold ({@code 1.compareTo(1) == 0}, not {@code < 0}) → stays 1.0 → mean
   * 1.0. Kills the L60 ConditionalsBoundary mutant {@code confidence < threshold} → {@code
   * confidence <= threshold}: under {@code <=} the equal confidence would clamp to 0 → mean 0.0.
   */
  @Test
  void confidence_equal_to_threshold_is_not_clamped() {
    WeeklyCostConfidence thresholdOne =
        new WeeklyCostConfidence(propsWithCostThreshold(BigDecimal.ONE));
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    SupplierProductDto rice = PlanTestData.supplierProduct("rice", new BigDecimal("0.10"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of("rice", rice), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));

    assertThat(thresholdOne.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ONE);
  }

  /**
   * Ingredients present but the recipe pool is empty (recipe unresolvable) → no ingredient is
   * counted → zero. Exercises the indexRecipes empty-pool guard (L83/L84) for line coverage so the
   * recipe-not-found path is no longer NO_COVERAGE.
   */
  @Test
  void unresolvable_recipe_yields_zero() {
    UUID id = UUID.randomUUID();
    SupplierProductDto rice = PlanTestData.supplierProduct("rice", new BigDecimal("0.10"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of("rice", rice), List.of());
    // recipe id NOT in the (empty) pool
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));

    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ZERO);
  }
}
