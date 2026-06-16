package com.example.mealprep.planner.domain.service.internal;

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
 * <pre>{@code factor = clamp(round_to_step(perMealCalorieTarget / recipe.kcalPerServing), 0.5, 3.0)}</pre>
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
   * {@code [MIN_FACTOR, MAX_FACTOR]} in {@link #STEP} increments. Falls back to {@code 1.0} when the
   * recipe has no calories ({@code <= 0}) or there is no target for the slot ({@code null}/{@code
   * <= 0}) — so an un-sizable slot contributes a plain single serving and coverage stays honest.
   */
  public static double factor(int kcalPerServing, Integer perMealCalorieTarget) {
    if (kcalPerServing <= 0 || perMealCalorieTarget == null || perMealCalorieTarget <= 0) {
      return 1.0;
    }
    double stepped = Math.round(((double) perMealCalorieTarget / kcalPerServing) / STEP) * STEP;
    return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, stepped));
  }
}
