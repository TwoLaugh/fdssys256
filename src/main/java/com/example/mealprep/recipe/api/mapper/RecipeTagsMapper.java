package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.RecipeTagsDto;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/** Entity → DTO mapping for {@link RecipeTags}. */
@Component
public class RecipeTagsMapper {

  public RecipeTagsDto toDto(RecipeTags entity) {
    if (entity == null) {
      return null;
    }
    return new RecipeTagsDto(
        entity.getProtein(),
        entity.getCookingMethod(),
        entity.getComplexity(),
        copyOrEmpty(entity.getFlavourProfile()),
        copyOrEmpty(entity.getDietaryFlags()));
  }

  private static List<String> copyOrEmpty(List<String> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    return new ArrayList<>(source);
  }
}
