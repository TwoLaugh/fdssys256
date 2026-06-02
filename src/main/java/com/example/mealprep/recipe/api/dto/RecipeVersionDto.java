package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read shape of a recipe version's full body — ingredients ordered by {@code lineOrder}, method
 * steps ordered by {@code stepNumber}.
 *
 * <p>{@code appliedSubstitutionIds} is populated only by the {@code GET
 * /api/v1/recipes/{recipeId}/versions/{versionId}/with-substitutions} endpoint (recipe-01e). It is
 * {@code null} on every other read.
 *
 * <p>{@code embedding} (recipe-01i) surfaces the persisted per-recipe pgvector semantic vector
 * (1536-dim {@code text-embedding-3-small}; see {@code
 * com.example.mealprep.recipe.domain.entity.RecipeEmbeddingConverter}). It is {@code null} until
 * the async embedding listener populates the column ({@code embeddingStatus != EMBEDDED}) — every
 * read path is nullable-safe. The planner's {@code PreferenceSubScore} consumes it (cosine against
 * the user/household taste vector) and falls back to a neutral score when it is {@code null}. The
 * trailing position keeps the legacy 15-arg constructor (below) source-compatible for callers that
 * do not carry an embedding.
 */
public record RecipeVersionDto(
    UUID id,
    UUID branchId,
    int versionNumber,
    UUID parentVersionId,
    VersionTrigger trigger,
    String changeReason,
    String embeddingStatus,
    Instant createdAt,
    String createdByActor,
    UUID adapterTraceId,
    List<IngredientDto> ingredients,
    List<MethodStepDto> methodSteps,
    RecipeMetadataDto metadata,
    RecipeTagsDto tags,
    List<UUID> appliedSubstitutionIds,
    float[] embedding) {

  /**
   * Legacy 15-arg constructor (no embedding) — defaults {@code embedding} to {@code null}. Retained
   * so the many existing call sites that predate recipe-01i compile unchanged; only the recipe
   * mappers (which read the persisted vector) use the canonical constructor with the embedding.
   */
  public RecipeVersionDto(
      UUID id,
      UUID branchId,
      int versionNumber,
      UUID parentVersionId,
      VersionTrigger trigger,
      String changeReason,
      String embeddingStatus,
      Instant createdAt,
      String createdByActor,
      UUID adapterTraceId,
      List<IngredientDto> ingredients,
      List<MethodStepDto> methodSteps,
      RecipeMetadataDto metadata,
      RecipeTagsDto tags,
      List<UUID> appliedSubstitutionIds) {
    this(
        id,
        branchId,
        versionNumber,
        parentVersionId,
        trigger,
        changeReason,
        embeddingStatus,
        createdAt,
        createdByActor,
        adapterTraceId,
        ingredients,
        methodSteps,
        metadata,
        tags,
        appliedSubstitutionIds,
        null);
  }
}
