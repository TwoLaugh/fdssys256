package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.RecipeRatingDto;
import com.example.mealprep.recipe.domain.entity.RecipeRating;
import org.mapstruct.Mapper;

/** Entity to DTO mapping for {@link RecipeRating} (recipe-02b). */
@Mapper(componentModel = "spring")
public interface RecipeRatingMapper {

  default RecipeRatingDto toDto(RecipeRating entity) {
    if (entity == null) {
      return null;
    }
    return new RecipeRatingDto(
        entity.getId(),
        entity.getRecipeId(),
        entity.getVersionId(),
        entity.getUserId(),
        entity.getHouseholdId(),
        entity.getSlotId(),
        entity.getTaste(),
        entity.getEffortWorthIt(),
        entity.getPortionFit(),
        entity.getRepeatValue(),
        entity.getAggregate(),
        entity.getNotes(),
        entity.getTraceId(),
        entity.getOptimisticVersion(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
