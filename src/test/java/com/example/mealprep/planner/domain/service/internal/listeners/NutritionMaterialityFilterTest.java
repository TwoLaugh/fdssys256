package com.example.mealprep.planner.domain.service.internal.listeners;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.nutrition.api.dto.DivergenceSummaryDto;
import com.example.mealprep.nutrition.event.NutritionIntakeDivergedEvent;
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.SlotState;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link NutritionMaterialityFilter} — magnitude threshold (15%) AND a
 * minimum-redistributable-meals (3) check. Pure arithmetic, no mocks.
 */
class NutritionMaterialityFilterTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private final NutritionMaterialityFilter filter =
      new NutritionMaterialityFilter(PlanTestData.scoringProperties());

  /** A plan with {@code plannedMeals} regenerable slots (one day, N slots, all PLANNED). */
  private Plan planWith(int plannedMeals) {
    return PlanTestData.newPlanGraph(
        UUID.randomUUID(), WEEK, 1, PlanStatus.GENERATED, 1, plannedMeals);
  }

  private NutritionIntakeDivergedEvent event(BigDecimal maxVariance, boolean resolved) {
    DivergenceSummaryDto summary =
        new DivergenceSummaryDto(
            Map.of("protein", new BigDecimal("100")),
            Map.of("protein", new BigDecimal("120")),
            Map.of("protein", maxVariance));
    return new NutritionIntakeDivergedEvent(
        UUID.randomUUID(),
        WEEK.plusDays(1),
        resolved ? Set.of() : Set.of("protein"),
        summary,
        UUID.randomUUID(),
        Instant.now());
  }

  @Test
  void material_whenVarianceAtThreshold_andEnoughRedistributableMeals() {
    Plan plan = planWith(3);
    assertThat(filter.isMaterial(event(new BigDecimal("0.15"), false), plan)).isTrue();
  }

  @Test
  void material_handlesNegativeUndershootByAbsoluteValue() {
    Plan plan = planWith(3);
    assertThat(filter.isMaterial(event(new BigDecimal("-0.30"), false), plan)).isTrue();
  }

  @Test
  void immaterial_whenVarianceBelowThreshold() {
    Plan plan = planWith(5);
    assertThat(filter.isMaterial(event(new BigDecimal("0.10"), false), plan)).isFalse();
  }

  @Test
  void immaterial_whenNotEnoughRedistributableMeals() {
    Plan plan = planWith(2); // < default min of 3
    assertThat(filter.isMaterial(event(new BigDecimal("0.50"), false), plan)).isFalse();
  }

  @Test
  void immaterial_whenDivergenceResolvedToEmptySet() {
    Plan plan = planWith(5);
    assertThat(filter.isMaterial(event(new BigDecimal("0.90"), true), plan)).isFalse();
  }

  @Test
  void immaterial_whenAllRedistributableSlotsAreEaten() {
    Plan plan = planWith(5);
    for (Day d : plan.getDays()) {
      for (MealSlot s : d.getSlots()) {
        s.setState(SlotState.EATEN);
      }
    }
    assertThat(filter.isMaterial(event(new BigDecimal("0.50"), false), plan)).isFalse();
  }
}
