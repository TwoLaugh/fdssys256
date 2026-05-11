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
    List<UUID> appliedSubstitutionIds) {}
