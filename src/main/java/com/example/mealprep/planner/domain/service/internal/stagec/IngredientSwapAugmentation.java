package com.example.mealprep.planner.domain.service.internal.stagec;

import java.util.UUID;

/**
 * Swap one ingredient in a slot's recipe. Per lld/planner.md §{@code Phase2Augmenter}
 * (planner-01h).
 *
 * <p><b>Tag-only carrier.</b> This augmentation does NOT mutate the recipe — the real recipe
 * substitution flows through the adaptation pipeline (Stage D) via a refine-directive, not Phase 2.
 * 01h's verifier validates only the <i>allergen safety</i> of the swapped-in ingredient (it
 * substitutes the key in the recipe's ingredient list and re-runs the hard-constraint filter); it
 * does not validate semantic correctness of the swap (that requires recipe-substitution logic owned
 * by recipe-01e). The composer (01j) surfaces this in {@code ScheduledRecipe.augmentationNotes} for
 * user awareness.
 */
public record IngredientSwapAugmentation(
    UUID targetSlotId, String fromIngredientKey, String toIngredientKey, String reasoning)
    implements Augmentation {}
