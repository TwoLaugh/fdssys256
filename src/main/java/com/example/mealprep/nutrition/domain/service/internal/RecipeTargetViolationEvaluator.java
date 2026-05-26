package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.entity.PerMealDistributionEntry;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * Pure (stateless, no-Spring) evaluator behind {@code
 * NutritionQueryService.findRecipeIdsViolatingTargets} — the coarse <b>v1 pre-filter</b> the
 * adaptation pipeline uses to decide which recipes are worth an LLM adaptation call. The LLM job,
 * NOT this class, performs the real nutrition adaptation; this only flags recipes whose stored
 * per-serving nutrition plainly breaches the user's targets at the per-meal level.
 *
 * <p>By design the heuristic MAY slightly over-select (the LLM is the real decision-maker). It must
 * NOT under-select obvious cases, so the rules are deliberately simple and direction-aware.
 *
 * <p><b>Per-meal-share scaling.</b> Targets are DAILY but a recipe is ONE meal, so each daily
 * allowance is scaled by {@code mealShare} (see {@link #mealShare(NutritionTargets)}): the LARGEST
 * per-meal calorie fraction the user configured, or {@link #DEFAULT_MEAL_SHARE} when no per-meal
 * distribution exists.
 *
 * <p><b>Field shape.</b> Inputs are the serialised {@link
 * com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto} JSON: {@code caloriesPerServing}
 * (int), {@code proteinPerServingG} / {@code carbsPerServingG} / {@code fatPerServingG} / {@code
 * fibrePerServingG} (decimal), and a {@code microsPerServing} object. Saturated fat is NOT a
 * top-level field on that DTO in this codebase, so it is read (best-effort) from {@code
 * microsPerServing["saturatedFatG"]} and simply skipped when absent — see the class report.
 */
final class RecipeTargetViolationEvaluator {

  private RecipeTargetViolationEvaluator() {}

  /**
   * Conservative fallback per-meal fraction used when the user has no {@code perMealDistribution}
   * configured. A single meal can plausibly be up to ~40% of a day's intake, so 0.4 keeps the gate
   * coarse (lets borderline recipes through to the LLM) without under-flagging the clearly-over.
   */
  static final BigDecimal DEFAULT_MEAL_SHARE = new BigDecimal("0.4");

  // RecipeNutritionResultDto field names (the per-serving JSON shape we own).
  private static final String F_CALORIES = "caloriesPerServing";
  private static final String F_PROTEIN = "proteinPerServingG";
  private static final String F_CARBS = "carbsPerServingG";
  private static final String F_FAT = "fatPerServingG";
  private static final String F_FIBRE = "fibrePerServingG";
  private static final String F_MICROS = "microsPerServing";
  // Saturated fat has no top-level field on RecipeNutritionResultDto; CandidateDailyRollupDto
  // documents "saturatedFatG" as the normalised micro key, so we look there best-effort.
  private static final String MICRO_SAT_FAT = "saturatedFatG";

  /**
   * @return {@code true} if {@code perServingJson} violates any enforced macro of {@code targets}
   *     at the per-meal level; {@code false} if it is within all bands. Never throws — an
   *     absent/unparseable macro field is treated as "no data, not a violation".
   */
  static boolean violates(NutritionTargets targets, JsonNode perServingJson) {
    BigDecimal mealShare = mealShare(targets);

    // ----- Calories (ceiling). UPPER_LIMIT / BOTH_BOUNDED → check over; LOWER_FLOOR → skip. -----
    EnforcementDirection calDir = targets.getCalorieDirection();
    if (calDir == EnforcementDirection.UPPER_LIMIT || calDir == EnforcementDirection.BOTH_BOUNDED) {
      BigDecimal cals = decimalField(perServingJson, F_CALORIES);
      if (cals != null) {
        BigDecimal allowance =
            BigDecimal.valueOf(targets.getDailyCalorieTarget()).multiply(mealShare);
        // Calories carry an explicit per-day over-tolerance; scale it to the meal too.
        BigDecimal tolerance =
            BigDecimal.valueOf(targets.getCalorieToleranceOver()).multiply(mealShare);
        if (exceedsCeiling(cals, allowance, tolerance)) {
          return true;
        }
      }
    }

    // ----- Protein (floor). LOWER_FLOOR / BOTH_BOUNDED + a configured floor → check under. -----
    if (floorViolated(
        targets.getProteinDirection(),
        targets.getProteinFloorG(),
        mealShare,
        decimalField(perServingJson, F_PROTEIN))) {
      return true;
    }

    // ----- Carbs (floor, if configured). -----
    if (floorViolated(
        targets.getCarbsDirection(),
        targets.getCarbsFloorG(),
        mealShare,
        decimalField(perServingJson, F_CARBS))) {
      return true;
    }

    // ----- Fat (ceiling). -----
    if (ceilingViolated(
        targets.getFatDirection(),
        targets.getFatTargetG(),
        mealShare,
        decimalField(perServingJson, F_FAT))) {
      return true;
    }

    // ----- Fibre (floor, if configured). -----
    if (floorViolated(
        targets.getFibreDirection(),
        targets.getFibreFloorG(),
        mealShare,
        decimalField(perServingJson, F_FIBRE))) {
      return true;
    }

    // ----- Saturated fat (ceiling). Best-effort from micros["saturatedFatG"]; skipped if absent.
    // --
    if (ceilingViolated(
        targets.getSatFatDirection(),
        targets.getSatFatTargetG(),
        mealShare,
        microField(perServingJson, MICRO_SAT_FAT))) {
      return true;
    }

    return false;
  }

  /**
   * mealShare = the largest per-meal calorie fraction the user configured ({@code
   * slot.calorieTarget / dailyCalorieTarget}), else {@link #DEFAULT_MEAL_SHARE}. The persisted
   * {@code PerMealDistributionEntry} stores ABSOLUTE per-slot calorie targets (not fractions), so
   * we derive the fraction here. Falls back to the default if the list is empty or the daily target
   * is not positive (degenerate data).
   */
  static BigDecimal mealShare(NutritionTargets targets) {
    int daily = targets.getDailyCalorieTarget();
    if (daily <= 0
        || targets.getPerMealDistribution() == null
        || targets.getPerMealDistribution().isEmpty()) {
      return DEFAULT_MEAL_SHARE;
    }
    int maxSlotKcal = 0;
    for (PerMealDistributionEntry slot : targets.getPerMealDistribution()) {
      maxSlotKcal = Math.max(maxSlotKcal, slot.getCalorieTarget());
    }
    if (maxSlotKcal <= 0) {
      return DEFAULT_MEAL_SHARE;
    }
    return BigDecimal.valueOf(maxSlotKcal)
        .divide(BigDecimal.valueOf(daily), 6, java.math.RoundingMode.HALF_UP);
  }

  // ---------------- per-direction rules ----------------

  /**
   * Ceiling rule for a macro whose daily allowance is {@code dailyTargetG}. Only fires for {@code
   * UPPER_LIMIT} / {@code BOTH_BOUNDED} directions and when both the target and the per-serving
   * value are present. No explicit tolerance field on macros → tolerance 0.
   */
  private static boolean ceilingViolated(
      EnforcementDirection direction,
      BigDecimal dailyTargetG,
      BigDecimal mealShare,
      BigDecimal perServing) {
    if (perServing == null || dailyTargetG == null) {
      return false;
    }
    if (direction != EnforcementDirection.UPPER_LIMIT
        && direction != EnforcementDirection.BOTH_BOUNDED) {
      return false; // pure floor / not-enforced-as-ceiling → skip
    }
    BigDecimal allowance = dailyTargetG.multiply(mealShare);
    return exceedsCeiling(perServing, allowance, BigDecimal.ZERO);
  }

  /**
   * Floor rule for a macro that carries an explicit {@code *FloorG}. Only fires for {@code
   * LOWER_FLOOR} / {@code BOTH_BOUNDED} directions, when the floor is configured AND the
   * per-serving value is present. No explicit tolerance field on macros → tolerance 0.
   */
  private static boolean floorViolated(
      EnforcementDirection direction,
      BigDecimal dailyFloorG,
      BigDecimal mealShare,
      BigDecimal perServing) {
    if (perServing == null || dailyFloorG == null) {
      return false; // no floor configured or no data → cannot be a floor violation
    }
    if (direction != EnforcementDirection.LOWER_FLOOR
        && direction != EnforcementDirection.BOTH_BOUNDED) {
      return false; // pure ceiling / not-enforced-as-floor → skip
    }
    BigDecimal floor = dailyFloorG.multiply(mealShare);
    return belowFloor(perServing, floor, BigDecimal.ZERO);
  }

  private static boolean exceedsCeiling(
      BigDecimal perServing, BigDecimal allowance, BigDecimal tolerance) {
    return perServing.compareTo(allowance.add(tolerance)) > 0;
  }

  private static boolean belowFloor(BigDecimal perServing, BigDecimal floor, BigDecimal tolerance) {
    return perServing.compareTo(floor.subtract(tolerance)) < 0;
  }

  // ---------------- JSON field extraction (graceful) ----------------

  /** Read a top-level numeric field; {@code null} when missing, null, or not a number. */
  private static BigDecimal decimalField(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull() || !v.isNumber()) {
      return null;
    }
    return v.decimalValue();
  }

  /** Read a numeric value out of the {@code microsPerServing} object; {@code null} when absent. */
  private static BigDecimal microField(JsonNode node, String key) {
    if (node == null) {
      return null;
    }
    JsonNode micros = node.get(F_MICROS);
    if (micros == null || !micros.isObject()) {
      return null;
    }
    JsonNode v = micros.get(key);
    if (v == null || v.isNull() || !v.isNumber()) {
      return null;
    }
    return v.decimalValue();
  }
}
