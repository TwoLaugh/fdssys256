package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.api.dto.DataModelChangeType;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.provisions.event.BudgetChangedEvent;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Trigger 3 — listens to user data-model change events from peer modules and enqueues per-recipe
 * adaptation jobs via {@link AdaptationService#enqueueDataModelChangeJobs}. Per LLD lines 690-696
 * and ticket §Trigger 3 listeners.
 *
 * <p><b>Critical round-7 rule</b>: every listener method is annotated with both
 * {@code @TransactionalEventListener(AFTER_COMMIT)} AND {@code @Transactional(REQUIRES_NEW)}. The
 * default REQUIRED propagation is rejected at Spring context-load with the round-7 error: {@code
 * "@TransactionalEventListener method must not be annotated with @Transactional unless when
 * declared as REQUIRES_NEW or NOT_SUPPORTED"}. Since each listener body inserts jobs (writes), only
 * REQUIRES_NEW satisfies the rule.
 *
 * <p><b>Cross-module query-service helpers</b> ({@code findRecipesAffectedByPreferenceChange} etc.)
 * are referenced by the LLD but do not exist yet in 01d's dependency surface. The v1 fallback per
 * ticket §33 is to no-op (empty affected set) when filtering helpers are absent — leaves a
 * follow-up note for the relevant module to ship them. Worth user review.
 */
@org.springframework.stereotype.Component
public class AdaptationDataModelListener {

  private static final Logger LOG = LoggerFactory.getLogger(AdaptationDataModelListener.class);

  private final AdaptationService adaptationService;

  public AdaptationDataModelListener(AdaptationService adaptationService) {
    this.adaptationService = adaptationService;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onHardConstraintsChanged(HardConstraintsUpdatedEvent event) {
    Set<UUID> affected = filterAffectedHardConstraints(event);
    if (affected.isEmpty()) {
      LOG.debug(
          "HardConstraintsUpdatedEvent userId={} fields={} — no affected recipes",
          event.userId(),
          event.fieldsChanged());
      return;
    }
    enqueue(event.userId(), DataModelChangeType.HARD_CONSTRAINTS, affected, event.traceId());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onNutritionTargetsChanged(NutritionTargetsChangedEvent event) {
    Set<UUID> affected = filterAffectedNutrition(event);
    if (affected.isEmpty()) {
      LOG.debug(
          "NutritionTargetsChangedEvent userId={} targetsId={} — no affected recipes",
          event.userId(),
          event.targetsId());
      return;
    }
    enqueue(event.userId(), DataModelChangeType.NUTRITION_TARGETS, affected, event.traceId());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onBudgetChanged(BudgetChangedEvent event) {
    Set<UUID> affected = filterAffectedBudget(event);
    if (affected.isEmpty()) {
      LOG.debug(
          "BudgetChangedEvent userId={} newWeeklyTarget={} — no affected recipes",
          event.userId(),
          event.newWeeklyTarget());
      return;
    }
    enqueue(event.userId(), DataModelChangeType.PROVISIONS_BUDGET, affected, event.traceId());
  }

  // --- v1 affected-recipe filters (placeholders) -----------------------------------------------

  /**
   * v1 placeholder. Real impl per LLD calls {@code
   * preferenceQueryService.findRecipesContainingAllergen(userId, allergen)} for each changed
   * allergen field. Returns empty set in 01d — preference module must ship the helper. <b>Follow-up
   * filed.</b>
   */
  private Set<UUID> filterAffectedHardConstraints(HardConstraintsUpdatedEvent event) {
    return Collections.emptySet();
  }

  /**
   * v1 placeholder. Real impl calls {@code nutritionQueryService.findRecipesViolatingTarget(...)}.
   */
  private Set<UUID> filterAffectedNutrition(NutritionTargetsChangedEvent event) {
    return Collections.emptySet();
  }

  /** v1 placeholder. Real impl calls {@code provisionsQueryService.findRecipesOverBudget(...)}. */
  private Set<UUID> filterAffectedBudget(BudgetChangedEvent event) {
    return Collections.emptySet();
  }

  private void enqueue(UUID userId, DataModelChangeType type, Set<UUID> recipeIds, UUID traceId) {
    DataModelJobRequest req =
        new DataModelJobRequest(
            userId, type, JsonNodeFactory.instance.objectNode(), recipeIds, traceId);
    adaptationService.enqueueDataModelChangeJobs(req);
  }
}
