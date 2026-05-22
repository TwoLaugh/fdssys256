package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/recipes/{recipeId}/ratings} (recipe-02b).
 *
 * <p>{@code taste} is required (one-tap default path supplies only this). The other three
 * dimensions are optional and default to {@code taste} in the aggregate when absent. {@code slotId}
 * is optional — null when rating without a planned slot context.
 */
public record CreateRatingRequest(
    @NotNull UUID versionId,
    UUID slotId,
    @NotNull @Min(0) @Max(100) Integer taste,
    @Min(0) @Max(100) Integer effortWorthIt,
    @Min(0) @Max(100) Integer portionFit,
    @Min(0) @Max(100) Integer repeatValue,
    @Size(max = 1000) String notes) {}
