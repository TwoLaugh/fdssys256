package com.example.mealprep.planner.api.dto;

import com.example.mealprep.core.types.SlotKind;
import java.time.LocalDate;
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
    boolean pinned) {}
