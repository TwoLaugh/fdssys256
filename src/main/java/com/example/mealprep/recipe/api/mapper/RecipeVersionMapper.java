package com.example.mealprep.recipe.api.mapper;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.MethodStepDto;
import com.example.mealprep.recipe.api.dto.NutritionPerServingDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        appliedSubs,
        entity.getEmbedding(),
        toNutritionPerServing(entity.getNutritionPerServing()));
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
        appliedSubs,
        baseVersion.getEmbedding(),
        toNutritionPerServing(baseVersion.getNutritionPerServing()));
  }

  /**
   * Re-shape the persisted {@code nutrition_per_serving} JSONB (the nutrition module's {@code
   * RecipeNutritionResultDto} written verbatim by the recipe-01g writer bridge) into the contract's
   * {@link NutritionPerServingDto}. Returns {@code null} when nothing has been persisted yet or the
   * stored result's own status is still {@code pending} (no ingredient resolved → no meaningful
   * figures); calculated and partial results both surface figures per the ticket.
   */
  static NutritionPerServingDto toNutritionPerServing(JsonNode persisted) {
    if (persisted == null || persisted.isNull() || !persisted.isObject()) {
      return null;
    }
    String status = persisted.path("nutritionStatus").asText("");
    if ("pending".equalsIgnoreCase(status)) {
      return null;
    }
    Map<String, BigDecimal> micros = new LinkedHashMap<>();
    JsonNode microsNode = persisted.path("microsPerServing");
    if (microsNode.isObject()) {
      microsNode
          .fields()
          .forEachRemaining(
              entry -> {
                if (entry.getValue().isNumber()) {
                  micros.put(entry.getKey(), entry.getValue().decimalValue());
                }
              });
    }
    Map<String, String> microSources = new LinkedHashMap<>();
    JsonNode srcNode = persisted.path("microSources");
    if (srcNode.isObject()) {
      srcNode
          .fields()
          .forEachRemaining(
              e -> {
                if (e.getValue().isTextual()) {
                  microSources.put(e.getKey(), e.getValue().asText());
                }
              });
    }
    Map<String, BigDecimal> microConfidence = new LinkedHashMap<>();
    JsonNode confNode = persisted.path("microConfidence");
    if (confNode.isObject()) {
      confNode
          .fields()
          .forEachRemaining(
              e -> {
                if (e.getValue().isNumber()) {
                  microConfidence.put(e.getKey(), e.getValue().decimalValue());
                }
              });
    }
    return new NutritionPerServingDto(
        persisted.path("caloriesPerServing").asInt(0),
        decimalOrZero(persisted.path("proteinPerServingG")),
        decimalOrZero(persisted.path("carbsPerServingG")),
        decimalOrZero(persisted.path("fatPerServingG")),
        decimalOrZero(persisted.path("fibrePerServingG")),
        micros,
        microSources,
        microConfidence);
  }

  private static BigDecimal decimalOrZero(JsonNode node) {
    return node.isNumber() ? node.decimalValue() : BigDecimal.ZERO;
  }
}
