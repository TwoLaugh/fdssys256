package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit test for {@link TimeSubScore} — LOCKED linear overshoot penalty. */
class TimeSubScoreTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);
  private final TimeSubScore calc = new TimeSubScore();

  @Test
  void name_is_time() {
    assertThat(calc.name()).isEqualTo("time");
  }

  @Test
  void empty_plan_is_vacuous_one() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, List.of()), ctx))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void slot_at_exactly_budget_scores_one() {
    UUID recipeId = UUID.randomUUID();
    MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    RecipeDto recipe =
        PlanTestData.scoredRecipe(recipeId, 30, "Thai", "tofu", "stir-fry", List.of());
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(skel), List.of(recipe));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(skel.slotId(), recipeId, WEEK, 0, 2)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void slot_fifty_percent_over_budget_scores_half() {
    UUID recipeId = UUID.randomUUID();
    MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    RecipeDto recipe = PlanTestData.scoredRecipe(recipeId, 45, "Thai", "tofu", "wok", List.of());
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(skel), List.of(recipe));
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(skel.slotId(), recipeId, WEEK, 0, 2)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(new BigDecimal("0.5"));
  }

  @Test
  void slot_with_no_recipe_in_pool_does_not_penalise() {
    MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(skel), List.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(skel.slotId(), UUID.randomUUID(), WEEK, 0, 2)));
    assertThat(calc.compute(plan, ctx)).isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void mean_over_two_slots() {
    UUID r1 = UUID.randomUUID();
    UUID r2 = UUID.randomUUID();
    MealSlotSkeleton s1 = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    MealSlotSkeleton s2 = PlanTestData.skeletonFor(WEEK, 1, SlotKind.DINNER, 30);
    RecipeDto rec1 = PlanTestData.scoredRecipe(r1, 30, "A", "a", "bake", List.of()); // 1.0
    RecipeDto rec2 = PlanTestData.scoredRecipe(r2, 45, "B", "b", "fry", List.of()); // 0.5
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(s1, s2), List.of(rec1, rec2));
    List<SlotAssignment> as =
        List.of(
            PlanTestData.assignment(s1.slotId(), r1, WEEK, 0, 2),
            PlanTestData.assignment(s2.slotId(), r2, WEEK, 1, 2));
    assertThat(calc.compute(PlanTestData.candidatePlan(WEEK, as), ctx))
        .isEqualByComparingTo(new BigDecimal("0.75"));
  }
}
