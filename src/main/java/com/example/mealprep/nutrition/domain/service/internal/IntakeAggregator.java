package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.DailyAggregateDto;
import com.example.mealprep.nutrition.api.dto.MacroAggregateDto;
import com.example.mealprep.nutrition.api.dto.WeeklyAggregateDto;
import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.IntakeSnack;
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
 *   <li>Macro {@code remainingG} is computed against the user's {@link NutritionTargets} daily
 *       target and floored at zero: {@code remaining = max(0, target - actualSoFar)} (nutrition-6 /
 *       LLD Flow 9 line 1026). When the user has no targets row, there is no target to remain
 *       against, so it falls back to {@code max(0, planned - actualSoFar)} (how much of the plan is
 *       left) rather than fabricating a target.
 *   <li>Macro {@code plannedG} sums {@code IntakeSlot.plannedXxxG} across all slots; snacks have no
 *       planned counterpart.
 *   <li>{@code microsActualSoFar} merges the slot.actualMicros JSONB objects + snack.micros JSONB
 *       objects, summing numeric values per key.
 *   <li>{@code aggregateWeek} is Monday-anchored; missing days contribute a zero-valued daily
 *       aggregate. Per-day {@code remaining} uses the daily target; the weekly total's remaining is
 *       the zero-floored 7×-target less the weekly actual. {@code floorViolations} uses
 *       7-day-summed floors (macro floor × 7).
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
   *
   * <p>Loads the user's {@link NutritionTargets} so {@code remaining} is computed against the daily
   * target and floored at zero (nutrition-6).
   */
  public DailyAggregateDto aggregateDay(UUID userId, LocalDate onDate) {
    DailyTargets targets = dailyTargets(userId);
    return intakeDayRepository
        .findByUserIdAndOnDate(userId, onDate)
        .map(day -> aggregateDayEntity(day, targets))
        .orElseGet(() -> emptyAggregate(targets));
  }

  /** Aggregate one {@link IntakeDay} entity against the user's {@code targets}. */
  static DailyAggregateDto aggregateDayEntity(IntakeDay day, DailyTargets targets) {
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
        caloriesRemaining(targets, caloriesPlanned, caloriesActual),
        macroAgg(targets == null ? null : targets.protein(), proteinPlanned, proteinActual),
        macroAgg(targets == null ? null : targets.carbs(), carbsPlanned, carbsActual),
        macroAgg(targets == null ? null : targets.fat(), fatPlanned, fatActual),
        macroAgg(targets == null ? null : targets.fibre(), fibrePlanned, fibreActual),
        scaleMicros(micros));
  }

  /** Weekly rollup, Monday-anchored. Caller validates {@code weekStart} is a Monday. */
  public WeeklyAggregateDto aggregateWeek(UUID userId, LocalDate weekStart) {
    LocalDate weekEnd = weekStart.plusDays(6);
    Optional<NutritionTargets> targetsOpt = nutritionTargetsRepository.findByUserId(userId);
    DailyTargets targets = targetsOpt.map(DailyTargets::of).orElse(null);

    Map<LocalDate, IntakeDay> byDate = new LinkedHashMap<>();
    for (IntakeDay d :
        intakeDayRepository.findByUserIdAndOnDateBetween(userId, weekStart, weekEnd)) {
      byDate.put(d.getOnDate(), d);
    }

    List<DailyAggregateDto> perDay = new ArrayList<>(7);
    for (int i = 0; i < 7; i++) {
      LocalDate d = weekStart.plusDays(i);
      IntakeDay day = byDate.get(d);
      perDay.add(day != null ? aggregateDayEntity(day, targets) : emptyAggregate(targets));
    }
    // Weekly total: remaining is the zero-floored 7×-daily-target less the weekly actual.
    DailyAggregateDto weeklyTotal = sumDailies(perDay, targets);

    List<String> floorViolations =
        targetsOpt.map(t -> computeWeeklyFloorViolations(t, weeklyTotal)).orElseGet(List::of);

    return new WeeklyAggregateDto(weekStart, weekEnd, perDay, weeklyTotal, floorViolations);
  }

  // ---------------- helpers ----------------

  private DailyTargets dailyTargets(UUID userId) {
    return nutritionTargetsRepository.findByUserId(userId).map(DailyTargets::of).orElse(null);
  }

  private static DailyAggregateDto emptyAggregate(DailyTargets targets) {
    return new DailyAggregateDto(
        0,
        0,
        caloriesRemaining(targets, 0, 0),
        macroAgg(targets == null ? null : targets.protein(), BigDecimal.ZERO, BigDecimal.ZERO),
        macroAgg(targets == null ? null : targets.carbs(), BigDecimal.ZERO, BigDecimal.ZERO),
        macroAgg(targets == null ? null : targets.fat(), BigDecimal.ZERO, BigDecimal.ZERO),
        macroAgg(targets == null ? null : targets.fibre(), BigDecimal.ZERO, BigDecimal.ZERO),
        new LinkedHashMap<>());
  }

  /**
   * Per-macro aggregate. {@code remaining = max(0, target - actual)} when a daily {@code target} is
   * configured; otherwise {@code max(0, planned - actual)} (how much of the plan is left). Never
   * negative (nutrition-6 / LLD Flow 9 line 1026).
   */
  private static MacroAggregateDto macroAgg(
      BigDecimal dailyTarget, BigDecimal planned, BigDecimal actual) {
    BigDecimal p = scale(planned);
    BigDecimal a = scale(actual);
    BigDecimal basis = dailyTarget != null ? scale(dailyTarget) : p;
    BigDecimal remaining = basis.subtract(a).max(BigDecimal.ZERO);
    return new MacroAggregateDto(p, a, remaining);
  }

  /**
   * Calories remaining: {@code max(0, dailyCalorieTarget - actual)} when targets exist, else {@code
   * max(0, planned - actual)}.
   */
  private static int caloriesRemaining(DailyTargets targets, int planned, int actual) {
    int basis = targets != null ? targets.calories() : planned;
    return Math.max(0, basis - actual);
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

  private static DailyAggregateDto sumDailies(List<DailyAggregateDto> days, DailyTargets targets) {
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

    // Weekly basis is 7× the daily target (or null → planned-based per-macro fallback).
    DailyTargets weekly = targets == null ? null : targets.times(7);
    return new DailyAggregateDto(
        caloriesPlanned,
        caloriesActual,
        caloriesRemaining(weekly, caloriesPlanned, caloriesActual),
        macroAgg(weekly == null ? null : weekly.protein(), proteinPlanned, proteinActual),
        macroAgg(weekly == null ? null : weekly.carbs(), carbsPlanned, carbsActual),
        macroAgg(weekly == null ? null : weekly.fat(), fatPlanned, fatActual),
        macroAgg(weekly == null ? null : weekly.fibre(), fibrePlanned, fibreActual),
        scaleMicros(micros));
  }

  /**
   * Compute weekly floor violations for the dashboard's {@code WeeklyAggregateDto.floorViolations}:
   * for each macro carrying a {@code <macro>FloorG}, compare weekly actual against {@code floorG ×
   * 7}. This is the weekly dashboard list (every breached floor), distinct from the planner's
   * per-target {@code is_hard_floor} multiplicative gate.
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

  /**
   * The user's daily target basis for the "remaining" computation: calories + the four macro target
   * grams. {@code remaining} is computed against these (zero-floored). {@code null} macro targets
   * fall back to the planned basis per-macro.
   */
  record DailyTargets(
      int calories, BigDecimal protein, BigDecimal carbs, BigDecimal fat, BigDecimal fibre) {

    static DailyTargets of(NutritionTargets t) {
      return new DailyTargets(
          t.getDailyCalorieTarget(),
          t.getProteinTargetG(),
          t.getCarbsTargetG(),
          t.getFatTargetG(),
          t.getFibreTargetG());
    }

    /** Scale every target by {@code factor} (used to derive the weekly-total basis = daily × 7). */
    DailyTargets times(int factor) {
      BigDecimal f = BigDecimal.valueOf(factor);
      return new DailyTargets(
          calories * factor,
          protein == null ? null : protein.multiply(f),
          carbs == null ? null : carbs.multiply(f),
          fat == null ? null : fat.multiply(f),
          fibre == null ? null : fibre.multiply(f));
    }
  }
}
