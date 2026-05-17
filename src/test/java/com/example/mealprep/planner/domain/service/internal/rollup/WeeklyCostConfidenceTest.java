package com.example.mealprep.planner.domain.service.internal.rollup;

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
}
