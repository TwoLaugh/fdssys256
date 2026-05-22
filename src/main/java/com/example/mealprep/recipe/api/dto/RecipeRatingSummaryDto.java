package com.example.mealprep.recipe.api.dto;

import java.util.UUID;

/**
 * Aggregate-rating summary for a version or a whole recipe (recipe-02b). {@code versionId} is null
 * for recipe-level aggregates. The {@code avg*} fields are nullable {@link Double}s — null when no
 * ratings exist (or, for a dimension, when no rating supplied that dimension). Constructed directly
 * by the JPQL aggregate queries in {@code RecipeRatingRepository}.
 */
public record RecipeRatingSummaryDto(
    UUID versionId,
    Double avgTaste,
    Double avgEffortWorthIt,
    Double avgPortionFit,
    Double avgRepeatValue,
    Double avgAggregate,
    long count) {}
