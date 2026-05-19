package com.example.mealprep.planner.domain.service.internal.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.types.SlotKind;
import com.example.mealprep.nutrition.api.dto.CandidateDailyRollupDto;
import com.example.mealprep.nutrition.api.dto.CandidatePlanRollupDto;
import com.example.mealprep.nutrition.api.dto.FloorGateResultDto;
import com.example.mealprep.nutrition.domain.service.NutritionFloorGateService;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.MealSlotSkeleton;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

  // ---- mutation-killing additions -------------------------------------------------------------

  /**
   * Non-empty plan but no resolvable primary user (no soft prefs, no skeletons) → the {@code
   * primary == null} branch returns true without touching the service. Kills the L56
   * BooleanFalseReturnVals mutant ({@code return true} → {@code return false}).
   */
  @Test
  void no_primary_user_passes_vacuously_without_service() {
    PlanCompositionContext ctx = PlanTestData.minimalContext(List.of(), List.of());
    boolean passed =
        gate.passes(
            PlanTestData.candidatePlan(
                WEEK,
                List.of(PlanTestData.assignment(UUID.randomUUID(), UUID.randomUUID(), WEEK, 0, 2))),
            ctx);
    assertThat(passed).isTrue();
    verify(service, never()).evaluate(any(), any());
  }

  /**
   * Assignments present but every {@code onDate()} is null → the macro aggregator yields an empty
   * by-date map → the {@code byDate.isEmpty()} branch returns true without calling the service.
   * Kills the L61 BooleanFalseReturnVals mutant ({@code return true} → {@code return false}).
   */
  @Test
  void all_null_dates_yield_empty_rollup_and_pass() {
    UUID user = UUID.randomUUID();
    PlanCompositionContext ctx =
        PlanTestData.scoringContext(
            List.of(PlanTestData.skeletonFor(WEEK, 0, SlotKind.DINNER, 30)),
            List.of(),
            null,
            Map.of(
                user,
                new com.example.mealprep.household.api.dto.SoftPreferenceBundleDto(
                    user, null, null)),
            Map.of());
    SlotAssignment nullDate =
        new SlotAssignment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            null, // onDate null
            SlotKind.DINNER,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    CandidatePlan plan = PlanTestData.candidatePlan(WEEK, List.of(nullDate));

    assertThat(gate.passes(plan, ctx)).isTrue();
    verify(service, never()).evaluate(any(), any());
  }

  /**
   * Captures the {@link CandidatePlanRollupDto} handed to the service for a two-day plan and
   * asserts its window + per-day shape. Kills several mutants in the rollup-building block:
   *
   * <ul>
   *   <li>L69 NegateConditionals ({@code start == null}): the rollup start must be the EARLIEST
   *       date, not null.
   *   <li>L86 NullReturnVals ({@code toDailyRollupDto} → null): the per-day list must contain
   *       non-null entries, one per date.
   *   <li>L85/L98 ({@code micros}/{@code nz}): the mapped daily DTO carries a non-null empty micros
   *       map and zeroed (non-null) macro totals — exactly the documented all-zero divergence.
   * </ul>
   */
  @Test
  void builds_rollup_with_correct_window_and_nonnull_zeroed_days() {
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

    CandidatePlan plan =
        PlanTestData.candidatePlan(
            WEEK,
            List.of(
                PlanTestData.assignment(skel.slotId(), UUID.randomUUID(), WEEK, 0, 2),
                PlanTestData.assignment(
                    UUID.randomUUID(), UUID.randomUUID(), WEEK.plusDays(2), 1, 2)));

    gate.passes(plan, ctx);

    ArgumentCaptor<CandidatePlanRollupDto> captor =
        ArgumentCaptor.forClass(CandidatePlanRollupDto.class);
    verify(service).evaluate(eq(user), captor.capture());
    CandidatePlanRollupDto rollup = captor.getValue();

    assertThat(rollup.startDate()).isEqualTo(WEEK);
    assertThat(rollup.endDate()).isEqualTo(WEEK.plusDays(2));
    assertThat(rollup.perDay()).hasSize(2).doesNotContainNull();
    CandidateDailyRollupDto day0 = rollup.perDay().get(0);
    assertThat(day0.date()).isEqualTo(WEEK);
    assertThat(day0.calories()).isZero();
    assertThat(day0.proteinG()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(day0.carbsG()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(day0.fatG()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(day0.fibreG()).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(day0.micros()).isNotNull();
  }
}
