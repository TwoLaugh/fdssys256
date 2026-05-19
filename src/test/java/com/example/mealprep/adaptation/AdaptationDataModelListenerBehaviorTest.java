package com.example.mealprep.adaptation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.domain.service.internal.AdaptationDataModelListener;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.provisions.event.BudgetChangedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Behavioural coverage for {@link AdaptationDataModelListener}'s three listener bodies ({@code
 * onHardConstraintsChanged} / {@code onNutritionTargetsChanged} / {@code onBudgetChanged}). PIT
 * flagged these NO_COVERAGE: the {@code NegateConditionals} mutant on {@code affected.isEmpty()}
 * and the {@code VoidMethodCall} mutant on the {@code enqueue(...)} call.
 *
 * <p>v1 affected-recipe filters are placeholders that return an empty set, so every listener must
 * early-return and NEVER call {@link AdaptationService#enqueueDataModelChangeJobs}. Asserting the
 * collaborator is never invoked fails under the negated conditional (which would then enqueue an
 * empty job) and pins the documented v1 no-op contract. {@code AdaptationService} is a
 * cross-boundary collaborator (interface), so mocking it is allowed per the playbook.
 */
class AdaptationDataModelListenerBehaviorTest {

  private final AdaptationService service = mock(AdaptationService.class);
  private final AdaptationDataModelListener listener = new AdaptationDataModelListener(service);

  @Test
  void hard_constraints_changed_with_no_affected_recipes_does_not_enqueue() {
    listener.onHardConstraintsChanged(
        new HardConstraintsUpdatedEvent(
            UUID.randomUUID(), Set.of("allergens"), UUID.randomUUID(), Instant.now()));
    verify(service, never()).enqueueDataModelChangeJobs(any(DataModelJobRequest.class));
  }

  @Test
  void nutrition_targets_changed_with_no_affected_recipes_does_not_enqueue() {
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
