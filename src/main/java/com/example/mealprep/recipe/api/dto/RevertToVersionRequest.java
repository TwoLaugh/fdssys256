package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/recipes/{recipeId}/versions/revert}. Per LLD §REST line 647.
 * Server writes a new version on the target branch whose body is cloned from the target version;
 * {@code trigger = REVERT}.
 */
public record RevertToVersionRequest(
    @NotNull UUID branchId,
    @Min(1) int versionNumber,
    @Min(0) long expectedRecipeOptimisticVersion) {}
