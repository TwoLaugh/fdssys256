package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.dto.RecipeDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.Recipe;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/** Entity ↔ DTO mapping for {@link Recipe}. */
@Component
public class RecipeMapper {

  /**
   * Map the recipe + its current version body and branches list. {@code currentVersionBody} may be
   * null when the caller hasn't loaded the version yet; {@code branches} is required-non-null on
   * the wire (defaults to an empty list).
   */
  public RecipeDto toDto(
      Recipe entity, RecipeVersionDto currentVersionBody, List<RecipeBranchDto> branches) {
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
        currentVersionBody,
        branches != null ? branches : Collections.emptyList());
  }

  /**
   * Convenience overload that defaults {@code branches} to an empty list. Retained so existing unit
   * tests / call sites that don't yet have a branches list can map without breaking.
   */
  public RecipeDto toDto(Recipe entity, RecipeVersionDto currentVersionBody) {
    return toDto(entity, currentVersionBody, Collections.emptyList());
  }
}
