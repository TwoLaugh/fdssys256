package com.example.mealprep.recipe.api.dto;

import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read shape of a recipe — root scalar fields plus the current version's full body and the list of
 * branches associated with the recipe.
 *
 * <p>The {@code branches} field landed in recipe-01b alongside {@code GET
 * /api/v1/recipes/{recipeId}/branches}; in 01a/01b every recipe has exactly one auto-created 'main'
 * branch — recipe-01d will introduce user-facing branch creation.
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
    RecipeVersionDto currentVersionBody,
    List<RecipeBranchDto> branches) {}
