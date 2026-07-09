package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit test for {@link CostSubScore} — LOCKED confidence-weighted cost-fit formula. */
class CostSubScoreTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final CostSubScore calc =
      new CostSubScore(
          PlanTestData.scoringProperties(),
          new com.example.mealprep.planner.domain.service.internal.rollup.DailyCostAggregator(),
          new com.example.mealprep.planner.domain.service.internal.rollup.WeeklyCostConfidence(
              PlanTestData.scoringProperties()));

  @Test
  void name_is_cost() {
    assertThat(calc.name()).isEqualTo("cost");
  }

  @Test
  void null_budget_returns_neutral() {
    var bundle = PlanTestData.provisionsBundle(PlanTestData.budget(null), Map.of(), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(), bundle, Map.of(), Map.of());
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, List.of()), ctx))
        .isEqualByComparingTo(new BigDecimal("0.5"));
  }

  /**
   * Zero weekly budget must short-circuit past the cost-fit division (the {@code
   * weeklyTarget().compareTo(0) <= 0} guard) into the no-budget INGREDIENT-REUSE path — NOT divide by
   * zero. Kills the L70 ConditionalsBoundary mutant {@code <= 0} → {@code < 0}: with the mutant a zero
   * target falls through and divides estimatedCost by a zero budget, throwing instead of returning a
   * reuse score. A single recipe shares nothing, so reuse = {@code 1 − 1/1 = 0}.
   */
  @Test
  void zero_budget_takes_reuse_path_not_divide_by_zero() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    var bundle =
        PlanTestData.provisionsBundle(PlanTestData.budget(BigDecimal.ZERO), Map.of(), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  /**
   * No-budget reuse reward: a plan whose distinct recipes SHARE ingredient keys scores higher than
   * one whose recipes are disjoint (a smaller shopping list). Shared = 4 distinct of 6 total → {@code
   * 1 − 4/6 ≈ 0.333}; disjoint = 6 of 6 → {@code 0}.
   */
  @Test
  void reuse_rewards_ingredient_overlap() {
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    UUID r3 = UUID.randomUUID();
    RecipeDto rec1 =
        PlanTestData.scoredRecipe(r1, 20, "Thai", "tofu", "fry", List.of("rice", "tofu", "soy"));
    RecipeDto rec2 =
        PlanTestData.scoredRecipe(r2, 20, "Thai", "tofu", "fry", List.of("rice", "tofu", "ginger"));
    RecipeDto rec3 =
        PlanTestData.scoredRecipe(r3, 20, "Brit", "beef", "roast", List.of("beef", "potato", "carrot"));
    var bundle = PlanTestData.provisionsBundle(PlanTestData.budget(null), Map.of(), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(rec1, rec2, rec3), bundle, Map.of(), Map.of());
    CandidatePlan shared =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), r1, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), r2, WEEK, 1, 2)));
    CandidatePlan disjoint =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), r1, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), r3, WEEK, 1, 2)));
    assertThat(calc.compute(shared, ctx)).isEqualByComparingTo(new BigDecimal("0.333333"));
    assertThat(calc.compute(disjoint, ctx)).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(calc.compute(shared, ctx)).isGreaterThan(calc.compute(disjoint, ctx));
  }

  @Test
  void no_supplier_prices_collapses_to_neutral() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe =
        PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice", "tofu"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of(), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    // mean_confidence ≈ 0 → 0.5 + (raw - 0.5) × 0 = 0.5
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  void well_under_budget_full_confidence_scores_near_one() {
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
    // cost = 0.10 * 1 * 2 = 0.20; raw_fit ≈ 1 - 0.004 ≈ 0.996; conf=1 → ≈0.996
    BigDecimal score = calc.compute(plan, ctx);
    assertThat(score).isGreaterThan(new BigDecimal("0.9"));
    assertThat(score).isLessThanOrEqualTo(BigDecimal.ONE);
  }

  @Test
  void over_budget_full_confidence_collapses_toward_zero() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("caviar"));
    SupplierProductDto caviar = PlanTestData.supplierProduct("caviar", new BigDecimal("500"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of("caviar", caviar), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    // cost 1000 >> budget 50 → raw_fit clamps to 0; conf 1 → 0.5 + (0 - 0.5)*1 = 0
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ZERO);
  }
}
