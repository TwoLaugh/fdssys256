package com.example.mealprep.planner.domain.service.internal.stagec;

import java.util.UUID;

/**
 * Sealed Phase-2 augmentation hierarchy. Per lld/planner.md §{@code Phase2Augmenter} lines 882-883
 * (planner-01h). Three permitted record subtypes; pattern-matched exhaustively (no {@code default}
 * arm needed) by {@link AugmentationVerifier}.
 *
 * <p>{@code targetSlotId()} is {@code null} for plan-level repairs (a {@link RepairAugmentation}
 * that flags a whole-plan issue rather than a single slot).
 *
 * <p><b>Visibility note (planner-01h reconciliation):</b> the ticket's file table marks the
 * hierarchy package-private, but the public {@code api.dto.AugmentationResult} record exposes
 * {@code List<Augmentation>} across packages — Java requires the element type to be public for that
 * to compile. The hierarchy is therefore {@code public} but stays in the module-internal {@code
 * domain.service.internal.stagec} package (so it is not re-exported via the module facade); {@code
 * PlannerBoundaryTest} only fences {@code domain.repository}, so this is boundary-clean.
 */
public sealed interface Augmentation
    permits AddSnackAugmentation, IngredientSwapAugmentation, RepairAugmentation {

  /** The slot this augmentation targets; {@code null} for plan-level repairs. */
  UUID targetSlotId();
}
