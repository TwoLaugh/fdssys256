package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Entity → DTO mapping for {@link RecipeBranch}. Straight field copy. */
@Component
public class RecipeBranchMapper {

  public RecipeBranchDto toDto(RecipeBranch entity) {
    if (entity == null) {
      return null;
    }
    return new RecipeBranchDto(
        entity.getId(),
        entity.getRecipe() != null ? entity.getRecipe().getId() : null,
        entity.getParentBranchId(),
        entity.getBranchPointVersionId(),
        entity.getName(),
        entity.getLabel(),
        entity.getReason(),
        entity.getCurrentVersion(),
        entity.getDivergenceScore(),
        entity.getCreatedAt(),
        entity.getCreatedByActor(),
        entity.getAdapterTraceId(),
        entity.getVersion());
  }

  /** Returns branches sorted by {@code createdAt} ascending — 'main' is always first. */
  public List<RecipeBranchDto> toDtoList(List<RecipeBranch> source) {
    if (source == null || source.isEmpty()) {
      return Collections.emptyList();
    }
    List<RecipeBranch> sorted = new ArrayList<>(source);
    sorted.sort(Comparator.comparing(RecipeBranch::getCreatedAt));
    List<RecipeBranchDto> result = new ArrayList<>(sorted.size());
    for (RecipeBranch entity : sorted) {
      result.add(toDto(entity));
    }
    return result;
  }
}
