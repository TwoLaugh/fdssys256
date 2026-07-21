package com.example.mealprep.planner.api.dto;

import com.example.mealprep.core.types.SlotKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One scheduled-recipe choice produced by the Stage-A beam search for a single {@link
 * MealSlotSkeleton}. Value carrier used by both {@link PartialPlan} (in-flight) and {@link
 * CandidatePlan} (final). Carries the slot identifiers (day + slot UUIDs pre-allocated by the
 * composer in 01j) so downstream scoring can key per-slot lookups by {@link #slotId()}.
 *
 * <p>{@code pinned == true} indicates the assignment came from {@code PinningRules} (mid-week
 * re-opt, 01i); the search does not expand or re-score pinned slots. For 01d's fresh-generation
 * tests the list of pinned assignments is always empty.
 *
 * <p>{@code additions} are in-meal riders bolted onto this slot's main recipe in Phase 2 (after
 * Stage-C, before the rollup that feeds coverage is rebuilt) to close residual calories + short
 * micros — see {@link Addition}. Empty for the beam search itself; populated only on the chosen
 * plan's assignments. {@code DailyMacroAggregator} sums each addition's own nutrition, and {@code
 * PlanPersister} copies the list onto the persisted {@code ScheduledRecipe}.
 *
 * <p>{@code portionFactor} is the per-person servings of the slot's MAIN recipe, attached ONLY on
 * the chosen plan's assignments by the finalise-time {@code PortionOptimizer} (it solves a per-day
 * portioning optimisation against ALL the user's daily macro targets, not the beam's cheap
 * calorie-only proxy). It is {@code null} during the beam search and on every fresh-built
 * assignment — when {@code null}, {@code DailyMacroAggregator} / {@code PlanPersister} fall back to
 * the existing {@code PortionScaler} computation (so the beam / incremental scoring path is byte
 * unchanged); when non-null, that optimised factor is used verbatim. Additions are NOT scaled by it
 * (they are pre-sized) — only the main recipe's servings are the optimiser's decision variable.
 */
public record SlotAssignment(
    UUID dayId,
    UUID slotId,
    int slotIndex,
    LocalDate onDate,
    SlotKind kind,
    UUID recipeId,
    UUID recipeVersionId,
    UUID recipeBranchId,
    int servings,
    boolean pinned,
    List<Addition> additions,
    BigDecimal portionFactor) {

  public SlotAssignment {
    additions = additions == null ? List.of() : List.copyOf(additions);
  }

  /**
   * Back-compat constructor (no additions) — defaults to an empty list and a {@code null} portion
   * factor. Retained so the beam-search / composer / test call sites that predate Phase-2 additions
   * compile unchanged; only the Phase-2 augmentation step builds assignments carrying additions
   * (via {@link #withAdditions(List)}).
   */
  public SlotAssignment(
      UUID dayId,
      UUID slotId,
      int slotIndex,
      LocalDate onDate,
      SlotKind kind,
      UUID recipeId,
      UUID recipeVersionId,
      UUID recipeBranchId,
      int servings,
      boolean pinned) {
    this(
        dayId,
        slotId,
        slotIndex,
        onDate,
        kind,
        recipeId,
        recipeVersionId,
        recipeBranchId,
        servings,
        pinned,
        List.of(),
        null);
  }

  /**
   * Back-compat constructor (with additions, no portion factor) — defaults {@code portionFactor} to
   * {@code null} so the Phase-2 addition planner's {@link #withAdditions(List)} call site and any
   * test that builds an assignment with additions compile unchanged. The optimised factor is only
   * ever attached via {@link #withPortionFactor(BigDecimal)} on the chosen plan.
   */
  public SlotAssignment(
      UUID dayId,
      UUID slotId,
      int slotIndex,
      LocalDate onDate,
      SlotKind kind,
      UUID recipeId,
      UUID recipeVersionId,
      UUID recipeBranchId,
      int servings,
      boolean pinned,
      List<Addition> additions) {
    this(
        dayId,
        slotId,
        slotIndex,
        onDate,
        kind,
        recipeId,
        recipeVersionId,
        recipeBranchId,
        servings,
        pinned,
        additions,
        null);
  }

  /** This assignment with its additions replaced — used by the Phase-2 addition planner. */
  public SlotAssignment withAdditions(List<Addition> newAdditions) {
    return new SlotAssignment(
        dayId,
        slotId,
        slotIndex,
        onDate,
        kind,
        recipeId,
        recipeVersionId,
        recipeBranchId,
        servings,
        pinned,
        newAdditions,
        portionFactor);
  }

  /**
   * This assignment with its optimised per-slot portion factor attached — used by the finalise-time
   * {@code PortionOptimizer}. When set, downstream readers ({@code DailyMacroAggregator}, {@code
   * PlanPersister}) use this factor instead of recomputing the calorie-only {@code PortionScaler}
   * value.
   */
  public SlotAssignment withPortionFactor(BigDecimal newPortionFactor) {
    return new SlotAssignment(
        dayId,
        slotId,
        slotIndex,
        onDate,
        kind,
        recipeId,
        recipeVersionId,
        recipeBranchId,
        servings,
        pinned,
        additions,
        newPortionFactor);
  }
}
