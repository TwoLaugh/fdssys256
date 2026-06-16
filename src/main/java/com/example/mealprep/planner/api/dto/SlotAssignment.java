package com.example.mealprep.planner.api.dto;

import com.example.mealprep.core.types.SlotKind;
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
    List<Addition> additions) {

  public SlotAssignment {
    additions = additions == null ? List.of() : List.copyOf(additions);
  }

  /**
   * Back-compat constructor (no additions) — defaults to an empty list. Retained so the beam-search
   * / composer / test call sites that predate Phase-2 additions compile unchanged; only the Phase-2
   * augmentation step builds assignments carrying additions (via {@link #withAdditions(List)}).
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
        List.of());
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
        newAdditions);
  }
}
