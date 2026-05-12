package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.DailyAggregateDto;
import com.example.mealprep.nutrition.api.dto.MacroAggregateDto;
import com.example.mealprep.nutrition.api.dto.WeeklyAggregateDto;
import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
import com.example.mealprep.nutrition.domain.entity.MicroTarget;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.repository.IntakeDayRepository;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pure-compute helper that turns one {@link IntakeDay} aggregate (or a 7-day window of them) into a
 * {@link DailyAggregateDto} / {@link WeeklyAggregateDto}. Package-private; cross-module callers go
 * through {@code NutritionQueryService}.
 *
 * <p>Behaviour notes:
 *
 * <ul>
 *   <li>{@code aggregateDay} sums slot.actual + snack across the existing {@link IntakeDay}'s
 *       slots/snacks. Slot status doesn't gate inclusion — the slot's {@code actual_*} columns are
 *       zero unless the slot has been decided, so {@code PENDING} contributes zero by construction.
 *   <li>Macro {@code remainingG} = plan - actual; can be negative.
 *   <li>Macro {@code plannedG} sums {@code IntakeSlot.plannedXxxG} across all slots; snacks have no
 *       planned counterpart.
 *   <li>{@code microsActualSoFar} merges the slot.actualMicros JSONB objects + snack.micros JSONB
 *       objects, summing numeric values per key.
 *   <li>{@code aggregateWeek} is Monday-anchored; missing days contribute a zero-valued daily
 *       aggregate. {@code floorViolations} uses 7-day-summed floors (macro floor × 7).
 * </ul>
 */
@Component
public class IntakeAggregator {

  private static final int MACRO_SCALE = 2;
  private static final RoundingMode MACRO_ROUNDING = RoundingMode.HALF_UP;

  private final IntakeDayRepository intakeDayRepository;
  private final NutritionTargetsRepository nutritionTargetsRepository;

  public IntakeAggregator(
      IntakeDayRepository intakeDayRepository,
      NutritionTargetsRepository nutritionTargetsRepository) {
    this.intakeDayRepository = intakeDayRepository;
    this.nutritionTargetsRepository = nutritionTargetsRepository;
  }

  /**
   * Aggregate a single day. Loads the {@link IntakeDay} (and forces lazy-load of slots+snacks)
   * inside the caller's transaction. Returns a zero-valued aggregate if no day row exists.
   */
  public DailyAggregateDto aggregateDay(UUID userId, LocalDate onDate) {
    return intakeDayRepository
        .findByUserIdAndOnDate(userId, onDate)
        .map(IntakeAggregator::aggregateDayEntity)
        .orElseGet(IntakeAggregator::emptyAggregate);
  }

