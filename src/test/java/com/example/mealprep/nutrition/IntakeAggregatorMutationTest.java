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

/**
 * Mutation-killing unit coverage for {@link IntakeAggregator}. Targets surviving / uncovered
 * mutants the existing {@code IntakeAggregatorTest} (which only exercised {@code aggregateWeek}
 * with planned==actual slots) left alive:
 *
 * <ul>
 *   <li>{@code aggregateDay} direct path (L66 NullReturnVals — {@code Optional.map} result).
 *   <li>{@code aggregateDayEntity} L91/L114 MathMutator — {@code caloriesPlanned += ...} and the
 *       {@code caloriesPlanned - caloriesActual} remaining; the old test used planned==actual so
 *       {@code +} vs {@code -} and {@code -} vs {@code +} were indistinguishable.
 *   <li>{@code mergeMicros} L101/L111/L182/L187/L190 — micros were never populated in fixtures.
 *   <li>{@code scaleMicros} L203 EmptyObjectReturnVals — returned-map emptiness was never asserted.
 *   <li>{@code sumDailies} L220/L235 MathMutator — weekly totals with distinct planned/actual.
 *   <li>{@code addIfViolated} L256/L257/L258 VoidMethodCall — carbs/fat/fibre floors (only protein
 *       was exercised before, so removing the carbs/fat/fibre checks survived).
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IntakeAggregatorMutationTest {

  @Mock private IntakeDayRepository intakeDayRepository;
  @Mock private NutritionTargetsRepository targetsRepository;

  private static final ObjectMapper OM = new ObjectMapper();
  private static final UUID USER = UUID.randomUUID();
  private static final LocalDate MONDAY = LocalDate.of(2026, 5, 11); // a Monday
  private static final LocalDate DAY = LocalDate.of(2026, 5, 12);

  private IntakeAggregator aggregator() {
    return new IntakeAggregator(intakeDayRepository, targetsRepository);
  }

  // ---------------- aggregateDay direct path ----------------

  @Test
  void aggregateDay_existingRow_returnsPopulatedAggregate_notZeroFill() {
    // Kills L66 NullReturnVals (Optional.map(...) -> the mapped DTO must be returned, not null)
    // and the L91 MathMutator (caloriesPlanned += vs -=) and L114 (planned - actual remaining).
    IntakeSlot slot = slotPlannedVsActual(MealSlot.BREAKFAST, 500, 30, 400, 25);
    IntakeDay d = day(DAY, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, DAY)).thenReturn(Optional.of(d));

    DailyAggregateDto agg = aggregator().aggregateDay(USER, DAY);

    assertThat(agg).isNotNull();
    // planned=500, actual=400 — distinct so += vs -= is observable.
    assertThat(agg.caloriesPlanned()).isEqualTo(500);
    assertThat(agg.caloriesActualSoFar()).isEqualTo(400);
    // remaining = planned - actual = 100 (kills L114: caloriesPlanned - caloriesActual -> +).
    assertThat(agg.caloriesRemaining()).isEqualTo(100);
    assertThat(agg.protein().plannedG()).isEqualByComparingTo(new BigDecimal("30.00"));
    assertThat(agg.protein().actualSoFarG()).isEqualByComparingTo(new BigDecimal("25.00"));
    // protein remaining = 30 - 25 = 5
    assertThat(agg.protein().remainingG()).isEqualByComparingTo(new BigDecimal("5.00"));
  }

  @Test
  void aggregateDay_noRow_returnsZeroAggregate() {
    when(intakeDayRepository.findByUserIdAndOnDate(USER, DAY)).thenReturn(Optional.empty());

    DailyAggregateDto agg = aggregator().aggregateDay(USER, DAY);

    assertThat(agg).isNotNull();
    assertThat(agg.caloriesPlanned()).isZero();
    assertThat(agg.caloriesActualSoFar()).isZero();
    assertThat(agg.microsActualSoFar()).isEmpty();
  }

  // ---------------- micros merge (slot + snack) ----------------

  @Test
  void aggregateDay_mergesSlotAndSnackMicros_andScalesThem() {
    // Kills L101 (mergeMicros(micros, s.getActualMicros()) VoidMethodCall),
    // L111 (mergeMicros(micros, snack.getMicros()) VoidMethodCall),
    // L182/L187/L190 (mergeMicros body NO_COVERAGE), and L203 scaleMicros EmptyObjectReturn.
    IntakeSlot slot = slotPlannedVsActual(MealSlot.LUNCH, 600, 40, 600, 40);
    slot.setActualMicros(micros("iron_mg", "5.0", "vitamin_c_mg", "12.0"));
    IntakeDay d = day(DAY, slot);
    IntakeSnack snack = snack(100, 5);
    snack.setMicros(micros("iron_mg", "2.5"));
    d.addSnack(snack);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, DAY)).thenReturn(Optional.of(d));

    DailyAggregateDto agg = aggregator().aggregateDay(USER, DAY);

    // iron_mg = 5.0 (slot) + 2.5 (snack) = 7.50, scaled to 2 dp (kills scaleMicros empty-return).
    assertThat(agg.microsActualSoFar()).containsKey("iron_mg");
    assertThat(agg.microsActualSoFar().get("iron_mg")).isEqualByComparingTo(new BigDecimal("7.50"));
    assertThat(agg.microsActualSoFar().get("vitamin_c_mg"))
        .isEqualByComparingTo(new BigDecimal("12.00"));
  }

  @Test
  void aggregateDay_nonObjectMicros_areIgnored_noException() {
    // Kills L182 NegateConditionals (micros == null || !micros.isObject()) — a non-object node
    // must be skipped, leaving micros empty rather than throwing / merging garbage.
    IntakeSlot slot = slotPlannedVsActual(MealSlot.DINNER, 700, 50, 700, 50);
    slot.setActualMicros(OM.getNodeFactory().textNode("not-an-object"));
    IntakeDay d = day(DAY, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, DAY)).thenReturn(Optional.of(d));

    DailyAggregateDto agg = aggregator().aggregateDay(USER, DAY);

    assertThat(agg.microsActualSoFar()).isEmpty();
  }

  @Test
  void aggregateDay_nonNumericMicroValue_isSkipped() {
    // Kills L190 NegateConditionals in the field lambda (v == null || !v.isNumber()).
    ObjectNode m = OM.createObjectNode();
    m.put("iron_mg", "abc"); // non-numeric -> must be skipped
    m.put("zinc_mg", 3.0); // numeric -> must be kept
    IntakeSlot slot = slotPlannedVsActual(MealSlot.BREAKFAST, 500, 30, 500, 30);
    slot.setActualMicros(m);
    IntakeDay d = day(DAY, slot);
    when(intakeDayRepository.findByUserIdAndOnDate(USER, DAY)).thenReturn(Optional.of(d));

    DailyAggregateDto agg = aggregator().aggregateDay(USER, DAY);

    assertThat(agg.microsActualSoFar()).doesNotContainKey("iron_mg");
    assertThat(agg.microsActualSoFar().get("zinc_mg")).isEqualByComparingTo(new BigDecimal("3.00"));
  }

  // ---------------- weekly sum math ----------------

  @Test
  void aggregateWeek_sumDailies_distinctPlannedAndActual_areSummedNotDiffed() {
    // Kills L220 (caloriesPlanned += d.caloriesPlanned()) and L235
    // (caloriesPlanned - caloriesActual in the weekly total) — old test had planned==actual.
    IntakeDay d1 = day(MONDAY, slotPlannedVsActual(MealSlot.BREAKFAST, 500, 30, 300, 18));
    IntakeDay d2 = day(MONDAY.plusDays(1), slotPlannedVsActual(MealSlot.LUNCH, 600, 40, 450, 30));
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(USER), any(), any()))
        .thenReturn(List.of(d1, d2));
    when(targetsRepository.findByUserId(USER)).thenReturn(Optional.empty());

    WeeklyAggregateDto wk = aggregator().aggregateWeek(USER, MONDAY);

    // planned 500+600=1100, actual 300+450=750, remaining 1100-750=350.
    assertThat(wk.weeklyTotal().caloriesPlanned()).isEqualTo(1100);
    assertThat(wk.weeklyTotal().caloriesActualSoFar()).isEqualTo(750);
    assertThat(wk.weeklyTotal().caloriesRemaining()).isEqualTo(350);
    assertThat(wk.weeklyTotal().protein().plannedG()).isEqualByComparingTo(new BigDecimal("70.00"));
    assertThat(wk.weeklyTotal().protein().actualSoFarG())
        .isEqualByComparingTo(new BigDecimal("48.00"));
  }

  // ---------------- weekly floor violations: carbs / fat / fibre ----------------

  @Test
  void aggregateWeek_carbsFatFibreFloorsBreached_eachListed() {
    // Kills L256/L257/L258 VoidMethodCall — removing the carbs/fat/fibre addIfViolated calls
    // previously survived because only the protein floor was ever set in the old test.
    IntakeDay only =
        day(MONDAY, confirmedSlot(MealSlot.BREAKFAST, 200, /*p*/ 5, /*c*/ 5, /*f*/ 2, /*fib*/ 1));
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(USER), any(), any()))
        .thenReturn(List.of(only));
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(USER)
            .withCarbsFloor(BigDecimal.valueOf(100.0)) // weekly floor 700g, actual 5g -> violated
            .withFatFloor(BigDecimal.valueOf(50.0)) // weekly floor 350g, actual 2g -> violated
            .withFibreFloor(BigDecimal.valueOf(25.0)) // weekly floor 175g, actual 1g -> violated
            .build();
    when(targetsRepository.findByUserId(USER)).thenReturn(Optional.of(targets));

    WeeklyAggregateDto wk = aggregator().aggregateWeek(USER, MONDAY);

    assertThat(wk.floorViolations()).containsExactlyInAnyOrder("carbs", "fat", "fibre");
  }

  @Test
  void aggregateWeek_carbsFloorMet_butFatBreached_onlyFatListed() {
    // Distinguishes the three independent addIfViolated calls so swapping/removing any one is
    // detectable (kills the carbs vs fat vs fibre VoidMethodCall mutants individually).
    List<IntakeDay> days = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      // 100g carbs/day -> 700g/week meets a 100g/day floor; 1g fat/day -> 7g < 350g/week.
      days.add(day(MONDAY.plusDays(i), confirmedSlot(MealSlot.LUNCH, 600, 30, 100, 1, 30)));
    }
    when(intakeDayRepository.findByUserIdAndOnDateBetween(eq(USER), any(), any())).thenReturn(days);
    NutritionTargets targets =
        NutritionTestData.targets()
            .withUserId(USER)
            .withCarbsFloor(BigDecimal.valueOf(100.0)) // met
            .withFatFloor(BigDecimal.valueOf(50.0)) // breached
            .build();
    when(targetsRepository.findByUserId(USER)).thenReturn(Optional.of(targets));

    WeeklyAggregateDto wk = aggregator().aggregateWeek(USER, MONDAY);

    assertThat(wk.floorViolations()).containsExactly("fat");
  }

  // ---------------- fixtures ----------------

  private static IntakeDay day(LocalDate onDate, IntakeSlot... slots) {
    IntakeDay d =
        IntakeDay.builder()
            .id(UUID.randomUUID())
            .userId(USER)
            .onDate(onDate)
            .slots(new ArrayList<>())
            .snacks(new ArrayList<>())
            .auditLog(new ArrayList<>())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    for (IntakeSlot s : slots) {
      d.addSlot(s);
    }
    return d;
  }

  private static IntakeSlot slotPlannedVsActual(
      MealSlot meal, int plannedKcal, int plannedProtein, int actualKcal, int actualProtein) {
    return IntakeSlot.builder()
        .id(UUID.randomUUID())
        .mealSlot(meal)
        .plannedCalories(plannedKcal)
        .plannedProteinG(BigDecimal.valueOf(plannedProtein))
        .plannedCarbsG(BigDecimal.valueOf(50))
        .plannedFatG(BigDecimal.valueOf(20))
        .plannedFibreG(BigDecimal.valueOf(8))
        .actualStatus(IntakeSlotStatus.CONFIRMED)
        .actualCalories(actualKcal)
        .actualProteinG(BigDecimal.valueOf(actualProtein))
        .actualCarbsG(BigDecimal.valueOf(40))
        .actualFatG(BigDecimal.valueOf(15))
        .actualFibreG(BigDecimal.valueOf(6))
        .build();
  }

  private static IntakeSlot confirmedSlot(
      MealSlot meal, int kcal, int protein, int carbs, int fat, int fibre) {
    return IntakeSlot.builder()
        .id(UUID.randomUUID())
        .mealSlot(meal)
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

  private static IntakeSnack snack(int kcal, int protein) {
    return IntakeSnack.builder()
        .id(UUID.randomUUID())
        .freeText("almonds")
        .quantityG(BigDecimal.valueOf(30))
        .calories(kcal)
        .proteinG(BigDecimal.valueOf(protein))
        .carbsG(BigDecimal.valueOf(6))
        .fatG(BigDecimal.valueOf(15))
        .fibreG(BigDecimal.valueOf(3))
        .source(IntakeSource.MANUAL)
        .loggedAt(Instant.now())
        .build();
  }

  private static com.fasterxml.jackson.databind.JsonNode micros(String... kv) {
    ObjectNode n = OM.createObjectNode();
    for (int i = 0; i < kv.length; i += 2) {
      n.put(kv[i], new BigDecimal(kv[i + 1]));
    }
    return n;
  }
}
