package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.RecipeImportDto;
import com.example.mealprep.recipe.domain.entity.RecipeImport;
import org.springframework.stereotype.Component;

/** Entity → DTO mapping for {@link RecipeImport}. */
@Component
public class RecipeImportMapper {

  public RecipeImportDto toDto(RecipeImport entity) {
    if (entity == null) {
      return null;
    }
    return new RecipeImportDto(
        entity.getId(),
        entity.getRecipeId(),
        entity.getSourceType(),
        entity.getSourceUrl(),
        entity.getSourcePayload(),
        entity.getExtractionMethod(),
        entity.getDuplicateOfRecipeId(),
        entity.getImportedAt(),
        entity.getImportedByUserId());
  }
}
