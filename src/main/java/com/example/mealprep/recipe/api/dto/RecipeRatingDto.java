package com.example.mealprep.recipe.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of a {@code RecipeRating} (recipe-02b). {@code taste} and {@code aggregate} are
 * primitives (always present); the other three dimensions are nullable {@link Integer}s reflecting
 * the one-tap default UX where only {@code taste} is supplied.
 */
public record RecipeRatingDto(
    UUID id,
    UUID recipeId,
    UUID versionId,
    UUID userId,
    UUID householdId,
    UUID slotId,
    int taste,
    Integer effortWorthIt,
    Integer portionFit,
    Integer repeatValue,
    int aggregate,
    String notes,
    UUID traceId,
    long optimisticVersion,
    Instant createdAt,
    Instant updatedAt) {}
