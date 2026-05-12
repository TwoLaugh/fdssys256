package com.example.mealprep.recipe.spi.internal;

import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.recipe.domain.entity.NutritionStatus;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * Recipe-side implementation of nutrition-01f's {@code RecipeNutritionWriter} SPI. Bridges the
 * cross-module wire so the nutrition listener's per-serving result lands in the recipe version via
 * {@link RecipeWriteApi#updateNutritionStatus}. Per recipe-01g ticket §{@code
 * RecipeNutritionWriterImpl} (bridge from recipe-01f).
 *
 * <p>Round-5 SPI pattern: declared via {@code @Component @ConditionalOnClass(name = "…")} —
 * string-form, NOT class-literal — so the impl class never tries to resolve the interface at
 * class-load time if nutrition isn't on the classpath. When the {@code @Component} bean registers,
 * nutrition-01f's {@code @ConditionalOnMissingBean(RecipeNutritionWriter.class)} Noop defers
 * automatically.
 *
 * <p>Package-private; the cross-module contract is the {@code nutrition.spi.RecipeNutritionWriter}
 * interface, not this class.
 */
@Component
@ConditionalOnClass(name = "com.example.mealprep.nutrition.spi.RecipeNutritionWriter")
class RecipeNutritionWriterImpl
    implements com.example.mealprep.nutrition.spi.RecipeNutritionWriter {

  private static final Logger log = LoggerFactory.getLogger(RecipeNutritionWriterImpl.class);

  private final RecipeWriteApi writeApi;
  private final ObjectMapper objectMapper;

  RecipeNutritionWriterImpl(RecipeWriteApi writeApi, ObjectMapper objectMapper) {
    this.writeApi = writeApi;
    this.objectMapper = objectMapper;
  }

  @Override
  public void writeNutritionPerServing(UUID versionId, RecipeNutritionResultDto result) {
    log.debug(
        "RecipeNutritionWriterImpl: writing nutrition for version {} (status {}, calories"
            + " {}/serving)",
        versionId,
        result.nutritionStatus(),
        result.caloriesPerServing());
    NutritionStatus status = NutritionStatus.valueOf(result.nutritionStatus().toUpperCase());
    writeApi.updateNutritionStatus(versionId, status, objectMapper.valueToTree(result));
  }
}
