package com.example.mealprep.planner.domain.service.internal.stagec;

import java.util.UUID;

/**
 * Insert a new snack slot or scheduled recipe. Per lld/planner.md §{@code Phase2Augmenter}
 * (planner-01h). The verifier checks the {@code newRecipeId} resolves in {@code ctx.recipePool()},
 * that its ingredients pass the hard-constraint filter for the slot's eaters, and that the recipe's
 * total time is within the slot's time budget × overshoot ratio.
 *
 * <p>The actual slot/scheduled-recipe creation is the composer's job (planner-01j); 01h only
 * verifies and surfaces.
 */
public record AddSnackAugmentation(
    UUID targetSlotId, UUID newRecipeId, int servings, String reasoning) implements Augmentation {}
