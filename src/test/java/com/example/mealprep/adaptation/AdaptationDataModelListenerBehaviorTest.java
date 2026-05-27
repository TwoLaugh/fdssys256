package com.example.mealprep.adaptation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationDataModelListener;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.provisions.event.BudgetChangedEvent;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Behavioural coverage for {@link AdaptationDataModelListener}'s three listener bodies ({@code
 * onHardConstraintsChanged} / {@code onNutritionTargetsChanged} / {@code onBudgetChanged}). PIT
 * flagged these NO_COVERAGE: the {@code NegateConditionals} mutant on {@code affected.isEmpty()}
 * and the {@code VoidMethodCall} mutant on the {@code enqueue(...)} call.
 *
 * <p>Here the cross-module query seams are stubbed to return NOTHING (no recipes / no nutrition),
 * so every filter yields an empty affected set and every listener must early-return and NEVER call
 * {@link AdaptationService#enqueueDataModelChangeJobs}. Asserting the collaborator is never invoked
 * fails under the negated conditional (which would then enqueue an empty job) and pins the
 * early-return contract. The seams are cross-boundary collaborators (interfaces), so mocking them
 * is allowed per the playbook. (The non-empty / real-violation paths are covered end-to-end against
 * real beans in {@code AdaptationDataModelFilterIT}.)
 */
class AdaptationDataModelListenerBehaviorTest {

  private final AdaptationService service = mock(AdaptationService.class);
  private final RecipeQueryService recipeQueryService = mock(RecipeQueryService.class);
  private final HardConstraintFilterService hardConstraintFilterService =
      mock(HardConstraintFilterService.class);
  private final NutritionQueryService nutritionQueryService = mock(NutritionQueryService.class);
  private final AdaptationDataModelListener listener =
      new AdaptationDataModelListener(
          service, recipeQueryService, hardConstraintFilterService, nutritionQueryService);

  @Test
  void hard_constraints_changed_with_no_affected_recipes_does_not_enqueue() {
    // No recipes for the user → filterAffectedHardConstraints short-circuits to an empty set.
    when(recipeQueryService.findUserRecipeIngredientKeys(any(UUID.class))).thenReturn(Map.of());
    listener.onHardConstraintsChanged(
        new HardConstraintsUpdatedEvent(
            UUID.randomUUID(), Set.of("allergens"), UUID.randomUUID(), Instant.now()));
    verify(service, never()).enqueueDataModelChangeJobs(any(DataModelJobRequest.class));
  }

  @Test
  void nutrition_targets_changed_with_no_affected_recipes_does_not_enqueue() {
    // No recipe nutrition for the user → filterAffectedNutrition short-circuits to an empty set.
    when(nutritionQueryService.findRecipeIdsViolatingTargets(any(UUID.class), any()))
        .thenReturn(Set.of());
    listener.onNutritionTargetsChanged(
        new NutritionTargetsChangedEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Set.of("protein_g"),
            UUID.randomUUID(),
            Instant.now()));
    verify(service, never()).enqueueDataModelChangeJobs(any(DataModelJobRequest.class));
  }

  @Test
  void budget_changed_with_no_affected_recipes_does_not_enqueue() {
    listener.onBudgetChanged(
        new BudgetChangedEvent(
            UUID.randomUUID(),
            new BigDecimal("80.00"),
            new BigDecimal("60.00"),
            null,
            UUID.randomUUID(),
            Instant.now()));
    verify(service, never()).enqueueDataModelChangeJobs(any(DataModelJobRequest.class));
  }
}
