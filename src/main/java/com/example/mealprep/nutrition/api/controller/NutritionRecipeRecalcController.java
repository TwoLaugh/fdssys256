package com.example.mealprep.nutrition.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.RecalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.RecipeIngredientLineDto;
import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.domain.service.NutritionCalculationService;
import com.example.mealprep.nutrition.exception.RecipeNutritionWriteFailedException;
import com.example.mealprep.nutrition.exception.RecipeVersionLookupFailedException;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.nutrition.spi.internal.NoopRecipeNutritionWriterConfiguration;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manual recalc seam — {@code POST
 * /api/v1/nutrition/recipes/{recipeId}/versions/{versionId}/recalculate}. Authenticated callers
 * (typically admin / feedback flows) can force a per-serving nutrition recompute on an existing
 * version without producing a new recipe version.
 *
 * <p>The endpoint reads the version through {@link RecipeQueryService} (cross-module read), runs
 * the calc, and pipes the result through {@link RecipeNutritionWriter} so the persistence step
 * still flows through the SPI seam. When the Noop fallback is the wired bean, the response carries
 * a {@code Warning} header (RFC 7234 §5.5) so callers know the recalc completed but the recipe
 * version is unchanged.
 */
@RestController
@RequestMapping("/api/v1/nutrition/recipes")
@Tag(name = "Nutrition")
public class NutritionRecipeRecalcController {

  private static final Logger log = LoggerFactory.getLogger(NutritionRecipeRecalcController.class);

  private static final String NOOP_WRITER_CLASS =
      NoopRecipeNutritionWriterConfiguration.class.getName() + "$NoopRecipeNutritionWriterImpl";

  private final NutritionCalculationService calculationService;
  private final RecipeNutritionWriter writer;
  private final RecipeQueryService recipeQueryService;
  private final CurrentUserResolver currentUserResolver;

  public NutritionRecipeRecalcController(
      NutritionCalculationService calculationService,
      RecipeNutritionWriter writer,
      RecipeQueryService recipeQueryService,
      CurrentUserResolver currentUserResolver) {
    this.calculationService = calculationService;
    this.writer = writer;
    this.recipeQueryService = recipeQueryService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      path = "/{recipeId}/versions/{versionId}/recalculate",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Manually recalculate per-serving nutrition for a recipe version; result written back"
              + " via the recipe-side SPI.")
  public ResponseEntity<RecipeNutritionResultDto> recalculate(
      @PathVariable UUID recipeId,
      @PathVariable UUID versionId,
      @Valid @RequestBody RecalculateRecipeNutritionRequest body) {
    UUID actorUserId = requireCurrentUserId();
    log.info(
        "manual recalc requested recipeId={} versionId={} branchId={} versionNumber={}"
            + " actorUserId={}",
        recipeId,
        versionId,
        body.branchId(),
        body.versionNumber(),
        actorUserId);

    RecipeVersionDto version;
    try {
      version = recipeQueryService.getVersionWithSubstitutions(recipeId, versionId);
    } catch (RuntimeException ex) {
      throw new RecipeVersionLookupFailedException(recipeId, versionId, ex);
    }
    if (version == null) {
      throw new RecipeVersionLookupFailedException(recipeId, versionId);
    }

    List<RecipeIngredientLineDto> lines = mapLines(version.ingredients());
    if (lines.isEmpty()) {
      // Degenerate version — no ingredients to compute. Return a pending result without invoking
      // the calc (the calc's @Size(min=1) constraint would otherwise refuse the call).
      RecipeNutritionResultDto emptyResult =
          new RecipeNutritionResultDto(
              recipeId,
              0,
              java.math.BigDecimal.ZERO,
              java.math.BigDecimal.ZERO,
              java.math.BigDecimal.ZERO,
              java.math.BigDecimal.ZERO,
              java.util.Map.of(),
              "pending",
              List.of());
      log.info(
          "manual recalc: version has zero ingredients — returning pending recipeId={} versionId={}",
          recipeId,
          versionId);
      return ResponseEntity.ok(emptyResult);
    }
    int servings = 1; // metadata.servings not on RecipeVersionDto surface yet — see ticket §25.
    RecipeNutritionResultDto result =
        calculationService.recalculateForEvolvedRecipe(
            new CalculateRecipeNutritionRequest(recipeId, lines, servings));

    boolean noopWired = writer.getClass().getName().equals(NOOP_WRITER_CLASS);
    try {
      writer.writeNutritionPerServing(versionId, result);
    } catch (RecipeNutritionWriteFailedException ex) {
      // The SPI impl raised a domain-level write failure — surface as 422.
      throw ex;
    } catch (RuntimeException ex) {
      // Any other SPI failure becomes a 422 with a wrapping message.
      throw new RecipeNutritionWriteFailedException(versionId, ex.getMessage(), ex);
    }

    ResponseEntity.BodyBuilder ok = ResponseEntity.status(HttpStatus.OK);
    if (noopWired) {
      // RFC 7234 §5.5 Warning header (code 100 = "Response is stale" — repurposed here per ticket
      // §28 as a clear "the SPI is not wired" signal).
      ok =
          (ResponseEntity.BodyBuilder)
              ok.header(
                  "Warning",
                  "100 - \"recipe-01f impl not yet wired; nutrition computed but not persisted\"");
    }
    return ok.body(result);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
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