  /** Aggregate one {@link IntakeDay} entity. Public for {@link DivergenceDetector} reuse. */
  static DailyAggregateDto aggregateDayEntity(IntakeDay day) {
    // Force lazy load.
    day.getSlots().size();
    day.getSnacks().size();

    int caloriesPlanned = 0;
    int caloriesActual = 0;
    BigDecimal proteinPlanned = BigDecimal.ZERO;
    BigDecimal proteinActual = BigDecimal.ZERO;
    BigDecimal carbsPlanned = BigDecimal.ZERO;
    BigDecimal carbsActual = BigDecimal.ZERO;
    BigDecimal fatPlanned = BigDecimal.ZERO;
    BigDecimal fatActual = BigDecimal.ZERO;
    BigDecimal fibrePlanned = BigDecimal.ZERO;
    BigDecimal fibreActual = BigDecimal.ZERO;
    Map<String, BigDecimal> micros = new LinkedHashMap<>();

    for (IntakeSlot s : day.getSlots()) {
      caloriesPlanned += nz(s.getPlannedCalories());
      caloriesActual += nz(s.getActualCalories());
      proteinPlanned = proteinPlanned.add(nz(s.getPlannedProteinG()));
      proteinActual = proteinActual.add(nz(s.getActualProteinG()));
      carbsPlanned = carbsPlanned.add(nz(s.getPlannedCarbsG()));
      carbsActual = carbsActual.add(nz(s.getActualCarbsG()));
      fatPlanned = fatPlanned.add(nz(s.getPlannedFatG()));
      fatActual = fatActual.add(nz(s.getActualFatG()));
      fibrePlanned = fibrePlanned.add(nz(s.getPlannedFibreG()));
      fibreActual = fibreActual.add(nz(s.getActualFibreG()));
      mergeMicros(micros, s.getActualMicros());
    }

    for (IntakeSnack snack : day.getSnacks()) {
      // Snacks contribute to actuals only — no planned counterpart.
      caloriesActual += snack.getCalories();
      proteinActual = proteinActual.add(nz(snack.getProteinG()));
      carbsActual = carbsActual.add(nz(snack.getCarbsG()));
      fatActual = fatActual.add(nz(snack.getFatG()));
      fibreActual = fibreActual.add(nz(snack.getFibreG()));
      mergeMicros(micros, snack.getMicros());
    }

    return new DailyAggregateDto(
        caloriesPlanned,
        caloriesActual,
        caloriesPlanned - caloriesActual,
        macroAgg(proteinPlanned, proteinActual),
        macroAgg(carbsPlanned, carbsActual),
        macroAgg(fatPlanned, fatActual),
        macroAgg(fibrePlanned, fibreActual),
        scaleMicros(micros));
  }

  /** Weekly rollup, Monday-anchored. Caller validates {@code weekStart} is a Monday. */
  public WeeklyAggregateDto aggregateWeek(UUID userId, LocalDate weekStart) {
    LocalDate weekEnd = weekStart.plusDays(6);
    Map<LocalDate, IntakeDay> byDate = new LinkedHashMap<>();
    for (IntakeDay d :
        intakeDayRepository.findByUserIdAndOnDateBetween(userId, weekStart, weekEnd)) {
      byDate.put(d.getOnDate(), d);
    }

    List<DailyAggregateDto> perDay = new ArrayList<>(7);
    for (int i = 0; i < 7; i++) {
      LocalDate d = weekStart.plusDays(i);
      IntakeDay day = byDate.get(d);
      perDay.add(day != null ? aggregateDayEntity(day) : emptyAggregate());
    }
    DailyAggregateDto weeklyTotal = sumDailies(perDay);

    Optional<NutritionTargets> targets = nutritionTargetsRepository.findByUserId(userId);
    List<String> floorViolations =
        targets.map(t -> computeWeeklyFloorViolations(t, weeklyTotal)).orElseGet(List::of);

    return new WeeklyAggregateDto(weekStart, weekEnd, perDay, weeklyTotal, floorViolations);
  }

  // ---------------- helpers ----------------

  private static DailyAggregateDto emptyAggregate() {
    return new DailyAggregateDto(
        0,
        0,
        0,
        macroAgg(BigDecimal.ZERO, BigDecimal.ZERO),
        macroAgg(BigDecimal.ZERO, BigDecimal.ZERO),
        macroAgg(BigDecimal.ZERO, BigDecimal.ZERO),
        macroAgg(BigDecimal.ZERO, BigDecimal.ZERO),
        new LinkedHashMap<>());
  }

  private static MacroAggregateDto macroAgg(BigDecimal planned, BigDecimal actual) {
    BigDecimal p = scale(planned);
    BigDecimal a = scale(actual);
    return new MacroAggregateDto(p, a, p.subtract(a));
  }

  private static BigDecimal scale(BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(MACRO_SCALE, MACRO_ROUNDING);
  }

