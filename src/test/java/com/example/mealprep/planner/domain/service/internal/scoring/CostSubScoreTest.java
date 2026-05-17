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
  private final CostSubScore calc = new CostSubScore(PlanTestData.scoringProperties());

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
