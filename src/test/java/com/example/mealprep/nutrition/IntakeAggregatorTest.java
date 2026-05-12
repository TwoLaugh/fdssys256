package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.DailyAggregateDto;
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
  void aggregateWeek_hardFloorBreached_listsKey() {
    UUID userId = UUID.randomUUID();
    // One day with 10g protein actual; floor is 100g per day = 700g/week. Weekly actual = 10g <
    // 700g.
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

    assertThat(out.floorViolations()).containsExactly("protein");
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

  // ---------------- fixtures ----------------

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
