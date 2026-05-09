package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.Recipe;
import org.springframework.stereotype.Component;

/** Entity ↔ DTO mapping for {@link Recipe}. */
@Component
public class RecipeMapper {

  /**
   * Map the recipe + its current version body. {@code currentVersionBody} may be null when the
   * caller hasn't loaded the version yet.
   */
  public RecipeDto toDto(Recipe entity, RecipeVersionDto currentVersionBody) {
    if (entity == null) {
      return null;
    }
    return new RecipeDto(
        entity.getId(),
        entity.getUserId(),
        entity.getCatalogue(),
        entity.getName(),
        entity.getDescription(),
        entity.getCurrentVersion(),
        entity.getCurrentBranchId(),
        entity.getDataQuality(),
        entity.getNutritionStatus(),
        entity.getForkedFromRecipeId(),
        entity.getLastUsedInPlanAt(),
        entity.getArchivedAt(),
        entity.getDeletedAt(),
        entity.getOptimisticVersion(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        currentVersionBody);
  }
}
