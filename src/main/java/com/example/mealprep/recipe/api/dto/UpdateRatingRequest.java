package com.example.mealprep.recipe.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code PUT /api/v1/recipes/{recipeId}/ratings/{ratingId}} (recipe-02b). Same
 * shape as {@link CreateRatingRequest} plus {@code expectedVersion} for optimistic-lock checking —
 * a user revising a rating after a re-cook supplies the version they last read.
 */
public record UpdateRatingRequest(
    @NotNull UUID versionId,
    UUID slotId,
    @NotNull @Min(0) @Max(100) Integer taste,
    @Min(0) @Max(100) Integer effortWorthIt,
    @Min(0) @Max(100) Integer portionFit,
    @Min(0) @Max(100) Integer repeatValue,
    @Size(max = 1000) String notes,
    @Min(0) long expectedVersion) {}
