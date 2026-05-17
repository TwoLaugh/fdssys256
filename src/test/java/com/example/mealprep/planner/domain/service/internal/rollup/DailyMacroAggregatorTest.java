package com.example.mealprep.planner.domain.service.internal.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link DailyMacroAggregator}. Recipe nutrition is not exposed on {@code
 * RecipeVersionDto} in this codebase, so macro totals are 0 — these tests pin the date-bucketing,
 * sort order, and "every assigned day gets a bucket" contract that the rollup + gate depend on.
 */
class DailyMacroAggregatorTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final DailyMacroAggregator agg = new DailyMacroAggregator();

  @Test
  void empty_plan_yields_empty_map() {
    assertThat(
            agg.aggregateByDate(
                PlanTestData.candidatePlan(WEEK, List.of()),
                PlanTestData.minimalContext(List.of(), List.of())))
        .isEmpty();
  }

  @Test
  void null_assignments_yields_empty_map() {
    CandidatePlan plan = new CandidatePlan(UUID.randomUUID(), WEEK, null, null);
    assertThat(agg.aggregateByDate(plan, PlanTestData.minimalContext(List.of(), List.of())))
        .isEmpty();
  }

  @Test
  void each_assigned_day_gets_a_zeroed_bucket_even_when_recipe_missing_from_pool() {
    UUID r1 = UUID.randomUUID();
    SlotAssignment a1 = PlanTestData.assignment(UUID.randomUUID(), r1, WEEK, 0, 2);
    SlotAssignment a2 = PlanTestData.assignment(UUID.randomUUID(), r1, WEEK.plusDays(1), 0, 2);
    CandidatePlan plan = PlanTestData.candidatePlan(WEEK, List.of(a1, a2));
    // recipe r1 is NOT in the pool → 0-macro contribution but the day still appears
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    Map<LocalDate, DailyMacroTotals> result = agg.aggregateByDate(plan, ctx);

    assertThat(result.keySet()).containsExactly(WEEK, WEEK.plusDays(1));
    DailyMacroTotals d0 = result.get(WEEK);
    assertThat(d0.kcal()).isZero();
    assertThat(d0.proteinG()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
    assertThat(d0.fatG()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
    assertThat(d0.carbsG()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
    assertThat(d0.fibreG()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
  }

  @Test
  void resolvable_recipe_still_zero_macros_no_nutrition_exposed() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 20, "Thai", "tofu", "fry", List.of("rice"));
    SlotAssignment a = PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 4);
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    DailyMacroTotals totals =
        agg.aggregateByDate(PlanTestData.candidatePlan(WEEK, List.of(a)), ctx).get(WEEK);

    assertThat(totals.kcal()).isZero();
    assertThat(totals.proteinG()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
  }

  @Test
  void buckets_are_date_ascending_regardless_of_assignment_order() {
    UUID id = UUID.randomUUID();
    SlotAssignment late = PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(3), 0, 1);
    SlotAssignment early = PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 1);
    SlotAssignment mid = PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(1), 0, 1);
    CandidatePlan plan = PlanTestData.candidatePlan(WEEK, List.of(late, early, mid));

    assertThat(
            agg.aggregateByDate(plan, PlanTestData.minimalContext(List.of(), List.of())).keySet())
        .containsExactly(WEEK, WEEK.plusDays(1), WEEK.plusDays(3));
  }

  @Test
  void deterministic_same_input_same_output() {
    UUID id = UUID.randomUUID();
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK, 0, 2),
                PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(1), 0, 2)));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    assertThat(agg.aggregateByDate(plan, ctx)).isEqualTo(agg.aggregateByDate(plan, ctx));
  }
}
