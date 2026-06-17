package com.example.mealprep.planner.api.dto;

import com.example.mealprep.planner.domain.entity.AugmentationSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Per-slot scheduled recipe. Cross-module IDs ({@code recipeId}, {@code recipeVersionId}, {@code
 * recipeBranchId}) are soft references to the recipe module's tables — they are not DB-level FKs
 * per LLD §Database. {@code augmentationNotes} / {@code augmentationSource} are populated by
 * Phase-2 augmentation (planner-01h); null until then.
 *
 * <p>{@code additions} are the in-meal riders (½ avocado, side salad) added in Phase 2 to close
 * residual calories + short micros — see {@link Addition}. Empty until additions are planned.
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
    boolean phase2Addition,
    List<Addition> additions,
    BigDecimal portionFactor) {

  public ScheduledRecipeDto {
    additions = additions == null ? List.of() : List.copyOf(additions);
    portionFactor = portionFactor == null ? BigDecimal.ONE : portionFactor;
  }

  /** Back-compat ctor (no additions / portion factor) — for pre-Phase-2 call sites. */
  public ScheduledRecipeDto(
      UUID id,
      UUID recipeId,
      UUID recipeVersionId,
      UUID recipeBranchId,
      int servings,
      UUID batchCookSessionId,
      String augmentationNotes,
      AugmentationSource augmentationSource,
      boolean phase2Addition) {
    this(
        id,
        recipeId,
        recipeVersionId,
        recipeBranchId,
        servings,
        batchCookSessionId,
        augmentationNotes,
        augmentationSource,
        phase2Addition,
        List.of(),
        BigDecimal.ONE);
  }
}
