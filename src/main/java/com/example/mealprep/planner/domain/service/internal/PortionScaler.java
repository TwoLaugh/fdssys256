package com.example.mealprep.planner.domain.service.internal;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Pure portion-scaling arithmetic for the planner (Phase 1 of the portion-scaling + additions
 * design — {@code design/nutrition/portion-scaling-and-additions.md}).
 *
 * <p>The planner schedules exactly one <i>per-person</i> serving per slot, but the pool's median
 * recipe (~440 kcal/serving) sits far below per-meal calorie targets (900–1100 kcal). The portion
 * factor is how many servings of the slot's main the primary eater actually consumes, sized to the
 * meal's calorie target and clamped so a recipe far below target can't fabricate an absurd plate
 * (and a recipe far above target shrinks rather than overshooting):
 *
 * <pre>
 * {@code factor = clamp(round_to_step(perMealCalorieTarget / recipe.kcalPerServing), 0.5, 3.0)}
 * </pre>
 *
 * <p>Deliberately deterministic (no AI): cheap, predictable, no token cost or latency. Held here as
 * a stateless util so the same arithmetic is shared by {@code DailyMacroAggregator} (Phase 1a,
 * scaling the coverage rollup) and the planner composition step that will persist the factor onto
 * {@code ScheduledRecipe} (Phase 1b) — one source of truth for the bounds, not three copies.
 */
public final class PortionScaler {

  /** Minimum servings a slot is scaled to — a slot is at least half a serving. */
  public static final double MIN_FACTOR = 0.5;

  /** Maximum servings — past this, leave the meal honestly short rather than fabricate food. */
  public static final double MAX_FACTOR = 3.0;

  /** Rounding granularity — "1.75 servings", not "1.732…". */
  public static final double STEP = 0.25;

  /**
   * How far a scaled meal's protein may exceed its per-meal protein target before the factor is
   * capped. Calorie-only scaling overshoots protein badly with the taste profile's protein-dense
   * favourites (a 75 g-protein/serving stew scaled ×2 to reach a calorie target single-handedly
   * blows the daily protein floor); this caps the scale-up so no one meal piles on more than {@code
   * tolerance ×} its per-meal protein target. 1.5 keeps a day near ~1.5× the daily floor instead of
   * ~2×, trading a little calorie attainment for protein sanity. Tunable; promote to {@code
   * PlannerProperties} if it needs per-environment control during calibration.
   */
  public static final double PROTEIN_OVERSHOOT_TOLERANCE = 1.5;

  private PortionScaler() {}

  /**
   * Normalise a {@code SlotKind} / {@code MealSlot} enum name to a common key so the planner's
   * {@code SlotKind.SNACK} matches nutrition's {@code MealSlot.SNACKS}. Strips a single trailing
   * {@code S} (only {@code SNACKS} is affected; {@code BREAKFAST}/{@code LUNCH}/{@code DINNER} end
   * in other letters).
   */
  public static String normaliseKind(String enumName) {
    if (enumName == null) {
      return "";
    }
    String u = enumName.toUpperCase(Locale.ROOT);
    return u.endsWith("S") ? u.substring(0, u.length() - 1) : u;
  }

  /**
   * Servings of a recipe the primary eater consumes to meet a per-meal calorie target, clamped to
   * {@code [MIN_FACTOR, MAX_FACTOR]} in {@link #STEP} increments. Falls back to {@code 1.0} when
   * the recipe has no calories ({@code <= 0}) or there is no target for the slot ({@code
   * null}/{@code <= 0}) — so an un-sizable slot contributes a plain single serving and coverage
   * stays honest.
   */
  public static double factor(int kcalPerServing, Integer perMealCalorieTarget) {
    return factor(kcalPerServing, perMealCalorieTarget, null, null);
  }

  /**
   * Macro-aware portion factor: scale toward the per-meal calorie target as before, but cap the
   * scale-up so the scaled serving's protein does not exceed {@link #PROTEIN_OVERSHOOT_TOLERANCE} ×
   * the per-meal protein target — i.e. {@code factor = clamp(min(calorieFactor, proteinCapFactor),
   * 0.5, 3.0)}. The protein cap engages only when both a per-meal protein target and the recipe's
   * per-serving protein are known and positive; otherwise this is exactly the calorie-only {@link
   * #factor(int, Integer)} (so callers/tests without a per-meal protein distribution are
   * unchanged). Keeps protein-dense taste picks from inflating the daily protein floor when scaled
   * to hit calories.
   */
  public static double factor(
      int kcalPerServing,
      Integer perMealCalorieTarget,
      BigDecimal proteinPerServingG,
      BigDecimal perMealProteinTargetG) {
    if (kcalPerServing <= 0 || perMealCalorieTarget == null || perMealCalorieTarget <= 0) {
      return 1.0;
    }
    double factor = roundToStep((double) perMealCalorieTarget / kcalPerServing);
    if (proteinPerServingG != null
        && proteinPerServingG.signum() > 0
        && perMealProteinTargetG != null
        && perMealProteinTargetG.signum() > 0) {
      double proteinCap =
          roundToStep(
              perMealProteinTargetG.doubleValue()
                  * PROTEIN_OVERSHOOT_TOLERANCE
                  / proteinPerServingG.doubleValue());
      factor = Math.min(factor, proteinCap);
    }
    return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, factor));
  }

  private static double roundToStep(double servings) {
    return Math.round(servings / STEP) * STEP;
  }
}
