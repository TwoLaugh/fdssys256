package com.example.mealprep.planner.domain.service.internal.scoring;

import com.example.mealprep.planner.domain.service.internal.rollup.IncrementalNutritionState;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable running accumulators for the incremental Stage-A composite. Appending one slot derives a
 * new state in O(1)-ish (small set/map copies) instead of re-walking the whole partial plan, and the
 * composite is finalised from these raw accumulators by {@link IncrementalScoringEngine} reproducing
 * each sub-score's exact arithmetic verbatim.
 *
 * <p>RAW accumulators only — running sums (BigDecimal, order-independent-exact), counts, distinct
 * sets, per-day nutrition totals, and the variety-gate repeat counts. All rounding / division is
 * deferred to the finalize step so the result is byte-identical to {@code ScoringEngine.score}.
 *
 * <ul>
 *   <li>preference: {@code preferenceSum} + {@code preferenceCount} of the per-slot taste score.
 *   <li>time: {@code timeSum} + {@code timeCount} of the per-slot time score.
 *   <li>variety: distinct {@code cuisines} / {@code proteins} / {@code methods} sets.
 *   <li>batch: {@code batchSlotCount} (= total slots) + {@code sawNoBatch} + {@code distinctSessions}.
 *   <li>nutrition + floor gate: {@code nutrition} carries the per-day totals.
 *   <li>variety gate: {@code recipeCounts} (recipeId → count) + {@code varietyGateFailed}.
 * </ul>
 */
public final class IncrementalScoreState {

  final BigDecimal preferenceSum;
  final int preferenceCount;
  final BigDecimal timeSum;
  final int timeCount;
  final Set<String> cuisines;
  final Set<String> proteins;
  final Set<String> methods;
  final int batchSlotCount;
  final boolean sawNoBatch;
  final Set<UUID> distinctSessions;
  final IncrementalNutritionState nutrition;
  final Map<UUID, Integer> recipeCounts;
  final boolean varietyGateFailed;

  IncrementalScoreState(
      BigDecimal preferenceSum,
      int preferenceCount,
      BigDecimal timeSum,
      int timeCount,
      Set<String> cuisines,
      Set<String> proteins,
      Set<String> methods,
      int batchSlotCount,
      boolean sawNoBatch,
      Set<UUID> distinctSessions,
      IncrementalNutritionState nutrition,
      Map<UUID, Integer> recipeCounts,
      boolean varietyGateFailed) {
    this.preferenceSum = preferenceSum;
    this.preferenceCount = preferenceCount;
    this.timeSum = timeSum;
    this.timeCount = timeCount;
    this.cuisines = cuisines;
    this.proteins = proteins;
    this.methods = methods;
    this.batchSlotCount = batchSlotCount;
    this.sawNoBatch = sawNoBatch;
    this.distinctSessions = distinctSessions;
    this.nutrition = nutrition;
    this.recipeCounts = recipeCounts;
    this.varietyGateFailed = varietyGateFailed;
  }

  /** The seed state for an empty plan, holding a fresh empty per-day nutrition accumulator. */
  static IncrementalScoreState empty(IncrementalNutritionState nutrition) {
    return new IncrementalScoreState(
        BigDecimal.ZERO,
        0,
        BigDecimal.ZERO,
        0,
        new HashSet<>(),
        new HashSet<>(),
        new HashSet<>(),
        0,
        false,
        new HashSet<>(),
        nutrition,
        new HashMap<>(),
        false);
  }
}
