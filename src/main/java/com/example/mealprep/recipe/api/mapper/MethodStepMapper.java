package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.MethodStepDto;
import com.example.mealprep.recipe.domain.entity.RecipeMethodStep;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Entity → DTO mapping for {@link RecipeMethodStep}. */
@Component
public class MethodStepMapper {

  public MethodStepDto toDto(RecipeMethodStep entity) {
    if (entity == null) {
      return null;
    }
    return new MethodStepDto(
        entity.getId(),
        entity.getStepNumber(),
        entity.getInstruction(),
        entity.getDurationMinutes());
  }

  /** Returns method steps sorted by {@code stepNumber} ascending. */
  public List<MethodStepDto> toDtoList(List<RecipeMethodStep> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    List<RecipeMethodStep> sorted = new ArrayList<>(source);
    sorted.sort(Comparator.comparingInt(RecipeMethodStep::getStepNumber));
    List<MethodStepDto> result = new ArrayList<>(sorted.size());
    for (RecipeMethodStep entity : sorted) {
      result.add(toDto(entity));
    }
    return result;
  }
}
