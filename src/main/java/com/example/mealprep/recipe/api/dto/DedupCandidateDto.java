package com.example.mealprep.recipe.api.dto;

import java.util.UUID;

/**
 * A near-duplicate the dedup check found in the caller's library (recipe-2 / HLD §Recipe
 * deduplication). Surfaced on the import preview ({@link RecipeImportPreview#dedupCandidate}) so
 * the frontend can offer "merge / import as variant / import anyway" before the user confirms, and
 * as the {@code candidateRecipeId} extension on the 422 {@code recipe-import-duplicate}
 * ProblemDetail.
 *
 * @param recipeId the existing library recipe this import resembles
 * @param ingredientOverlap measured Jaccard overlap of normalised ingredient-mapping-key sets
 *     (0.0–1.0)
 */
public record DedupCandidateDto(UUID recipeId, double ingredientOverlap) {}
