package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Read shape of a recipe — root scalar fields plus the current version's full body.
 *
 * <p>01a does NOT include a {@code branches} array; the branches list (and {@code RecipeBranchDto}
 * itself) defers to recipe-01b alongside the user-facing branch creation flow.
 */
public record RecipeDto(
    UUID id,
    UUID userId,
    Catalogue catalogue,
    String name,
    String description,
    int currentVersion,
    UUID currentBranchId,
    DataQuality dataQuality,
    NutritionStatus nutritionStatus,
    UUID forkedFromRecipeId,
    Instant lastUsedInPlanAt,
    Instant archivedAt,
    Instant deletedAt,
    long optimisticVersion,
    Instant createdAt,
    Instant updatedAt,
    RecipeVersionDto currentVersionBody) {}
