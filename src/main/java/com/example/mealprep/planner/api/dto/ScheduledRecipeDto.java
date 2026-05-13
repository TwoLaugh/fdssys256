package com.example.mealprep.planner.api.dto;

import com.example.mealprep.planner.domain.entity.AugmentationSource;
import java.util.UUID;

/**
 * Per-slot scheduled recipe. Cross-module IDs ({@code recipeId}, {@code recipeVersionId}, {@code
 * recipeBranchId}) are soft references to the recipe module's tables — they are not DB-level FKs
 * per LLD §Database. {@code augmentationNotes} / {@code augmentationSource} are populated by
 * Phase-2 augmentation (planner-01h); null until then.
 */
public record ScheduledRecipeDto(
    UUID id,
    UUID recipeId,
    UUID recipeVersionId,
    UUID recipeBranchId,
    int servings,
    UUID batchCookSessionId,
    String augmentationNotes,
    AugmentationSource augmentationSource,
    boolean phase2Addition) {}
