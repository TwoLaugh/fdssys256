package com.example.mealprep.recipe.spi;

import java.util.UUID;

/**
 * Result of {@link RecipeWriteApi#saveImportedRecipe} per discovery-01g.
 *
 * <p>{@code newlyCreated=false} signals the dedup-by-fingerprint path — the returned {@code
 * recipeId} points at the EXISTING recipe whose {@code content_fingerprint} matched. {@code
 * dedupReason} carries the human-readable reason; null when {@code newlyCreated=true}.
 */
public record ImportedRecipeResult(
    UUID recipeId, UUID versionId, boolean newlyCreated, String dedupReason) {}
