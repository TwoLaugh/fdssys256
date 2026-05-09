package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/** Entity → DTO mapping for {@link RecipeMetadata}. */
@Component
public class RecipeMetadataMapper {

  public RecipeMetadataDto toDto(RecipeMetadata entity) {
    if (entity == null) {
      return null;
    }
    return new RecipeMetadataDto(
        entity.getServings(),
        entity.getPrepTimeMins(),
        entity.getCookTimeMins(),
        entity.getTotalTimeMins(),
        copyOrEmpty(entity.getEquipmentRequired()),
        entity.getFridgeDays(),
        entity.getFreezerWeeks(),
        entity.isPackable(),
        entity.getCuisine(),
        copyOrEmpty(entity.getMealTypes()));
  }

  private static List<String> copyOrEmpty(List<String> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    return new ArrayList<>(source);
  }
}
