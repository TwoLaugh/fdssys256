package com.example.mealprep.adaptation.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.preference.api.dto.FilterResult;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Behavioural coverage for {@link AdaptationImportListener}'s Trigger-1 cost-discipline gate
 * ({@code tickets/adaptation/02b-trigger1-cost-discipline.md}). Lives in the listener's package so
 * it can drive the package-private {@code decideAndEnqueue}. The cross-module query/filter seams
 * are mocked (they are cross-boundary collaborator interfaces — mocking is allowed per the
 * playbook); the persisted-row / real-bean paths are covered end-to-end in {@link
 * AdaptationImportGatingIT}.
 *
 * <p>Pins the origin → action mapping the gate enforces:
 *
 * <ul>
 *   <li>clean USER_VERIFIED (no conflict) → SKIP (never enqueue);
 *   <li>conflicting USER_VERIFIED → exactly one ASYNC job;
 *   <li>IMPORTED / WEB_DISCOVERED / AI_GENERATED → exactly one BATCH job.
 * </ul>
 */
class AdaptationImportListenerBehaviorTest {

  private final AdaptationService service = mock(AdaptationService.class);
  private final RecipeQueryService recipeQueryService = mock(RecipeQueryService.class);
  private final HardConstraintFilterService hardConstraintFilterService =
      mock(HardConstraintFilterService.class);
  private final NutritionQueryService nutritionQueryService = mock(NutritionQueryService.class);
  private final AdaptationImportListener listener =
      new AdaptationImportListener(
          service, recipeQueryService, hardConstraintFilterService, nutritionQueryService);

  @Test
  void cleanUserVerifiedCreate_withNoConflict_skips_andEnqueuesNothing() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    // Recipe has ingredient keys but they pass the user's hard constraints, and no nutrition row
    // exists yet → no conflict → SKIP.
    when(recipeQueryService.findUserRecipeIngredientKeys(userId))
        .thenReturn(Map.of(recipeId, List.of("rice", "chicken")));
    when(hardConstraintFilterService.checkRecipe(eq(userId), eq(recipeId), any()))
        .thenReturn(new FilterResult(true, List.of()));
    when(recipeQueryService.findUserRecipeNutrition(userId)).thenReturn(Map.of());

    Optional<UUID> result =
        listener.decideAndEnqueue(
            event(recipeId, userId, Catalogue.USER, DataQuality.USER_VERIFIED));

    assertThat(result).isEmpty();
    verify(service, never()).enqueueImportJob(any(ImportJobRequest.class));
    verify(service, never()).enqueueImportJob(any(ImportJobRequest.class), any(JobPriority.class));
  }

  @Test
  void cleanUserVerifiedCreate_withNoIngredients_skips() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    // No ingredient keys for the recipe and no nutrition → cannot conflict → SKIP. (Also pins that
    // an empty key list does NOT call the hard-constraint filter.)
    when(recipeQueryService.findUserRecipeIngredientKeys(userId)).thenReturn(Map.of());
    when(recipeQueryService.findUserRecipeNutrition(userId)).thenReturn(Map.of());

    Optional<UUID> result =
        listener.decideAndEnqueue(
            event(recipeId, userId, Catalogue.USER, DataQuality.USER_VERIFIED));

    assertThat(result).isEmpty();
    verify(hardConstraintFilterService, never()).checkRecipe(any(), any(), any());
    verify(service, never()).enqueueImportJob(any(ImportJobRequest.class), any(JobPriority.class));
  }

  @Test
  void userVerifiedCreate_withHardConstraintConflict_enqueuesExactlyOneAsyncJob() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    when(recipeQueryService.findUserRecipeIngredientKeys(userId))
        .thenReturn(Map.of(recipeId, List.of("peanut")));
    when(hardConstraintFilterService.checkRecipe(eq(userId), eq(recipeId), any()))
        .thenReturn(new FilterResult(false, List.of())); // violates → conflict
    UUID enqueued = UUID.randomUUID();
    when(service.enqueueImportJob(any(ImportJobRequest.class), eq(JobPriority.ASYNC)))
        .thenReturn(enqueued);

    Optional<UUID> result =
        listener.decideAndEnqueue(
            event(recipeId, userId, Catalogue.USER, DataQuality.USER_VERIFIED));

    assertThat(result).contains(enqueued);
    verify(service).enqueueImportJob(any(ImportJobRequest.class), eq(JobPriority.ASYNC));
    verify(service, never()).enqueueImportJob(any(ImportJobRequest.class), eq(JobPriority.BATCH));
  }

  @Test
  void userVerifiedCreate_withNutritionConflictOnly_enqueuesAsync() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    JsonNode nut = JsonNodeFactory.instance.objectNode().put("caloriesPerServing", 1500);
    // Hard constraints pass, but nutrition violates → conflict → ASYNC.
    when(recipeQueryService.findUserRecipeIngredientKeys(userId))
        .thenReturn(Map.of(recipeId, List.of("rice")));
    when(hardConstraintFilterService.checkRecipe(eq(userId), eq(recipeId), any()))
        .thenReturn(new FilterResult(true, List.of()));
    when(recipeQueryService.findUserRecipeNutrition(userId)).thenReturn(Map.of(recipeId, nut));
    when(nutritionQueryService.findRecipeIdsViolatingTargets(eq(userId), any()))
        .thenReturn(Set.of(recipeId));
    when(service.enqueueImportJob(any(ImportJobRequest.class), eq(JobPriority.ASYNC)))
        .thenReturn(UUID.randomUUID());

    Optional<UUID> result =
        listener.decideAndEnqueue(
            event(recipeId, userId, Catalogue.USER, DataQuality.USER_VERIFIED));

    assertThat(result).isPresent();
    verify(service).enqueueImportJob(any(ImportJobRequest.class), eq(JobPriority.ASYNC));
  }

  @Test
  void importedCreate_enqueuesExactlyOneBatchJob() {
    assertBatchOrigin(DataQuality.IMPORTED);
  }

  @Test
  void webDiscoveredCreate_enqueuesExactlyOneBatchJob() {
    assertBatchOrigin(DataQuality.WEB_DISCOVERED);
  }

  @Test
  void aiGeneratedCreate_enqueuesExactlyOneBatchJob() {
    assertBatchOrigin(DataQuality.AI_GENERATED);
  }

  private void assertBatchOrigin(DataQuality dataQuality) {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID enqueued = UUID.randomUUID();
    when(service.enqueueImportJob(any(ImportJobRequest.class), eq(JobPriority.BATCH)))
        .thenReturn(enqueued);

    Optional<UUID> result =
        listener.decideAndEnqueue(event(recipeId, userId, Catalogue.SYSTEM, dataQuality));

    assertThat(result).contains(enqueued);
    verify(service).enqueueImportJob(any(ImportJobRequest.class), eq(JobPriority.BATCH));
    verify(service, never()).enqueueImportJob(any(ImportJobRequest.class), eq(JobPriority.ASYNC));
    // Bulk-origin creates do NOT run the single-recipe pre-filter (that is the USER_VERIFIED path).
    verify(recipeQueryService, never()).findUserRecipeIngredientKeys(any());
  }

  private static RecipeCreatedEvent event(
      UUID recipeId, UUID userId, Catalogue catalogue, DataQuality dataQuality) {
    return new RecipeCreatedEvent(
        recipeId, catalogue, userId, dataQuality, UUID.randomUUID(), Instant.now());
  }
}