  private static int nz(Integer v) {
    return v == null ? 0 : v;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static void mergeMicros(Map<String, BigDecimal> acc, JsonNode micros) {
    if (micros == null || !micros.isObject()) {
      return;
    }
    micros
        .fields()
        .forEachRemaining(
            entry -> {
              JsonNode v = entry.getValue();
              if (v == null || !v.isNumber()) {
                return;
              }
              BigDecimal asBd = v.decimalValue();
              acc.merge(entry.getKey(), asBd, BigDecimal::add);
            });
  }

  private static Map<String, BigDecimal> scaleMicros(Map<String, BigDecimal> in) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> e : in.entrySet()) {
      out.put(e.getKey(), e.getValue().setScale(MACRO_SCALE, MACRO_ROUNDING));
    }
    return out;
  }

  private static DailyAggregateDto sumDailies(List<DailyAggregateDto> days) {
    int caloriesPlanned = 0;
    int caloriesActual = 0;
    BigDecimal proteinPlanned = BigDecimal.ZERO;
    BigDecimal proteinActual = BigDecimal.ZERO;
    BigDecimal carbsPlanned = BigDecimal.ZERO;
    BigDecimal carbsActual = BigDecimal.ZERO;
    BigDecimal fatPlanned = BigDecimal.ZERO;
    BigDecimal fatActual = BigDecimal.ZERO;
    BigDecimal fibrePlanned = BigDecimal.ZERO;
    BigDecimal fibreActual = BigDecimal.ZERO;
    Map<String, BigDecimal> micros = new LinkedHashMap<>();

    for (DailyAggregateDto d : days) {
      caloriesPlanned += d.caloriesPlanned();
      caloriesActual += d.caloriesActualSoFar();
      proteinPlanned = proteinPlanned.add(d.protein().plannedG());
      proteinActual = proteinActual.add(d.protein().actualSoFarG());
      carbsPlanned = carbsPlanned.add(d.carbs().plannedG());
      carbsActual = carbsActual.add(d.carbs().actualSoFarG());
      fatPlanned = fatPlanned.add(d.fat().plannedG());
      fatActual = fatActual.add(d.fat().actualSoFarG());
      fibrePlanned = fibrePlanned.add(d.fibre().plannedG());
      fibreActual = fibreActual.add(d.fibre().actualSoFarG());
      for (Map.Entry<String, BigDecimal> e : d.microsActualSoFar().entrySet()) {
        micros.merge(e.getKey(), e.getValue(), BigDecimal::add);
      }
    }

    return new DailyAggregateDto(
        caloriesPlanned,
        caloriesActual,
        caloriesPlanned - caloriesActual,
        macroAgg(proteinPlanned, proteinActual),
        macroAgg(carbsPlanned, carbsActual),
        macroAgg(fatPlanned, fatActual),
        macroAgg(fibrePlanned, fibreActual),
        scaleMicros(micros));
  }

  /**
   * Compute weekly floor violations: for each hard-floor macro, compare weekly actual against
   * {@code floorG × 7}. Micros are not hard-floored in the current schema (no {@code is_hard_floor}
   * column on {@link MicroTarget}).
   */
  private static List<String> computeWeeklyFloorViolations(
      NutritionTargets t, DailyAggregateDto weeklyTotal) {
    List<String> violations = new ArrayList<>();
    addIfViolated(
        violations, "protein", t.getProteinFloorG(), weeklyTotal.protein().actualSoFarG());
    addIfViolated(violations, "carbs", t.getCarbsFloorG(), weeklyTotal.carbs().actualSoFarG());
    addIfViolated(violations, "fat", t.getFatFloorG(), weeklyTotal.fat().actualSoFarG());
    addIfViolated(violations, "fibre", t.getFibreFloorG(), weeklyTotal.fibre().actualSoFarG());
    return violations;
  }

  private static void addIfViolated(
      List<String> out, String key, BigDecimal dailyFloor, BigDecimal weeklyActual) {
    if (dailyFloor == null) {
      return;
    }
    BigDecimal weeklyFloor = dailyFloor.multiply(BigDecimal.valueOf(7));
    if (weeklyActual.compareTo(weeklyFloor) < 0) {
      out.add(key);
    }
  }
}
