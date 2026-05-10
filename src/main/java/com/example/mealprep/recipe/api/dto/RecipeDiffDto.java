package com.example.mealprep.recipe.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Read shape of the persisted {@code change_diff} JSONB on a {@code RecipeVersion} row, projected
 * with the two version ids the diff is between. Built by the controller from {@code
 * toVersion.changeDiff}; per the LLD this is a key-value lookup, not a recompute.
 */
public record RecipeDiffDto(
    UUID fromVersionId,
    UUID toVersionId,
    List<IngredientChangeDto> ingredientChanges,
    List<MethodChangeDto> methodChanges,
    List<MetadataChangeDto> metadataChanges,
    List<TagChangeDto> tagChanges) {}
