package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.api.dto.CalculateRecipeNutritionRequest;
import com.example.mealprep.nutrition.api.dto.RecipeNutritionResultDto;
import com.example.mealprep.nutrition.domain.service.NutritionCalculationService;
import com.example.mealprep.nutrition.domain.service.internal.RecipeEventListener;
import com.example.mealprep.nutrition.spi.RecipeNutritionWriter;
import com.example.mealprep.recipe.api.dto.IngredientDto;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.event.RecipeUpdatedEvent;
import com.example.mealprep.recipe.exception.RecipeVersionNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link RecipeEventListener}. Covers happy-path (version lookup → calc → SPI write),
 * version-missing race (logs + returns without throwing), calc failure (logs + returns), and SPI
 * failure (logs + returns).
 */
@ExtendWith(MockitoExtension.class)
class RecipeEventListenerTest {

  @Mock private NutritionCalculationService calculationService;
  @Mock private RecipeNutritionWriter writer;
  @Mock private RecipeQueryService recipeQueryService;

  private RecipeEventListener listener;

  @BeforeEach
  void setUp() {
    listener = new RecipeEventListener(calculationService, writer, recipeQueryService);
  }

  private RecipeUpdatedEvent eventFor(UUID recipeId, UUID versionId) {
    return new RecipeUpdatedEvent(
        recipeId,
        UUID.randomUUID(),
        versionId,
        2,
        VersionTrigger.MANUAL_EDIT,
        UUID.randomUUID(),
        Instant.parse("2026-05-09T10:00:00Z"));
  }

  private RecipeVersionDto versionDtoWithIngredients(UUID versionId) {
    IngredientDto ing =
        new IngredientDto(
            UUID.randomUUID(),
            0,
            "chicken breast",
            "Chicken breast",
            BigDecimal.valueOf(200),
            "g",
            null,
            false,
            false,
            BigDecimal.valueOf(0.95));
    return new RecipeVersionDto(
        versionId,
        UUID.randomUUID(),
        2,
        null,
        VersionTrigger.MANUAL_EDIT,
        null,
        null,
        Instant.parse("2026-05-09T10:00:00Z"),
        "user:abc",
        null,
        List.of(ing),
        List.of(),
        null,
        null,
        null);
  }

  private RecipeNutritionResultDto sampleResult(UUID recipeId) {
    return new RecipeNutritionResultDto(
        recipeId,
        165,
        new BigDecimal("31.00"),
        new BigDecimal("0.00"),
        new BigDecimal("3.60"),
        new BigDecimal("0.00"),
        Map.of(),
        "calculated",
        List.of());
  }

  @Test
  void onRecipeUpdated_happyPath_runsCalc_andWritesViaSpi() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(recipeQueryService.getVersionWithSubstitutions(recipeId, versionId))
        .thenReturn(versionDtoWithIngredients(versionId));
    when(calculationService.recalculateForEvolvedRecipe(any())).thenReturn(sampleResult(recipeId));

    listener.onRecipeUpdated(eventFor(recipeId, versionId));

    ArgumentCaptor<CalculateRecipeNutritionRequest> reqCaptor =
        ArgumentCaptor.forClass(CalculateRecipeNutritionRequest.class);
    verify(calculationService).recalculateForEvolvedRecipe(reqCaptor.capture());
    assertThat(reqCaptor.getValue().recipeId()).isEqualTo(recipeId);
    assertThat(reqCaptor.getValue().ingredients()).hasSize(1);
    verify(writer).writeNutritionPerServing(eq(versionId), any());
  }

  @Test
  void onRecipeUpdated_versionMissing_doesNotInvokeCalc_andDoesNotThrow() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(recipeQueryService.getVersionWithSubstitutions(recipeId, versionId))
        .thenThrow(new RecipeVersionNotFoundException(versionId));

    assertThatCode(() -> listener.onRecipeUpdated(eventFor(recipeId, versionId)))
        .doesNotThrowAnyException();

    verify(calculationService, never()).recalculateForEvolvedRecipe(any());
    verify(writer, never()).writeNutritionPerServing(any(), any());
  }

  @Test
  void onRecipeUpdated_calcThrows_writerNotInvoked_andNoRethrow() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(recipeQueryService.getVersionWithSubstitutions(recipeId, versionId))
        .thenReturn(versionDtoWithIngredients(versionId));
    when(calculationService.recalculateForEvolvedRecipe(any()))
        .thenThrow(new RuntimeException("boom"));

    assertThatCode(() -> listener.onRecipeUpdated(eventFor(recipeId, versionId)))
        .doesNotThrowAnyException();

    verify(writer, never()).writeNutritionPerServing(any(), any());
  }

  @Test
  void onRecipeUpdated_spiThrows_doesNotRethrow() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(recipeQueryService.getVersionWithSubstitutions(recipeId, versionId))
        .thenReturn(versionDtoWithIngredients(versionId));
    when(calculationService.recalculateForEvolvedRecipe(any())).thenReturn(sampleResult(recipeId));
    org.mockito.Mockito.doThrow(new RuntimeException("spi failed"))
        .when(writer)
        .writeNutritionPerServing(any(), any());

    assertThatCode(() -> listener.onRecipeUpdated(eventFor(recipeId, versionId)))
        .doesNotThrowAnyException();
  }

  @Test
  void onRecipeUpdated_idempotent_secondDeliveryReRuns() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(recipeQueryService.getVersionWithSubstitutions(recipeId, versionId))
        .thenReturn(versionDtoWithIngredients(versionId));
    when(calculationService.recalculateForEvolvedRecipe(any())).thenReturn(sampleResult(recipeId));

    listener.onRecipeUpdated(eventFor(recipeId, versionId));
    listener.onRecipeUpdated(eventFor(recipeId, versionId));

    verify(calculationService, times(2)).recalculateForEvolvedRecipe(any());
    verify(writer, times(2)).writeNutritionPerServing(eq(versionId), any());
  }
}
