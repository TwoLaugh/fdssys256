package com.example.mealprep.planner.domain.service.internal.additions;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.Addition;
import com.example.mealprep.planner.api.dto.NutritionCoverageDocument;
import com.example.mealprep.planner.api.dto.NutritionTargetCoverageDocument;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.RollupSummaryDocument;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link IngredientAdditionPlanner}'s deterministic gap-fill — DB-free. Uses an empty
 * slot-skeleton context so the allergy filter is short-circuited (no household eaters to check),
 * exercising the residual/short-micro reading + greedy pick + per-day attach against the real
 * catalogue + USDA-fallback resolver.
 */
class IngredientAdditionPlannerTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  // Real resolver (null query service → catalogue USDA fallback); null filter is never called
  // because the context has no eaters; null AI service → deterministic carrier-slot placement.
  private final IngredientAdditionPlanner planner =
      new IngredientAdditionPlanner(new AdditionNutritionResolver(null), null, null);

  private static NutritionTargetCoverageDocument shortTarget(
      String key, String unit, String target, String projected) {
    return new NutritionTargetCoverageDocument(
        key, unit, new BigDecimal(target), new BigDecimal(projected), "LOWER_FLOOR", false, "SHORT",
        "measured");
  }

  private static RollupSummaryDocument rollupWithGap() {
    NutritionCoverageDocument coverage =
        new NutritionCoverageDocument(
            List.of(shortTarget("calories", "kcal", "3600", "3196")),
            List.of(
                shortTarget("vitamin_e_mg", "mg", "15", "5"),
                shortTarget("magnesium_mg", "mg", "420", "300")),
            0, 5, 16, 28, 0);
    return new RollupSummaryDocument(List.of(), null, coverage);
  }

  @Test
  void attaches_additions_that_close_calorie_and_micro_gaps() {
    // Two days, two slots each; the higher slotIndex (dinner) is the carrier per day.
    List<SlotAssignment> assignments =
        List.of(
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 2, 2),
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK.plusDays(1), 0, 2),
            PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK.plusDays(1), 2, 2));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    List<SlotAssignment> result = planner.attach(assignments, rollupWithGap(), ctx);

    List<SlotAssignment> withAdditions =
        result.stream().filter(a -> !a.additions().isEmpty()).toList();
    // One carrier slot per day → exactly 2 days get additions.
    assertThat(withAdditions).hasSize(2);
    // Carriers are the dinner slots (slotIndex 2), not the breakfast slots.
    assertThat(withAdditions).allSatisfy(a -> assertThat(a.slotIndex()).isEqualTo(2));

    List<Addition> picks = withAdditions.get(0).additions();
    assertThat(picks).isNotEmpty().hasSizeLessThanOrEqualTo(3);
    // Picks meaningfully close the ~404 kcal residual.
    int pickedKcal = picks.stream().mapToInt(p -> p.nutrition().calories()).sum();
    assertThat(pickedKcal).isGreaterThan(200);
    // At least one pick reinforces the short vitamin_e (the greedy ranks micro-rich picks up).
    assertThat(picks)
        .anySatisfy(p -> assertThat(p.nutrition().micros()).containsKey("vitamin_e_mg"));
  }

  @Test
  void no_additions_when_coverage_is_already_met() {
    NutritionCoverageDocument met =
        new NutritionCoverageDocument(
            List.of(
                new NutritionTargetCoverageDocument(
                    "calories", "kcal", new BigDecimal("3600"), new BigDecimal("3590"),
                    "LOWER_FLOOR", true, "MET", "measured")),
            List.of(),
            5, 5, 28, 28, 0);
    List<SlotAssignment> assignments =
        List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    List<SlotAssignment> result =
        planner.attach(assignments, new RollupSummaryDocument(List.of(), null, met), ctx);

    assertThat(result).allSatisfy(a -> assertThat(a.additions()).isEmpty());
  }

  @Test
  void no_additions_when_no_coverage() {
    List<SlotAssignment> assignments =
        List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2));
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());

    List<SlotAssignment> result =
        planner.attach(assignments, new RollupSummaryDocument(List.of(), null, null), ctx);

    assertThat(result).isEqualTo(assignments);
  }
}
