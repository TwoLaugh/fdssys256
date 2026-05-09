package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Entity → DTO mapping for {@link RecipeIngredient}. */
@Component
public class IngredientMapper {

  public IngredientDto toDto(RecipeIngredient entity) {
    if (entity == null) {
      return null;
    }
    return new IngredientDto(
        entity.getId(),
        entity.getLineOrder(),
        entity.getIngredientMappingKey(),
        entity.getDisplayName(),
        entity.getQuantity(),
        entity.getUnit(),
        entity.getPreparation(),
        entity.isOptional(),
        entity.isNeedsReview(),
        entity.getMappingConfidence());
  }

  /** Returns ingredients sorted by {@code lineOrder} ascending. */
  public List<IngredientDto> toDtoList(List<RecipeIngredient> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    List<RecipeIngredient> sorted = new ArrayList<>(source);
    sorted.sort(Comparator.comparingInt(RecipeIngredient::getLineOrder));
    List<IngredientDto> result = new ArrayList<>(sorted.size());
    for (RecipeIngredient entity : sorted) {
      result.add(toDto(entity));
    }
    return result;
  }
}
