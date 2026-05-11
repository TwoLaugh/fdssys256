package com.example.mealprep.nutrition.api.mapper;

import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import org.mapstruct.Mapper;

/** Entity ↔ DTO mapping for {@link IngredientMapping}. */
@Mapper(componentModel = "spring")
public interface IngredientMappingMapper {

  default IngredientNutritionDto toDto(IngredientMapping entity) {
    if (entity == null) {
      return null;
    }
    return new IngredientNutritionDto(
        entity.getSearchTerm(),
        entity.getSource(),
        entity.getExternalId(),
        entity.getNutritionPer100g(),
        entity.getDefaultPieceGrams(),
        entity.getConfidence(),
        entity.isNeedsReview(),
        entity.getLastVerifiedAt(),
        entity.getVersion());
  }
}
