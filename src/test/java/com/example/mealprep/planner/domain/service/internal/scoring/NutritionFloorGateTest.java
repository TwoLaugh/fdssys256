package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.FloorGateResultDto;
import com.example.mealprep.nutrition.domain.service.NutritionFloorGateService;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Unit test for {@link NutritionFloorGate} — delegation to {@link NutritionFloorGateService}. */
class NutritionFloorGateTest {

  private static final LocalDate WEEK = LocalDate.of(2026, 1, 5);

  private final NutritionFloorGateService service = Mockito.mock(NutritionFloorGateService.class);
  private final NutritionFloorGate gate =
      new NutritionFloorGate(
          service,
          new com.example.mealprep.planner.domain.service.internal.rollup.DailyMacroAggregator());

  @Test
  void empty_plan_passes_without_calling_service() {
    boolean passed =
        gate.passes(
            PlanTestData.candidatePlan(WEEK, List.of()),
            PlanTestData.minimalContext(List.of(), List.of()));
    assertThat(passed).isTrue();
    verify(service, never()).evaluate(any(), any());
  }

  @Test
  void delegates_to_service_and_returns_passed_bit() {
    UUID user = UUID.randomUUID();
    MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(
            List.of(skel),
            List.of(),
            null,
            Map.of(
                user,
                new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(
                    user, null, null)),
            Map.of());
    when(service.evaluate(eq(user), any(CandidatePlanRollupDto.class)))
        .thenReturn(new FloorGateResultDto(false, List.of(), "breached"));

    boolean passed =
        gate.passes(
            PlanTestData.candidatePlan(
                WEEK,
                List.of(PlanTestData.assignment(skel.slotId(), UUID.randomUUID(), WEEK, 0, 2))),
            ctx);

    assertThat(passed).isFalse();
    verify(service).evaluate(eq(user), any(CandidatePlanRollupDto.class));
  }

  @Test
  void passes_when_service_returns_passed_true() {
    UUID user = UUID.randomUUID();
    MealSlotSkeleton skel = PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30);
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(
            List.of(skel),
            List.of(),
            null,
            Map.of(
                user,
                new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(
                    user, null, null)),
            Map.of());
    when(service.evaluate(eq(user), any(CandidatePlanRollupDto.class)))
        .thenReturn(new FloorGateResultDto(true, List.of(), "ok"));

    boolean passed =
        gate.passes(
            PlanTestData.candidatePlan(
                WEEK,
                List.of(PlanTestData.assignment(skel.slotId(), UUID.randomUUID(), WEEK, 0, 2))),
            ctx);

    assertThat(passed).isTrue();
  }
}
