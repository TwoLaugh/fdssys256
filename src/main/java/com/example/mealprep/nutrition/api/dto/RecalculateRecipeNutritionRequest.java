package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate}.
 * Provides the branch + version-number triple so the controller can re-fetch the version through
 * {@code RecipeQueryService}.
 */
public record RecalculateRecipeNutritionRequest(
    @NotNull UUID branchId, @NotNull @Min(1) Integer versionNumber) {}
