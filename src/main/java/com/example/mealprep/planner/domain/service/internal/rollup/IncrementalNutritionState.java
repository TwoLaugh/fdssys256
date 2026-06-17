package com.example.mealprep.planner.domain.service.internal.rollup;

import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Immutable carrier of the running per-day macro/micro accumulators for the incremental Stage-A
 * scorer. Wraps a date-ascending map of {@link DailyMacroTotals.Builder} (the same mutable
 * accumulator the whole-plan {@link DailyMacroAggregator} walk uses) so the byte-identical
 * arithmetic is shared, but presents a copy-on-append face: each {@link #append} returns a NEW state
 * whose builders are deep copies, so sibling beam children never corrupt each other's totals.
 *
 * <p>The {@code Builder} type is package-private to {@code rollup}; this carrier is the public seam
 * the sibling {@code scoring} package uses, never naming {@code Builder} itself. Finalising to
 * {@code Map<LocalDate, DailyMacroTotals>} reproduces {@link DailyMacroAggregator#build} exactly.
 */
public final class IncrementalNutritionState {

  private final DailyMacroAggregator aggregator;
  private final Map<String, Integer> mealCalTargets;
  private final Map<String, BigDecimal> mealProteinTargets;
  private final Map<UUID, RecipeDto> byRecipeId;
  // Date-ascending (TreeMap) so build() iteration order matches the whole-plan walk's TreeMap.
  private final TreeMap<LocalDate, DailyMacroTotals.Builder> builders;

  IncrementalNutritionState(
      DailyMacroAggregator aggregator,
      Map<String, Integer> mealCalTargets,
      Map<String, BigDecimal> mealProteinTargets,
      Map<UUID, RecipeDto> byRecipeId,
      TreeMap<LocalDate, DailyMacroTotals.Builder> builders) {
    this.aggregator = aggregator;
    this.mealCalTargets = mealCalTargets;
    this.mealProteinTargets = mealProteinTargets;
    this.byRecipeId = byRecipeId;
    this.builders = builders;
  }

  /**
   * Fold one assignment into a fresh copy of these accumulators and return the new state. The
   * parent's builders are untouched; only the affected day's builder is copied-then-mutated, the
   * rest are shared by reference (they are never mutated again once a child is derived). Uses the
   * SAME {@link DailyMacroAggregator#applySlot} arithmetic as the whole-plan walk → byte-identical.
   */
  public IncrementalNutritionState append(SlotAssignment assignment) {
    TreeMap<LocalDate, DailyMacroTotals.Builder> next = new TreeMap<>(builders);
    LocalDate date = assignment.onDate();
    if (date != null) {
      DailyMacroTotals.Builder existing = next.get(date);
      // Copy the affected day so the parent / siblings keep their own accumulators intact. applySlot
      // re-creates the bucket via computeIfAbsent when absent, so a brand-new day needs no pre-seed.
      if (existing != null) {
        next.put(date, existing.copy());
      }
    }
    aggregator.applySlot(next, assignment, byRecipeId, mealCalTargets, mealProteinTargets);
    return new IncrementalNutritionState(
        aggregator, mealCalTargets, mealProteinTargets, byRecipeId, next);
  }

  /** Finalise to the immutable per-day totals — byte-identical to a whole-plan aggregation. */
  public Map<LocalDate, DailyMacroTotals> toByDate() {
    return aggregator.build(builders);
  }
}
