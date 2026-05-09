package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import org.springframework.stereotype.Component;

/**
 * Entity ↔ DTO mapping for {@link RecipeVersion}. Caller (service) is responsible for forcing
 * lazy-load of {@code ingredients} / {@code methodSteps} / {@code metadata} / {@code tags} inside a
 * transaction before invoking {@code toDto}.
 */
@Component
public class RecipeVersionMapper {

  private final IngredientMapper ingredientMapper;
  private final MethodStepMapper methodStepMapper;
  private final RecipeMetadataMapper metadataMapper;
  private final RecipeTagsMapper tagsMapper;

  public RecipeVersionMapper(
      IngredientMapper ingredientMapper,
      MethodStepMapper methodStepMapper,
      RecipeMetadataMapper metadataMapper,
      RecipeTagsMapper tagsMapper) {
    this.ingredientMapper = ingredientMapper;
    this.methodStepMapper = methodStepMapper;
    this.metadataMapper = metadataMapper;
    this.tagsMapper = tagsMapper;
  }

  public RecipeVersionDto toDto(RecipeVersion entity) {
    if (entity == null) {
      return null;
    }
    return new RecipeVersionDto(
        entity.getId(),
        entity.getBranch() != null ? entity.getBranch().getId() : null,
        entity.getVersionNumber(),
        entity.getParentVersionId(),
        entity.getTrigger(),
        entity.getChangeReason(),
        entity.getEmbeddingStatus(),
        entity.getCreatedAt(),
        entity.getCreatedByActor(),
        entity.getAdapterTraceId(),
        ingredientMapper.toDtoList(entity.getIngredients()),
        methodStepMapper.toDtoList(entity.getMethodSteps()),
        metadataMapper.toDto(entity.getMetadata()),
        tagsMapper.toDto(entity.getTags()));
  }
}
