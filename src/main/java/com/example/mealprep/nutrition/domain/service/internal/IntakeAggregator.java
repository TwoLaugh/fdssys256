package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.DailyAggregateDto;
import com.example.mealprep.nutrition.api.dto.FloorViolationDto;
import com.example.mealprep.nutrition.api.dto.MacroAggregateDto;
import com.example.mealprep.nutrition.api.dto.MicroIntakeStatusDto;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
 *   <li>{@code satFat} has no dedicated slot columns — it is summed from the {@code
 *       "saturated_fat_g"} entry of each slot's planned/actual micros documents (and snack micros).
 *       Slots without saturated-fat data contribute zero. The raw {@code
 *       microsActualSoFar["saturated_fat_g"]} entry is retained for map-convention consumers.
 *   <li>{@code microsActualSoFar} merges the slot.actualMicros JSONB objects + snack.micros JSONB
 *       objects, summing numeric values per key.
 *   <li>{@code micros} adds per-micro status on top of the map: every merged key becomes a MEASURED
 *       row (a present zero stays MEASURED with value 0), and each tracked micro target (one
 *       carrying a floor or cap) that no decided source wrote becomes a NO_DATA row with a null
 *       value. Mirrors the planner-side coverage semantics in {@code RollupBuilderImpl}.
 *   <li>{@code aggregateWeek} is Monday-anchored; missing days contribute a zero-valued daily
 *       aggregate. Per-day {@code remaining} uses the daily target; the weekly total's remaining is
 *       the zero-floored 7×-target less the weekly actual. {@code floorViolations} is structured
 *       per enforcement mode — see {@link #computeWeeklyFloorViolations}.
 * </ul>
 */
@Component
public class IntakeAggregator {

  private static final int MACRO_SCALE = 2;
  private static final RoundingMode MACRO_ROUNDING = RoundingMode.HALF_UP;
  private static final BigDecimal SEVEN = BigDecimal.valueOf(7);

  /** Nutrient key the per-slot/snack micros documents use for saturated fat. */
  static final String SAT_FAT_MICRO_KEY = "saturated_fat_g";

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
    BigDecimal satFatPlanned = BigDecimal.ZERO;
    BigDecimal satFatActual = BigDecimal.ZERO;
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
      satFatPlanned = satFatPlanned.add(microValue(s.getPlannedMicros(), SAT_FAT_MICRO_KEY));
      satFatActual = satFatActual.add(microValue(s.getActualMicros(), SAT_FAT_MICRO_KEY));
      mergeMicros(micros, s.getActualMicros());
    }

    for (IntakeSnack snack : day.getSnacks()) {
      // Snacks contribute to actuals only — no planned counterpart.
      caloriesActual += snack.getCalories();
      proteinActual = proteinActual.add(nz(snack.getProteinG()));
      carbsActual = carbsActual.add(nz(snack.getCarbsG()));
      fatActual = fatActual.add(nz(snack.getFatG()));
      fibreActual = fibreActual.add(nz(snack.getFibreG()));
      satFatActual = satFatActual.add(microValue(snack.getMicros(), SAT_FAT_MICRO_KEY));
      mergeMicros(micros, snack.getMicros());
    }

    Map<String, BigDecimal> scaled = scaleMicros(micros);
    return new DailyAggregateDto(
        caloriesPlanned,
        caloriesActual,
        caloriesRemaining(targets, caloriesPlanned, caloriesActual),
        macroAgg(targets == null ? null : targets.protein(), proteinPlanned, proteinActual),
        macroAgg(targets == null ? null : targets.carbs(), carbsPlanned, carbsActual),
        macroAgg(targets == null ? null : targets.fat(), fatPlanned, fatActual),
        macroAgg(targets == null ? null : targets.fibre(), fibrePlanned, fibreActual),
        macroAgg(targets == null ? null : targets.satFat(), satFatPlanned, satFatActual),
        scaled,
        microStatuses(scaled, targets));
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

    List<FloorViolationDto> floorViolations =
        targetsOpt
            .map(
                t ->
                    computeWeeklyFloorViolations(
                        t, weekStart, perDay, byDate.keySet(), weeklyTotal))
            .orElseGet(List::of);

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
        macroAgg(targets == null ? null : targets.satFat(), BigDecimal.ZERO, BigDecimal.ZERO),
        new LinkedHashMap<>(),
        microStatuses(Map.of(), targets));
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

  /**
   * Numeric value of {@code key} inside a micros JSONB object, or {@code ZERO} when the document is
   * absent, not an object, lacks the key, or carries a non-numeric value (no null-poisoning).
   */
  private static BigDecimal microValue(JsonNode micros, String key) {
    if (micros == null || !micros.isObject()) {
      return BigDecimal.ZERO;
    }
    JsonNode v = micros.get(key);
    if (v == null || !v.isNumber()) {
      return BigDecimal.ZERO;
    }
    return v.decimalValue();
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

  /**
   * Per-micro status rows for one aggregate. Every measured key gets a MEASURED row carrying its
   * (already scaled) sum, so a present zero reads MEASURED with value 0, never NO_DATA. Then each
   * tracked micro target the merge never saw gets a NO_DATA row with a null value: no decided
   * source wrote the key, so intake is unknown, not zero (same rule as the planner's coverage in
   * RollupBuilderImpl). Ordering is deterministic: measured keys in merge order, then unmeasured
   * tracked keys in target order.
   */
  private static List<MicroIntakeStatusDto> microStatuses(
      Map<String, BigDecimal> measured, DailyTargets targets) {
    List<MicroIntakeStatusDto> out = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> e : measured.entrySet()) {
      out.add(
          new MicroIntakeStatusDto(
              e.getKey(),
              microUnit(e.getKey()),
              e.getValue(),
              MicroIntakeStatusDto.STATUS_MEASURED));
    }
    if (targets != null) {
      for (String key : targets.trackedMicroKeys()) {
        if (!measured.containsKey(key)) {
          out.add(
              new MicroIntakeStatusDto(
                  key, microUnit(key), null, MicroIntakeStatusDto.STATUS_NO_DATA));
        }
      }
    }
    return out;
  }

  /** Display-hint unit from the key suffix. Same derivation as the planner's coverage rows. */
  private static String microUnit(String key) {
    if (key.endsWith("_mcg")) {
      return "mcg";
    }
    if (key.endsWith("_mg")) {
      return "mg";
    }
    return "";
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
    BigDecimal satFatPlanned = BigDecimal.ZERO;
    BigDecimal satFatActual = BigDecimal.ZERO;
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
      satFatPlanned = satFatPlanned.add(d.satFat().plannedG());
      satFatActual = satFatActual.add(d.satFat().actualSoFarG());
      for (Map.Entry<String, BigDecimal> e : d.microsActualSoFar().entrySet()) {
        micros.merge(e.getKey(), e.getValue(), BigDecimal::add);
      }
    }

    // Weekly basis is 7× the daily target (or null → planned-based per-macro fallback).
    DailyTargets weekly = targets == null ? null : targets.times(7);
    Map<String, BigDecimal> scaled = scaleMicros(micros);
    return new DailyAggregateDto(
        caloriesPlanned,
        caloriesActual,
        caloriesRemaining(weekly, caloriesPlanned, caloriesActual),
        macroAgg(weekly == null ? null : weekly.protein(), proteinPlanned, proteinActual),
        macroAgg(weekly == null ? null : weekly.carbs(), carbsPlanned, carbsActual),
        macroAgg(weekly == null ? null : weekly.fat(), fatPlanned, fatActual),
        macroAgg(weekly == null ? null : weekly.fibre(), fibrePlanned, fibreActual),
        macroAgg(weekly == null ? null : weekly.satFat(), satFatPlanned, satFatActual),
        scaled,
        microStatuses(scaled, targets));
  }

  /**
   * Compute the weekly dashboard's {@code WeeklyAggregateDto.floorViolations} as structured {@link
   * FloorViolationDto} entries, split by enforcement mode:
   *
   * <ul>
   *   <li><b>Daily-enforcement macro floors</b> ({@code <macro>Enforcement} starting with {@code
   *       "daily"}, e.g. {@code "daily_floor"}): one dated entry per violating day. Only days with
   *       an intake row are evaluated — days the user never tracked are absent data, not
   *       violations.
   *   <li><b>Weekly-average macro floors</b> (any other enforcement, e.g. {@code
   *       "weekly_average"}): a single {@code date == null} entry whose {@code floor} is the
   *       7-day-summed floor and {@code actual} the weekly total.
   *   <li><b>Micro hard-floors</b> ({@code microTargets[].isHardFloor} with a {@code targetValue}):
   *       treated as daily-enforcement (micros carry no enforcement mode; the hard-floor gate is
   *       per-day) — dated entries keyed by nutrient key.
   * </ul>
   *
   * <p>Macros qualify whenever they carry a {@code <macro>FloorG} — the dashboard lists every
   * breached floor, distinct from the planner's per-target {@code is_hard_floor} multiplicative
   * gate.
   */
  private static List<FloorViolationDto> computeWeeklyFloorViolations(
      NutritionTargets t,
      LocalDate weekStart,
      List<DailyAggregateDto> perDay,
      Set<LocalDate> trackedDates,
      DailyAggregateDto weeklyTotal) {
    List<FloorViolationDto> violations = new ArrayList<>();
    addMacroViolations(
        violations,
        "protein",
        t.getProteinFloorG(),
        t.getProteinEnforcement(),
        d -> d.protein().actualSoFarG(),
        weekStart,
        perDay,
        trackedDates,
        weeklyTotal);
    addMacroViolations(
        violations,
        "carbs",
        t.getCarbsFloorG(),
        t.getCarbsEnforcement(),
        d -> d.carbs().actualSoFarG(),
        weekStart,
        perDay,
        trackedDates,
        weeklyTotal);
    addMacroViolations(
        violations,
        "fat",
        t.getFatFloorG(),
        t.getFatEnforcement(),
        d -> d.fat().actualSoFarG(),
        weekStart,
        perDay,
        trackedDates,
        weeklyTotal);
    addMacroViolations(
        violations,
        "fibre",
        t.getFibreFloorG(),
        t.getFibreEnforcement(),
        d -> d.fibre().actualSoFarG(),
        weekStart,
        perDay,
        trackedDates,
        weeklyTotal);
    for (MicroTarget m : t.getMicroTargets()) {
      if (!m.isHardFloor() || m.getTargetValue() == null || m.getNutrientKey() == null) {
        continue;
      }
      String key = m.getNutrientKey();
      addDailyViolations(
          violations,
          key,
          m.getTargetValue(),
          d -> d.microsActualSoFar().getOrDefault(key, BigDecimal.ZERO),
          weekStart,
          perDay,
          trackedDates);
    }
    return violations;
  }

  /** One macro's contribution: dated per-day entries (daily) or a single weekly entry. */
  private static void addMacroViolations(
      List<FloorViolationDto> out,
      String key,
      BigDecimal dailyFloor,
      String enforcement,
      Function<DailyAggregateDto, BigDecimal> actualOf,
      LocalDate weekStart,
      List<DailyAggregateDto> perDay,
      Set<LocalDate> trackedDates,
      DailyAggregateDto weeklyTotal) {
    if (dailyFloor == null) {
      return;
    }
    if (isDailyEnforcement(enforcement)) {
      addDailyViolations(out, key, dailyFloor, actualOf, weekStart, perDay, trackedDates);
      return;
    }
    BigDecimal weeklyFloor = dailyFloor.multiply(SEVEN);
    BigDecimal weeklyActual = actualOf.apply(weeklyTotal);
    if (weeklyActual.compareTo(weeklyFloor) < 0) {
      out.add(new FloorViolationDto(null, key, weeklyFloor, weeklyActual));
    }
  }

  /** Dated entry for each tracked day whose actual fell below the daily {@code floor}. */
  private static void addDailyViolations(
      List<FloorViolationDto> out,
      String key,
      BigDecimal floor,
      Function<DailyAggregateDto, BigDecimal> actualOf,
      LocalDate weekStart,
      List<DailyAggregateDto> perDay,
      Set<LocalDate> trackedDates) {
    for (int i = 0; i < perDay.size(); i++) {
      LocalDate date = weekStart.plusDays(i);
      if (!trackedDates.contains(date)) {
        continue;
      }
      BigDecimal dayActual = actualOf.apply(perDay.get(i));
      if (dayActual.compareTo(floor) < 0) {
        out.add(new FloorViolationDto(date, key, floor, dayActual));
      }
    }
  }

  /**
   * Whether the free-form enforcement string denotes per-day enforcement ({@code "daily_floor"},
   * {@code "daily_band"}, …). Anything else — including {@code null} — is weekly-average.
   */
  private static boolean isDailyEnforcement(String enforcement) {
    return enforcement != null && enforcement.startsWith("daily");
  }

  /**
   * The user's daily target basis for the "remaining" computation: calories + the five macro target
   * grams. {@code remaining} is computed against these (zero-floored). {@code null} macro targets
   * fall back to the planned basis per-macro.
   *
   * <p>{@code trackedMicroKeys} lists the micro targets that carry a floor or cap, in target order.
   * They drive the NO_DATA rows of {@link #microStatuses}; a keyless or unbounded micro target is
   * not tracked (same filter as the planner's coverage).
   */
  record DailyTargets(
      int calories,
      BigDecimal protein,
      BigDecimal carbs,
      BigDecimal fat,
      BigDecimal fibre,
      BigDecimal satFat,
      List<String> trackedMicroKeys) {

    static DailyTargets of(NutritionTargets t) {
      List<String> tracked = new ArrayList<>();
      for (MicroTarget m : t.getMicroTargets()) {
        if (m.getNutrientKey() != null
            && (m.getTargetValue() != null || m.getUpperLimit() != null)) {
          tracked.add(m.getNutrientKey());
        }
      }
      return new DailyTargets(
          t.getDailyCalorieTarget(),
          t.getProteinTargetG(),
          t.getCarbsTargetG(),
          t.getFatTargetG(),
          t.getFibreTargetG(),
          t.getSatFatTargetG(),
          tracked);
    }

    /** Scale every target by {@code factor} (used to derive the weekly-total basis = daily × 7). */
    DailyTargets times(int factor) {
      BigDecimal f = BigDecimal.valueOf(factor);
      return new DailyTargets(
          calories * factor,
          protein == null ? null : protein.multiply(f),
          carbs == null ? null : carbs.multiply(f),
          fat == null ? null : fat.multiply(f),
          fibre == null ? null : fibre.multiply(f),
          satFat == null ? null : satFat.multiply(f),
          trackedMicroKeys);
    }
  }
}
