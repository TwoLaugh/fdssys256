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
 *
 * <p>The {@code imageUrl} field landed in recipe-02a: when non-null it points at the {@code GET
 * /api/v1/recipes/{recipeId}/image} serve endpoint (frontend uses it directly as an {@code <img
 * src=...>}). Null for recipes with no image.
 *
 * <p>The {@code avgTaste} + {@code ratingCount} aggregate fields landed with {@code GET
 * /api/v1/recipes} (library list/search): they are <b>populated only by the list read</b> (one
 * batched aggregate query per page — resolves the recipes-page §8 Q4 per-card N+1) and are {@code
 * null} on every other read path (the detail page keeps using {@code GET /ratings/summary}). On
 * list rows an unrated recipe carries {@code avgTaste = null} / {@code ratingCount = 0}.
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
    String imageUrl,
    long optimisticVersion,
    Instant createdAt,
    Instant updatedAt,
    RecipeVersionDto currentVersionBody,
    List<RecipeBranchDto> branches,
    Double avgTaste,
    Long ratingCount) {

  /**
   * Compatibility constructor for the pre-list shape — defaults the list-only rating aggregate
   * fields to {@code null} (the additive-DTO convention: nullable additions, not new required
   * fields). Non-list read paths and existing call sites keep this arity.
   */
  public RecipeDto(
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
      String imageUrl,
      long optimisticVersion,
      Instant createdAt,
      Instant updatedAt,
      RecipeVersionDto currentVersionBody,
      List<RecipeBranchDto> branches) {
    this(
        id,
        userId,
        catalogue,
        name,
        description,
        currentVersion,
        currentBranchId,
        dataQuality,
        nutritionStatus,
        forkedFromRecipeId,
        lastUsedInPlanAt,
        archivedAt,
        deletedAt,
        imageUrl,
        optimisticVersion,
        createdAt,
        updatedAt,
        currentVersionBody,
        branches,
        null,
        null);
  }
}
