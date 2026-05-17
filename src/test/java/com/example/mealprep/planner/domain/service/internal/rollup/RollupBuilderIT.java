package com.example.mealprep.planner.domain.service.internal.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.domain.service.internal.scoring.NutritionFloorGate;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full-context IT: the shared rollup helpers are component-scanned, {@link RollupBuilder} resolves
 * to {@link RollupBuilderImpl}, and the refactored {@link NutritionFloorGate} (now delegating to
 * {@link DailyMacroAggregator}) returns the same verdict as 01e against a canonical 7-day fixture
 * (no nutrition targets → {@code passed=true}, exactly as 01e's gate tests asserted).
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class RollupBuilderIT {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  @Autowired private RollupBuilder rollupBuilder;
  @Autowired private NutritionFloorGate nutritionFloorGate;
  @Autowired private DailyMacroAggregator macroAggregator;
  @Autowired private DailyCostAggregator costAggregator;
  @Autowired private WeeklyCostConfidence weeklyCostConfidence;

  @Test
  void shared_helpers_and_builder_are_wired() {
    assertThat(rollupBuilder).isInstanceOf(RollupBuilderImpl.class);
    assertThat(nutritionFloorGate).isNotNull();
    assertThat(macroAggregator).isNotNull();
    assertThat(costAggregator).isNotNull();
    assertThat(weeklyCostConfidence).isNotNull();
  }

  @Test
  void seven_day_plan_rollup_has_seven_sorted_daily_entries() {
    UUID id = UUID.randomUUID();
    RecipeDto recipe = PlanTestData.scoredRecipe(id, 30, "Thai", "tofu", "fry", List.of("rice"));
    List<com.example.mealprep.planner.api.dto.SlotAssignment> assignments = new ArrayList<>();
    for (int d = 0; d < 7; d++) {
      assignments.add(PlanTestData.assignment(UUID.randomUUID(), id, WEEK.plusDays(d), 0, 2));
    }
    CandidatePlan plan = PlanTestData.candidatePlan(WEEK, assignments);
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of(recipe));

    RollupSummaryDocument doc = rollupBuilder.build(plan, ctx);

    assertThat(doc.daily()).hasSize(7);
    assertThat(doc.daily())
        .extracting(com.example.mealprep.planner.api.dto.DailyRollupDocument::date)
        .isSorted();
    assertThat(doc.weekly().staleIngredientCount()).isEqualTo(1); // one distinct PENDING recipe
  }

  @Test
  void refactored_floor_gate_passes_with_no_targets_unchanged_from_01e() {
    UUID user = UUID.randomUUID();
    var skel =
        PlanTestData.skeletonFor(WEEK, 0, com.example.mealprep.core.types.SlotKind.DINNER, 30);
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(
            List.of(skel),
            List.of(),
            null,
            java.util.Map.of(
                user,
                new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(
                    user, null, null)),
            java.util.Map.of());
    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK, List.of(PlanTestData.assignment(skel.slotId(), UUID.randomUUID(), WEEK, 0, 2)));

    // No TargetsDto wired for the user → NutritionFloorGateService returns passed=true (01e spec).
    assertThat(nutritionFloorGate.passes(plan, ctx)).isTrue();
  }

  @Test
  void empty_plan_floor_gate_passes_vacuously_unchanged_from_01e() {
    assertThat(
            nutritionFloorGate.passes(
                PlanTestData.candidatePlan(WEEK, List.of()),
                PlanTestData.minimalContext(List.of(), List.of())))
        .isTrue();
  }
}
