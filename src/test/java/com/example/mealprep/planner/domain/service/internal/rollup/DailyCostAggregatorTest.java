package com.example.mealprep.planner.domain.service.internal.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit test for {@link DailyCostAggregator} — Σ (price × quantity × servings) per day. */
class DailyCostAggregatorTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final DailyCostAggregator agg = new DailyCostAggregator();

  @Test
  void no_provisions_means_zero_cost_buckets_but_day_present() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    SlotAssignment a = PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2);
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    Map<LocalDate, BigDecimal> result =
        agg.aggregateByDate(PlanTestData.candidatePlan(WEEK, List.of(a)), ctx);

    assertThat(result).containsOnlyKeys(WEEK);
    assertThat(result.get(WEEK)).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void priced_ingredient_multiplies_price_quantity_servings() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    // recipeFor/scoredRecipe set ingredient quantity = 1; servings = 3 here
    SupplierProductDto rice = PlanTestData.supplierProduct("rice", new BigDecimal("0.50"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of("rice", rice), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    SlotAssignment a = PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 3);

    Map<LocalDate, BigDecimal> result =
        agg.aggregateByDate(PlanTestData.candidatePlan(WEEK, List.of(a)), ctx);

    // 0.50 * 1 * 3 = 1.50
    assertThat(result.get(WEEK)).isEqualByComparingTo(new BigDecimal("1.50"));
  }

  @Test
  void total_cost_sums_across_days_and_equals_sum_of_buckets() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    SupplierProductDto rice = PlanTestData.supplierProduct("rice", new BigDecimal("2.00"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of("rice", rice), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 1),
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(1), 0, 2)));

    Map<LocalDate, BigDecimal> buckets = agg.aggregateByDate(plan, ctx);
    BigDecimal bucketSum = buckets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

    // day0: 2*1*1=2 ; day1: 2*1*2=4 ; total 6
    assertThat(buckets.get(WEEK)).isEqualByComparingTo(new BigDecimal("2.00"));
    assertThat(buckets.get(WEEK.plusDays(1))).isEqualByComparingTo(new BigDecimal("4.00"));
    assertThat(agg.totalCost(plan, ctx)).isEqualByComparingTo(bucketSum);
    assertThat(agg.totalCost(plan, ctx)).isEqualByComparingTo(new BigDecimal("6.00"));
  }

  @Test
  void empty_plan_yields_empty_map() {
    assertThat(
            agg.aggregateByDate(
                PlanTestData.candidatePlan(WEEK, List.of()),
                PlanTestData.minimalContext(List.of(), List.of())))
        .isEmpty();
  }
}
