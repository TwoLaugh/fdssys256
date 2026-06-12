package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.DailyAggregateDto;
import com.example.mealprep.nutrition.api.dto.FloorViolationDto;
import com.example.mealprep.nutrition.api.dto.WeeklyAggregateDto;
import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
import com.example.mealprep.nutrition.domain.entity.IntakeSource;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.domain.service.internal.IntakeAggregator;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit-level coverage of {@link IntakeAggregator}: per-day and per-week rollup math. */
@ExtendWith(MockitoExtension.class)
class IntakeAggregatorTest {

  @Mock private IntakeDayRepository intakeDayRepository;
  @Mock private NutritionTargetsRepository targetsRepository;

  private IntakeAggregator aggregator() {
    return new IntakeAggregator(intakeDayRepository, targetsRepository);
  }

  private static final LocalDate MONDAY = LocalDate.of(2026, 5, 11); // Monday
  private static final LocalDate DAY = LocalDate.of(2026, 5, 12);

  @Test
  void aggregateWeek_noDays_returnsSevenZeroEntries_andEmptyViolations_whenNoTargets() {
    UUID userId = UUID.randomUUID();
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of());
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    assertThat(out.weekStart()).isEqualTo(MONDAY);
    assertThat(out.weekEnd()).isEqualTo(MONDAY.plusDays(6));
    assertThat(out.perDay()).hasSize(7);
    assertThat(out.perDay())
        .allSatisfy(
            d -> {
              assertThat(d.caloriesPlanned()).isZero();
              assertThat(d.caloriesActualSoFar()).isZero();
            });
    assertThat(out.weeklyTotal().caloriesPlanned()).isZero();
    assertThat(out.floorViolations()).isEmpty();
  }

  @Test
  void aggregateWeek_threeOfSevenPopulated_zeroFillsMissingDays() {
    UUID userId = UUID.randomUUID();
    IntakeDay d1 = day(userId, MONDAY, confirmedSlot(MealSlot.BREAKFAST, 500, 30, 60, 15, 8));
    IntakeDay d3 =
        day(userId, MONDAY.plusDays(2), confirmedSlot(MealSlot.LUNCH, 600, 40, 70, 20, 10));
    IntakeDay d5 =
        day(userId, MONDAY.plusDays(4), confirmedSlot(MealSlot.DINNER, 700, 40, 80, 25, 12));
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of(d1, d3, d5));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    assertThat(out.perDay()).hasSize(7);
    assertThat(out.perDay().get(0).caloriesActualSoFar()).isEqualTo(500);
    assertThat(out.perDay().get(1).caloriesActualSoFar()).isZero();
    assertThat(out.perDay().get(2).caloriesActualSoFar()).isEqualTo(600);
    assertThat(out.perDay().get(3).caloriesActualSoFar()).isZero();
    assertThat(out.perDay().get(4).caloriesActualSoFar()).isEqualTo(700);
    assertThat(out.weeklyTotal().caloriesActualSoFar()).isEqualTo(500 + 600 + 700);
    assertThat(out.weeklyTotal().protein().actualSoFarG())
        .isEqualByComparingTo(new BigDecimal("110.00"));
  }

  @Test
  void aggregateWeek_dailyFloorBreached_datedEntryPerViolatingTrackedDay() {
    UUID userId = UUID.randomUUID();
    // protein is daily_floor-enforced (builder); floor 100g/day. Only Monday has an intake row
    // (10g actual < 100g) -> exactly one DATED entry. Untracked days are absent data, not
    // violations.
    IntakeDay only = day(userId, MONDAY, confirmedSlot(MealSlot.BREAKFAST, 200, 10, 20, 5, 2));
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of(only));
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withProteinFloor(BigDecimal.valueOf(100.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    assertThat(out.floorViolations()).hasSize(1);
    FloorViolationDto v = out.floorViolations().get(0);
    assertThat(v.macroOrMicro()).isEqualTo("protein");
    assertThat(v.date()).isEqualTo(MONDAY);
    assertThat(v.floor()).isEqualByComparingTo(new BigDecimal("100.0"));
    assertThat(v.actual()).isEqualByComparingTo(new BigDecimal("10.00"));
  }

  @Test
  void aggregateWeek_dailyFloorBreachedOnTwoTrackedDays_twoDatedEntries() {
    UUID userId = UUID.randomUUID();
    // Mon + Thu below the 100g/day protein floor; Tue meets it; remaining days untracked.
    IntakeDay mon = day(userId, MONDAY, confirmedSlot(MealSlot.BREAKFAST, 200, 10, 20, 5, 2));
    IntakeDay tue =
        day(userId, MONDAY.plusDays(1), confirmedSlot(MealSlot.LUNCH, 600, 120, 50, 20, 10));
    IntakeDay thu =
        day(userId, MONDAY.plusDays(3), confirmedSlot(MealSlot.DINNER, 300, 42, 30, 10, 5));
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of(mon, tue, thu));
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withProteinFloor(BigDecimal.valueOf(100.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    assertThat(out.floorViolations())
        .extracting(FloorViolationDto::macroOrMicro, FloorViolationDto::date)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("protein", MONDAY),
            org.assertj.core.groups.Tuple.tuple("protein", MONDAY.plusDays(3)));
  }

  @Test
  void aggregateWeek_weeklyAverageFloorBreached_singleUndatedEntryWithSummedFloor() {
    UUID userId = UUID.randomUUID();
    // carbs is weekly_average-enforced (builder); floor 100g/day -> 7-day-summed floor 700g.
    // Weekly actual = 20g < 700g -> a single date == null entry carrying the summed figures.
    IntakeDay only = day(userId, MONDAY, confirmedSlot(MealSlot.BREAKFAST, 200, 10, 20, 5, 2));
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of(only));
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withCarbsFloor(BigDecimal.valueOf(100.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    assertThat(out.floorViolations()).hasSize(1);
    FloorViolationDto v = out.floorViolations().get(0);
    assertThat(v.macroOrMicro()).isEqualTo("carbs");
    assertThat(v.date()).isNull();
    assertThat(v.floor()).isEqualByComparingTo(new BigDecimal("700.0"));
    assertThat(v.actual()).isEqualByComparingTo(new BigDecimal("20.00"));
  }

  @Test
  void aggregateWeek_microHardFloorBreached_datedEntryWithNutrientKey() {
    UUID userId = UUID.randomUUID();
    // iron_mg hard floor 18; the only tracked day rolled up 5mg -> dated entry keyed by nutrient.
    IntakeSlot slot = confirmedSlot(MealSlot.BREAKFAST, 500, 110, 60, 15, 8);
    slot.setActualMicros(micros("iron_mg", "5.0"));
    IntakeDay only = day(userId, MONDAY, slot);
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of(only));
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withMicroHardFloor("iron_mg", BigDecimal.valueOf(18.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    assertThat(out.floorViolations()).hasSize(1);
    FloorViolationDto v = out.floorViolations().get(0);
    assertThat(v.macroOrMicro()).isEqualTo("iron_mg");
    assertThat(v.date()).isEqualTo(MONDAY);
    assertThat(v.floor()).isEqualByComparingTo(new BigDecimal("18.0"));
    assertThat(v.actual()).isEqualByComparingTo(new BigDecimal("5.00"));
  }

  @Test
  void aggregateWeek_floorMet_noViolation() {
    UUID userId = UUID.randomUUID();
    // Seven days with 100g protein each = 700g weekly = floor*7.
    List<IntakeDay> days = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      days.add(
          day(userId, MONDAY.plusDays(i), confirmedSlot(MealSlot.LUNCH, 600, 100, 50, 20, 10)));
    }
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(days);
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(userId)
            .withProteinFloor(BigDecimal.valueOf(100.0))
            .build();
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.of(targets));

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    assertThat(out.floorViolations()).isEmpty();
  }

  @Test
  void aggregateWeek_snackCountsTowardActual_butNotPlanned() {
    UUID userId = UUID.randomUUID();
    IntakeDay d = day(userId, MONDAY);
    d.addSnack(snack(180, 7, 6, 15, 3));
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of(d));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    DailyAggregateDto wk = out.weeklyTotal();
    assertThat(wk.caloriesPlanned()).isZero();
    assertThat(wk.caloriesActualSoFar()).isEqualTo(180);
    assertThat(wk.protein().plannedG()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(wk.protein().actualSoFarG()).isEqualByComparingTo(new BigDecimal("7.00"));
  }

  @Test
  void aggregateWeek_deterministic_sameInputGivesSameOutput() {
    UUID userId = UUID.randomUUID();
    IntakeDay d = day(userId, MONDAY, confirmedSlot(MealSlot.BREAKFAST, 500, 30, 60, 15, 8));
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of(d));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    WeeklyAggregateDto a = aggregator().aggregateWeek(userId, MONDAY);
    WeeklyAggregateDto b = aggregator().aggregateWeek(userId, MONDAY);

    assertThat(a).isEqualTo(b);
  }

  @Test
  void aggregateDay_remainingIsTargetBased_andZeroFloored_whenTargetsExist() {
    // nutrition-6: remaining = max(0, dailyTarget - actualSoFar), not planned - actual.
    UUID userId = UUID.randomUUID();
    // Eat 1500 kcal / 140g protein. Targets: 2000 kcal / 120g protein.
    IntakeDay d = day(userId, DAY, confirmedSlot(MealSlot.BREAKFAST, 1500, 140, 60, 15, 8));
    when(intakeDayRepository.findByUserIdAndOnDate(userId, DAY)).thenReturn(Optional.of(d));
    when(targetsRepository.findByUserId(userId))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(userId).build()));

    DailyAggregateDto agg = aggregator().aggregateDay(userId, DAY);

    // calories: 2000 - 1500 = 500 (target-based, NOT planned 1500 - actual 1500 = 0).
    assertThat(agg.caloriesRemaining()).isEqualTo(500);
    // protein: actual 140 > target 120 -> floored at 0 (NOT negative).
    assertThat(agg.protein().remainingG()).isEqualByComparingTo(BigDecimal.ZERO);
    // carbs: target 250 - actual 60 = 190.
    assertThat(agg.carbs().remainingG()).isEqualByComparingTo(new BigDecimal("190.00"));
  }

  @Test
  void aggregateDay_noTargets_fallsBackToZeroFlooredPlannedRemaining() {
    // nutrition-6: with no targets row, remaining falls back to max(0, planned - actual).
    UUID userId = UUID.randomUUID();
    // planned 500 kcal / 30g protein; eat 700 kcal / 40g protein (over-eaten).
    IntakeDay d = day(userId, DAY, slotPlannedVsActual(MealSlot.BREAKFAST, 500, 30, 700, 40));
    when(intakeDayRepository.findByUserIdAndOnDate(userId, DAY)).thenReturn(Optional.of(d));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    DailyAggregateDto agg = aggregator().aggregateDay(userId, DAY);

    // planned 500 - actual 700 = -200 -> floored to 0.
    assertThat(agg.caloriesRemaining()).isZero();
    assertThat(agg.protein().remainingG()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void aggregateWeek_perDayAndTotalRemaining_areTargetBased_andZeroFloored() {
    // nutrition-6: per-day remaining uses the daily target; weekly total uses 7×-target.
    UUID userId = UUID.randomUUID();
    IntakeDay d1 = day(userId, MONDAY, confirmedSlot(MealSlot.BREAKFAST, 1500, 100, 50, 20, 10));
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of(d1));
    when(targetsRepository.findByUserId(userId))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(userId).build()));

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    // Monday: calories remaining = 2000 - 1500 = 500.
    assertThat(out.perDay().get(0).caloriesRemaining()).isEqualTo(500);
    // Empty days: remaining = full daily target (2000), not zero.
    assertThat(out.perDay().get(1).caloriesRemaining()).isEqualTo(2000);
    // Weekly total: 7×2000 - 1500 = 12500.
    assertThat(out.weeklyTotal().caloriesRemaining()).isEqualTo(7 * 2000 - 1500);
    // Weekly protein: 7×120 - 100 = 740, never negative.
    assertThat(out.weeklyTotal().protein().remainingG())
        .isEqualByComparingTo(new BigDecimal("740.00"));
  }

  // ---------------- satFat aggregate (nutrition-daily-aggregate-satfat) ----------------

  @Test
  void aggregateDay_satFat_summedFromSlotAndSnackMicros_remainingTargetBased() {
    UUID userId = UUID.randomUUID();
    // Slot 1 carries saturated-fat data in its planned/actual micros documents; slot 2 has none
    // and must contribute 0 (no null-poisoning). Snack adds to actuals only.
    IntakeSlot withSatFat = confirmedSlot(MealSlot.BREAKFAST, 500, 30, 60, 15, 8);
    withSatFat.setPlannedMicros(micros("saturated_fat_g", "6.0"));
    withSatFat.setActualMicros(micros("saturated_fat_g", "5.5"));
    IntakeSlot withoutSatFat = confirmedSlot(MealSlot.LUNCH, 600, 40, 70, 20, 10);
    IntakeDay d = day(userId, DAY, withSatFat, withoutSatFat);
    IntakeSnack s = snack(180, 7, 6, 15, 3);
    s.setMicros(micros("saturated_fat_g", "2.0"));
    d.addSnack(s);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, DAY)).thenReturn(Optional.of(d));
    // Builder targets carry satFatTargetG = 20.0.
    when(targetsRepository.findByUserId(userId))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(userId).build()));

    DailyAggregateDto agg = aggregator().aggregateDay(userId, DAY);

    assertThat(agg.satFat().plannedG()).isEqualByComparingTo(new BigDecimal("6.00"));
    assertThat(agg.satFat().actualSoFarG()).isEqualByComparingTo(new BigDecimal("7.50"));
    // remaining = max(0, satFat target 20 - actual 7.5) — mirrors the other four macros.
    assertThat(agg.satFat().remainingG()).isEqualByComparingTo(new BigDecimal("12.50"));
    // The micros-map convention entry is retained for one release (frontend cutover).
    assertThat(agg.microsActualSoFar().get("saturated_fat_g"))
        .isEqualByComparingTo(new BigDecimal("7.50"));
  }

  @Test
  void aggregateDay_satFat_noTargets_fallsBackToZeroFlooredPlannedRemaining() {
    UUID userId = UUID.randomUUID();
    IntakeSlot slot = confirmedSlot(MealSlot.BREAKFAST, 500, 30, 60, 15, 8);
    slot.setPlannedMicros(micros("saturated_fat_g", "4.0"));
    slot.setActualMicros(micros("saturated_fat_g", "9.0"));
    IntakeDay d = day(userId, DAY, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(userId, DAY)).thenReturn(Optional.of(d));
    when(targetsRepository.findByUserId(userId)).thenReturn(Optional.empty());

    DailyAggregateDto agg = aggregator().aggregateDay(userId, DAY);

    // planned 4 - actual 9 = -5 -> floored to 0 (same fallback as the other macros).
    assertThat(agg.satFat().plannedG()).isEqualByComparingTo(new BigDecimal("4.00"));
    assertThat(agg.satFat().actualSoFarG()).isEqualByComparingTo(new BigDecimal("9.00"));
    assertThat(agg.satFat().remainingG()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void aggregateWeek_satFat_flowsThroughPerDayAndWeeklyTotal() {
    UUID userId = UUID.randomUUID();
    IntakeSlot slot = confirmedSlot(MealSlot.BREAKFAST, 500, 30, 60, 15, 8);
    slot.setActualMicros(micros("saturated_fat_g", "5.0"));
    IntakeDay d = day(userId, MONDAY, slot);
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(userId), any(), any()))
        .thenReturn(List.of(d));
    when(targetsRepository.findByUserId(userId))
        .thenReturn(Optional.of(NutritionTestData.targets().withUserId(userId).build()));

    WeeklyAggregateDto out = aggregator().aggregateWeek(userId, MONDAY);

    assertThat(out.perDay().get(0).satFat().actualSoFarG())
        .isEqualByComparingTo(new BigDecimal("5.00"));
    // Empty days emit a zero-valued satFat aggregate (required field, never absent).
    assertThat(out.perDay().get(1).satFat().actualSoFarG()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(out.weeklyTotal().satFat().actualSoFarG())
        .isEqualByComparingTo(new BigDecimal("5.00"));
    // Weekly remaining = 7×20 - 5 = 135, zero-floored target basis like the other macros.
    assertThat(out.weeklyTotal().satFat().remainingG())
        .isEqualByComparingTo(new BigDecimal("135.00"));
  }

  // ---------------- fixtures ----------------

  private static final ObjectMapper OM = new ObjectMapper();

  private static ObjectNode micros(String key, String value) {
    ObjectNode n = OM.createObjectNode();
    n.put(key, new BigDecimal(value));
    return n;
  }

  private static IntakeDay day(UUID userId, LocalDate onDate, IntakeSlot... slots) {
    IntakeDay day =
        IntakeDay.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .onDate(onDate)
            .slots(new ArrayList<>())
            .snacks(new ArrayList<>())
            .auditLog(new ArrayList<>())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    for (IntakeSlot s : slots) {
      day.addSlot(s);
    }
    return day;
  }

  private static IntakeSlot confirmedSlot(
      MealSlot mealSlot, int kcal, int protein, int carbs, int fat, int fibre) {
    return IntakeSlot.builder()
        .id(UUID.randomUUID())
        .mealSlot(mealSlot)
        .plannedCalories(kcal)
        .plannedProteinG(BigDecimal.valueOf(protein))
        .plannedCarbsG(BigDecimal.valueOf(carbs))
        .plannedFatG(BigDecimal.valueOf(fat))
        .plannedFibreG(BigDecimal.valueOf(fibre))
        .actualStatus(IntakeSlotStatus.CONFIRMED)
        .actualCalories(kcal)
        .actualProteinG(BigDecimal.valueOf(protein))
        .actualCarbsG(BigDecimal.valueOf(carbs))
        .actualFatG(BigDecimal.valueOf(fat))
        .actualFibreG(BigDecimal.valueOf(fibre))
        .build();
  }

  private static IntakeSlot slotPlannedVsActual(
      MealSlot mealSlot, int plannedKcal, int plannedProtein, int actualKcal, int actualProtein) {
    return IntakeSlot.builder()
        .id(UUID.randomUUID())
        .mealSlot(mealSlot)
        .plannedCalories(plannedKcal)
        .plannedProteinG(BigDecimal.valueOf(plannedProtein))
        .actualStatus(IntakeSlotStatus.EDITED)
        .actualCalories(actualKcal)
        .actualProteinG(BigDecimal.valueOf(actualProtein))
        .build();
  }

  private static IntakeSnack snack(int kcal, int protein, int carbs, int fat, int fibre) {
    return IntakeSnack.builder()
        .id(UUID.randomUUID())
        .freeText("almonds")
        .quantityG(BigDecimal.valueOf(30))
        .calories(kcal)
        .proteinG(BigDecimal.valueOf(protein))
        .carbsG(BigDecimal.valueOf(carbs))
        .fatG(BigDecimal.valueOf(fat))
        .fibreG(BigDecimal.valueOf(fibre))
        .source(IntakeSource.MANUAL)
        .loggedAt(Instant.now())
        .build();
  }
}
