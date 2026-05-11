package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.MethodOverlayLineDto;
import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.SubstitutedItemDto;
import com.example.mealprep.recipe.domain.entity.MethodOverlayLine;
import com.example.mealprep.recipe.domain.entity.RecipeSubstitution;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Entity to DTO mapping for {@link RecipeSubstitution}. */
@Component
public class RecipeSubstitutionMapper {

  public RecipeSubstitutionDto toDto(RecipeSubstitution entity) {
    if (entity == null) {
      return null;
    }
    return new RecipeSubstitutionDto(
        entity.getId(),
        entity.getRecipeId(),
        entity.getVersionId(),
        entity.getBranchId(),
        new SubstitutedItemDto(
            entity.getOriginalMappingKey(), entity.getOriginalQuantity(), entity.getOriginalUnit()),
        new SubstitutedItemDto(
            entity.getSubstituteMappingKey(),
            entity.getSubstituteQuantity(),
            entity.getSubstituteUnit()),
        entity.getReason(),
        entity.getConstraintRef(),
        toOverlayDtoList(entity.getMethodOverlay()),
        entity.getNotes(),
        entity.isTemporary(),
        entity.getApplicationCount(),
        entity.getLastAppliedAt(),
        entity.getState(),
        entity.getPromotedToVersionId(),
        entity.getCreatedAt(),
        entity.getCreatedByActor(),
        entity.getAdapterTraceId(),
        entity.getVersion());
  }

  public List<RecipeSubstitutionDto> toDtoList(List<RecipeSubstitution> source) {
    if (source == null || source.isEmpty()) {
      return List.of();
    }
    List<RecipeSubstitutionDto> result = new ArrayList<>(source.size());
    for (RecipeSubstitution entity : source) {
      result.add(toDto(entity));
    }
    return result;
  }

  private static List<MethodOverlayLineDto> toOverlayDtoList(List<MethodOverlayLine> source) {
    if (source == null) {
      return null;
    }
    List<MethodOverlayLineDto> result = new ArrayList<>(source.size());
    for (MethodOverlayLine line : source) {
      result.add(new MethodOverlayLineDto(line.step(), line.instruction()));
    }
    return result;
  }
}
