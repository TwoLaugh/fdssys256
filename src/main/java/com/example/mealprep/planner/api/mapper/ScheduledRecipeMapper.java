package com.example.mealprep.planner.api.mapper;

import com.example.mealprep.planner.api.dto.ScheduledRecipeDto;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import org.mapstruct.Mapper;

/**
 * Maps {@link ScheduledRecipe} → {@link ScheduledRecipeDto}. Straight field copy; the slot
 * relationship is not echoed back in the DTO (the DTO is reached through {@link MealSlotMapper}).
 */
@Mapper(componentModel = "spring")
public abstract class ScheduledRecipeMapper {

  public ScheduledRecipeDto toDto(ScheduledRecipe entity) {
    if (entity == null) {
      return null;
    }
    return new ScheduledRecipeDto(
        entity.getId(),
        entity.getRecipeId(),
        entity.getRecipeVersionId(),
        entity.getRecipeBranchId(),
        entity.getServings(),
        entity.getBatchCookSessionId(),
        entity.getAugmentationNotes(),
        entity.getAugmentationSource(),
        entity.isPhase2Addition());
  }
}
