package com.example.mealprep.nutrition.domain.service.internal;

import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.RecipeIngredientLineDto;
import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.domain.service.NutritionCalculationService;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.event.RecipeUpdatedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for recipe-version write events {@code AFTER_COMMIT} and triggers a per-serving nutrition
 * recalc. The computed {@link RecipeNutritionResultDto} is handed to the {@link
 * RecipeNutritionWriter} SPI; when the Noop fallback is wired the result is logged + dropped,
 * otherwise it is persisted back to the recipe version (recipe-01f impl's concern).
 *
 * <p>LLD divergence note — the LLD's single {@code RecipeEvolvedEvent} is the recipe module's
 * post-refactor {@code RecipeUpdatedEvent} (LLD recipe.md line 695); the corresponding {@code
 * RecipeAdaptedEvent} ships with recipe-01f. Until that event type exists on the classpath, only
 * the {@code RecipeUpdatedEvent} listener is wired here. When recipe-01f adds the second event, a
 * sibling listener {@code @ConditionalOnClass} can be added without touching this class.
 *
 * <p>Both events MAY fire for a single pipeline write (recipe-01f publishes both). The calc is
 * deterministic and the SPI write is idempotent, so a double-run produces the same JSONB twice —
 * benign no-op on the second pass.
 *
 * <p>Failure modes — none of these re-throw: the listener is {@code AFTER_COMMIT}, there is no
 * upstream tx to roll back. A missing version, a calc failure, or a SPI failure all log + return.
 */
@Component
public class RecipeEventListener {

  private static final Logger log = LoggerFactory.getLogger(RecipeEventListener.class);

  private final NutritionCalculationService calculationService;
  private final RecipeNutritionWriter writer;
  private final RecipeQueryService recipeQueryService;

  public RecipeEventListener(
      NutritionCalculationService calculationService,
      RecipeNutritionWriter writer,
      RecipeQueryService recipeQueryService) {
    this.calculationService = calculationService;
    this.writer = writer;
    this.recipeQueryService = recipeQueryService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(
      propagation = Propagation.REQUIRES_NEW) // Spring rule: a @TransactionalEventListener
  // method must use REQUIRES_NEW or NOT_SUPPORTED if also @Transactional (default REQUIRED is
  // rejected at context-load). We need a fresh tx here so JPA reads + the SPI write back via
  // RecipeNutritionWriter both have an active tx.
  public void onRecipeUpdated(RecipeUpdatedEvent event) {
    log.debug(
        "RecipeEventListener.onRecipeUpdated recipeId={} versionId={} branchId={} versionNumber={}",
        event.recipeId(),
        event.newVersionId(),
        event.branchId(),
        event.newVersionNumber());
    recalcByVersionId(event.recipeId(), event.newVersionId());
  }

  /**
   * Fetch the version body, build a {@link CalculateRecipeNutritionRequest}, run the recalc, and
   * write the result back via the SPI. Errors are logged + swallowed — see class-Javadoc.
   */
  private void recalcByVersionId(UUID recipeId, UUID versionId) {
    RecipeVersionDto version;
    try {
      version = recipeQueryService.getVersionWithSubstitutions(recipeId, versionId);
    } catch (RuntimeException ex) {
      log.warn(
          "RecipeEventListener: version lookup failed — recipeId={} versionId={} cause={}",
          recipeId,
          versionId,
          ex.toString());
      return;
    }
    if (version == null) {
      log.warn(
          "RecipeEventListener: version lookup returned null — recipeId={} versionId={}",
          recipeId,
          versionId);
      return;
    }

    List<RecipeIngredientLineDto> lines = mapLines(version.ingredients());
    if (lines.isEmpty()) {
      log.info(
          "RecipeEventListener: version has zero ingredients — recipeId={} versionId={}; skipping"
              + " recalc",
          recipeId,
          versionId);
      return;
    }

    int servings = resolveServings(version);

    RecipeNutritionResultDto result;
    try {
      result =
          calculationService.recalculateForEvolvedRecipe(
              new CalculateRecipeNutritionRequest(recipeId, lines, servings));
    } catch (RuntimeException ex) {
      log.error(
          "RecipeEventListener: recalc failed — recipeId={} versionId={}", recipeId, versionId, ex);
      return;
    }

    try {
      writer.writeNutritionPerServing(versionId, result);
    } catch (RuntimeException ex) {
      log.error(
          "RecipeEventListener: SPI write failed — recipeId={} versionId={} status={}",
          recipeId,
          versionId,
          result.nutritionStatus(),
          ex);
    }
  }

  /**
   * Servings is not on the current {@link RecipeVersionDto} surface (recipe-01a's metadata doesn't
   * carry it). Until that data lands on the version DTO, the listener path uses servings=1 — the
   * per-serving result is then the sum of ingredient contributions, which matches the LLD's "totals
   * == per-serving when servings=1" edge case. The manual-recalc REST endpoint allows callers to
   * supply servings via the request body for accurate non-1 cases until the recipe surface exposes
   * it.
   */
  private int resolveServings(RecipeVersionDto version) {
    // metadata may carry servings in a later recipe ticket; today the field doesn't exist.
    return 1;
  }

  private static List<RecipeIngredientLineDto> mapLines(List<IngredientDto> ingredients) {
    if (ingredients == null || ingredients.isEmpty()) {
      return List.of();
    }
    List<RecipeIngredientLineDto> out = new ArrayList<>(ingredients.size());
    for (IngredientDto in : ingredients) {
      out.add(
          new RecipeIngredientLineDto(
              in.displayName(), in.ingredientMappingKey(), in.quantity(), in.unit(), null, null));
    }
    return out;
  }
}
