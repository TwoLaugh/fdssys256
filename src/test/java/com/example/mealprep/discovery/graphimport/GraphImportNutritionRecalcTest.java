package com.example.mealprep.discovery.graphimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.domain.service.internal.graphimport.GraphImportNutritionRecalc;
import com.example.mealprep.discovery.domain.service.internal.graphimport.GraphImportNutritionRecalc.GraphImportNutritionRecalcException;
import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.api.dto.UnmappedIngredientDto;
import com.example.mealprep.nutrition.domain.service.NutritionCalculationService;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.recipe.spi.ImportedRecipeData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * G07 unit gates (ticket {@code G07-nutrition-recompute.md} acceptance criteria): a non-{@code
 * calculated} result or a zero-kcal {@code calculated} result FAILS the dish with the writer never
 * invoked; servings flow through from artifact metadata (never hardcoded 1); grams flow verbatim
 * from the artifact's exact-gram quantities; the defence-in-depth guard rejects non-"g" lines
 * before the calc is even consulted.
 */
class GraphImportNutritionRecalcTest {

  private final NutritionCalculationService calc = mock(NutritionCalculationService.class);
  private final RecipeNutritionWriter writer = mock(RecipeNutritionWriter.class);
  private final GraphImportNutritionRecalc recalc = new GraphImportNutritionRecalc(calc, writer);

  private final UUID recipeId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();

  private static ImportedRecipeData.ImportedIngredient line(
      int order, String key, String quantity, String unit) {
    return new ImportedRecipeData.ImportedIngredient(
        order, key, key, new BigDecimal(quantity), unit, null, false);
  }

  private static ImportedRecipeData dish(
      Integer servings, ImportedRecipeData.ImportedIngredient... lines) {
    return new ImportedRecipeData(
        "graph:camp-test",
        null,
        "f".repeat(64),
        "Test dal",
        null,
        List.of(lines),
        List.of(new ImportedRecipeData.ImportedMethodStep(1, "Simmer.", 10)),
        new ImportedRecipeData.ImportedRecipeMetadata(
            servings, 10, 12, 22, List.of("hob"), null, null, null, null, List.of("dinner")),
        new ImportedRecipeData.ImportedRecipeTags("lentil", "simmer", null, null, null),
        "graph@1234abc+c@0123456789abcdef",
        null,
        UUID.randomUUID(),
        UUID.randomUUID());
  }

  private RecipeNutritionResultDto result(String status, int kcal) {
    return new RecipeNutritionResultDto(
        recipeId,
        kcal,
        new BigDecimal("20.00"),
        new BigDecimal("30.00"),
        new BigDecimal("10.00"),
        new BigDecimal("5.00"),
        Map.of("iron_mg", new BigDecimal("3.00"), "saturated_fat_g", new BigDecimal("1.20")),
        status,
        status.equals("calculated")
            ? List.of()
            : List.of(new UnmappedIngredientDto("mystery", "not-in-cache", BigDecimal.ZERO)));
  }

  @Test
  void happyPath_writesResult_andReportsCalculatedWithMicroCount() {
    when(calc.recalculateForEvolvedRecipe(any())).thenReturn(result("calculated", 450));

    GraphImportNutritionRecalc.Outcome outcome =
        recalc.recompute(
            dish(1, line(1, "rice", "180", "g"), line(2, "broccoli", "120", "g")),
            recipeId,
            versionId);

    assertThat(outcome.nutritionStatus()).isEqualTo("CALCULATED");
    assertThat(outcome.microCount()).isEqualTo(2);
    ArgumentCaptor<RecipeNutritionResultDto> written =
        ArgumentCaptor.forClass(RecipeNutritionResultDto.class);
    verify(writer)
        .writeNutritionPerServing(org.mockito.ArgumentMatchers.eq(versionId), written.capture());
    assertThat(written.getValue().caloriesPerServing()).isEqualTo(450);
  }

  @Test
  void gramsFlowVerbatim_fromArtifactQuantities_neverNull() {
    when(calc.recalculateForEvolvedRecipe(any())).thenReturn(result("calculated", 450));

    recalc.recompute(
        dish(1, line(1, "rice", "180", "g"), line(2, "broccoli", "120.5", "g")),
        recipeId,
        versionId);

    ArgumentCaptor<CalculateRecipeNutritionRequest> req =
        ArgumentCaptor.forClass(CalculateRecipeNutritionRequest.class);
    verify(calc).recalculateForEvolvedRecipe(req.capture());
    assertThat(req.getValue().recipeId()).isEqualTo(recipeId);
    assertThat(req.getValue().ingredients()).hasSize(2);
    // The finding-3 regression at unit level: gramsEstimate == the artifact's exact grams.
    assertThat(req.getValue().ingredients().get(0).gramsEstimate())
        .isEqualByComparingTo(new BigDecimal("180"));
    assertThat(req.getValue().ingredients().get(1).gramsEstimate())
        .isEqualByComparingTo(new BigDecimal("120.5"));
  }

  @Test
  void servingsPassedThroughFromArtifactMetadata_notHardcodedOne() {
    when(calc.recalculateForEvolvedRecipe(any())).thenReturn(result("calculated", 450));

    recalc.recompute(dish(3, line(1, "rice", "180", "g")), recipeId, versionId);

    ArgumentCaptor<CalculateRecipeNutritionRequest> req =
        ArgumentCaptor.forClass(CalculateRecipeNutritionRequest.class);
    verify(calc).recalculateForEvolvedRecipe(req.capture());
    assertThat(req.getValue().servings()).isEqualTo(3);
  }

  @Test
  void partialResult_failsDish_writerNotInvoked() {
    when(calc.recalculateForEvolvedRecipe(any())).thenReturn(result("partial", 450));

    assertThatThrownBy(
            () -> recalc.recompute(dish(1, line(1, "rice", "180", "g")), recipeId, versionId))
        .isInstanceOf(GraphImportNutritionRecalcException.class)
        .hasMessageContaining("partial")
        .hasMessageContaining("mystery"); // unmapped lines named for the report

    verify(writer, never()).writeNutritionPerServing(any(), any());
  }

  @Test
  void zeroKcalCalculatedResult_failsDish_writerNotInvoked() {
    when(calc.recalculateForEvolvedRecipe(any())).thenReturn(result("calculated", 0));

    assertThatThrownBy(
            () -> recalc.recompute(dish(1, line(1, "rice", "180", "g")), recipeId, versionId))
        .isInstanceOf(GraphImportNutritionRecalcException.class)
        .hasMessageContaining("0 kcal");

    verify(writer, never()).writeNutritionPerServing(any(), any());
  }

  @Test
  void nonGramUnit_guardRejects_beforeCalcConsulted() {
    assertThatThrownBy(
            () -> recalc.recompute(dish(1, line(1, "rice", "1", "cup")), recipeId, versionId))
        .isInstanceOf(GraphImportNutritionRecalcException.class)
        .hasMessageContaining("exact-grams");
    assertThatThrownBy(
            () -> recalc.recompute(dish(1, line(1, "rice", "0", "g")), recipeId, versionId))
        .isInstanceOf(GraphImportNutritionRecalcException.class);
    verifyNoInteractions(calc, writer);
  }

  @Test
  void missingServings_guardRejects() {
    assertThatThrownBy(
            () -> recalc.recompute(dish(null, line(1, "rice", "180", "g")), recipeId, versionId))
        .isInstanceOf(GraphImportNutritionRecalcException.class)
        .hasMessageContaining("servings");
    verifyNoInteractions(calc, writer);
  }
}
