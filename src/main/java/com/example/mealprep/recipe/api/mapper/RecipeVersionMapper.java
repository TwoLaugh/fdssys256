package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.MethodStepDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    return toDto(entity, null);
  }

  /**
   * Overlay-aware variant used by the {@code /with-substitutions} endpoint (recipe-01e). On every
   * other read path, {@code appliedSubstitutionIds} is {@code null}.
   */
  public RecipeVersionDto toDto(RecipeVersion entity, List<UUID> appliedSubs) {
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
        tagsMapper.toDto(entity.getTags()),
        appliedSubs);
  }

  /**
   * Build a {@link RecipeVersionDto} carrying the persisted version's metadata + the supplied
   * (already-overlaid) body. Used by {@code getVersionWithSubstitutions} so the returned DTO
   * carries the base version's id while the ingredients / method reflect the overlay applied by
   * {@code SubstitutionOverlayApplier}.
   */
  public RecipeVersionDto toOverlayDto(
      RecipeVersion baseVersion,
      List<CreateIngredientRequest> overlaidIngredients,
      List<CreateMethodStepRequest> overlaidMethod,
      List<UUID> appliedSubs) {
    if (baseVersion == null) {
      return null;
    }
    List<IngredientDto> ingredients = new ArrayList<>();
    if (overlaidIngredients != null) {
      for (CreateIngredientRequest req : overlaidIngredients) {
        ingredients.add(
            new IngredientDto(
                null,
                req.lineOrder(),
                req.ingredientMappingKey(),
                req.displayName(),
                req.quantity(),
                req.unit(),
                req.preparation(),
                Boolean.TRUE.equals(req.optional()),
                false,
                null));
      }
    }
    List<MethodStepDto> steps = new ArrayList<>();
    if (overlaidMethod != null) {
      for (CreateMethodStepRequest req : overlaidMethod) {
        steps.add(
            new MethodStepDto(null, req.stepNumber(), req.instruction(), req.durationMinutes()));
      }
    }
    return new RecipeVersionDto(
        baseVersion.getId(),
        baseVersion.getBranch() != null ? baseVersion.getBranch().getId() : null,
        baseVersion.getVersionNumber(),
        baseVersion.getParentVersionId(),
        baseVersion.getTrigger(),
        baseVersion.getChangeReason(),
        baseVersion.getEmbeddingStatus(),
        baseVersion.getCreatedAt(),
        baseVersion.getCreatedByActor(),
        baseVersion.getAdapterTraceId(),
        ingredients,
        steps,
        metadataMapper.toDto(baseVersion.getMetadata()),
        tagsMapper.toDto(baseVersion.getTags()),
        appliedSubs);
  }
}
