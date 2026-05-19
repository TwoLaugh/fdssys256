package com.example.mealprep.planner.domain.service.internal.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.DailyRollupDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.ScoreBreakdownDocument;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.domain.service.internal.scoring.NutritionFloorGate;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit test for {@link RollupBuilderImpl} (Stage B). Verifies daily/weekly aggregation, sort order,
 * stale counting, variety-index passthrough, the unfilled-slot + hard-floor-breach violations, and
 * determinism.
 */
class RollupBuilderTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private final NutritionFloorGate floorGate = Mockito.mock(NutritionFloorGate.class);

  private RollupBuilderImpl builder(NutritionFloorGate gate) {
    return new RollupBuilderImpl(
        new DailyMacroAggregator(),
        new DailyCostAggregator(),
        new WeeklyCostConfidence(PlanTestData.scoringProperties()),
        gate);
  }

  @Test
  void empty_plan_yields_empty_daily_and_zeroed_weekly() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    RollupSummaryDocument doc =
        builder(floorGate)
            .build(
                PlanTestData.candidatePlan(WEEK, List.of()),
                PlanTestData.minimalContext(List.of(), List.of()));

    assertThat(doc.daily()).isEmpty();
    assertThat(doc.weekly().kcalTotal()).isZero();
    assertThat(doc.weekly().staleIngredientCount()).isZero();
    assertThat(doc.weekly().batchCookSessions()).isZero();
    assertThat(doc.weekly().costEstimateGbp()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(doc.weekly().varietyIndex()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(doc.weekly().constraintViolations()).isEmpty();
  }

  @Test
  void daily_list_is_date_ascending_and_one_entry_per_day() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 30, "Thai", "tofu", "fry", List.of("rice"));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(2), 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(1), 0, 2)));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    List<DailyRollupDocument> daily = builder(floorGate).build(plan, ctx).daily();

    assertThat(daily)
        .extracting(DailyRollupDocument::date)
        .containsExactly(WEEK, WEEK.plusDays(1), WEEK.plusDays(2));
  }

  @Test
  void total_time_sums_recipe_metadata_per_day() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 45, "Thai", "tofu", "fry", List.of("rice"));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 1, 2)));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    DailyRollupDocument day0 = builder(floorGate).build(plan, ctx).daily().get(0);
    assertThat(day0.totalTimeMin()).isEqualTo(90); // 45 + 45
  }

  @Test
  void cost_sums_across_days_and_weekly_equals_sum_of_daily() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 30, "Thai", "tofu", "fry", List.of("rice"));
    SupplierProductDto rice = PlanTestData.supplierProduct("rice", new BigDecimal("1.00"));
    var bundle =
        PlanTestData.provisionsBundle(
            PlanTestData.budget(new BigDecimal("50")), Map.of("rice", rice), List.of());
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(List.of(), List.of(recipe), bundle, Map.of(), Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(1), 0, 3)));

    RollupSummaryDocument doc = builder(floorGate).build(plan, ctx);
    BigDecimal sumDaily =
        doc.daily().stream()
            .map(DailyRollupDocument::costGbp)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    assertThat(doc.daily().get(0).costGbp()).isEqualByComparingTo(new BigDecimal("2.00"));
    assertThat(doc.daily().get(1).costGbp()).isEqualByComparingTo(new BigDecimal("3.00"));
    assertThat(doc.weekly().costEstimateGbp()).isEqualByComparingTo(sumDaily);
    assertThat(doc.weekly().costEstimateGbp()).isEqualByComparingTo(new BigDecimal("5.00"));
  }

  @Test
  void stale_count_is_distinct_recipes_with_non_calculated_nutrition() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    // scoredRecipe defaults to NutritionStatus.PENDING → both stale
    RecipeDto recipe1 = PlanTestData.scoredRecipe(r1, 30, "Thai", "tofu", "fry", List.of("rice"));
    RecipeDto recipe2 = PlanTestData.scoredRecipe(r2, 30, "Thai", "beef", "fry", List.of("beef"));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), r1, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), r1, WEEK.plusDays(1), 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), r2, WEEK, 1, 2)));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe1, recipe2));

    // r1 appears twice but counts once → 2 distinct stale recipes
    assertThat(builder(floorGate).build(plan, ctx).weekly().staleIngredientCount()).isEqualTo(2);
  }

  @Test
  void variety_index_read_from_score_result_breakdown_not_recomputed() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 30, "Thai", "tofu", "fry", List.of("rice"));
    ScoreBreakdownDocument breakdown =
        new ScoreBreakdownDocument(
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("0.77"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            true,
            true,
            "v1-uniform");
    CandidatePlan plan =
        new CandidatePlan(
            UUID.randomUUID(),
            WEEK,
            List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)),
            new ScoreResult(BigDecimal.ZERO, breakdown));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    assertThat(builder(floorGate).build(plan, ctx).weekly().varietyIndex())
        .isEqualByComparingTo(new BigDecimal("0.77"));
  }

  @Test
  void unfilled_slot_produces_daily_and_weekly_violation() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID missing = UUID.randomUUID();
    SlotAssignment a = PlanTestData.assignment(UUID.randomUUID(), missing, WEEK, 0, 2);
    CandidatePlan plan = PlanTestData.candidatePlan(WEEK, List.of(a));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    RollupSummaryDocument doc = builder(floorGate).build(plan, ctx);

    assertThat(doc.daily().get(0).violations()).hasSize(1);
    assertThat(doc.daily().get(0).violations().get(0)).contains("unfilled");
    assertThat(doc.weekly().constraintViolations()).anyMatch(v -> v.contains("unfilled"));
  }

  @Test
  void hard_floor_breach_added_when_gate_fails() {
    when(floorGate.passes(any(), any())).thenReturn(false);
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 30, "Thai", "tofu", "fry", List.of("rice"));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2)));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    assertThat(builder(floorGate).build(plan, ctx).weekly().constraintViolations())
        .contains("hard floor breach");
  }

  /**
   * The weekly protein/fat/carbs averages are computed via {@code average(...)}. Asserting they are
   * a non-null zero (recipe nutrition not exposed → all daily macros 0 → 0/n = 0.0) kills the L136
   * NullReturnVals mutant ({@code return ...divide(...)} → {@code return null}); a null average
   * would NPE inside {@code isEqualByComparingTo}.
   */
  @Test
  void weekly_macro_averages_are_nonnull_zero() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 30, "Thai", "tofu", "fry", List.of("rice"));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(1), 0, 2)));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    var weekly = builder(floorGate).build(plan, ctx).weekly();

    assertThat(weekly.proteinAvgG()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(weekly.fatAvgG()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(weekly.carbsAvgG()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
  }

  /**
   * Recipe absent from the pool exercises the indexRecipes empty-result path (L229/L230) and the
   * total-time / violations aggregation with an unresolvable recipe — covering those previously
   * NO_COVERAGE return lines while still asserting the unfilled-slot contract.
   */
  @Test
  void unresolvable_recipe_zero_time_and_unfilled_violation() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID missing = UUID.randomUUID();
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(UUID.randomUUID(), missing, WEEK, 0, 2)));
    // empty recipe pool → indexRecipes returns an empty index
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    RollupSummaryDocument doc = builder(floorGate).build(plan, ctx);

    assertThat(doc.daily().get(0).totalTimeMin()).isZero();
    assertThat(doc.daily().get(0).violations()).anyMatch(v -> v.contains("unfilled"));
  }

  @Test
  void deterministic_same_input_byte_identical_output() {
    when(floorGate.passes(any(), any())).thenReturn(true);
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 30, "Thai", "tofu", "fry", List.of("rice"));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(1), 0, 2)));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    assertThat(builder(floorGate).build(plan, ctx)).isEqualTo(builder(floorGate).build(plan, ctx));
  }
}
