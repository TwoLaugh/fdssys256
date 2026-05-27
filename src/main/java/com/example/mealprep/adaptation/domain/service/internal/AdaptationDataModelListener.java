package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.api.dto.DataModelChangeType;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.provisions.event.BudgetChangedEvent;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p><b>Cross-module affected-recipe filters.</b> The hard-constraints and nutrition dimensions are
 * now implemented (the recipe / preference / nutrition modules ship the raw-read + evaluation seams
 * they need). Each re-evaluates the user's FULL current state via the relevant published {@code
 * *QueryService} / {@code *FilterService} — slight over-selection vs the specific changed field is
 * acceptable because the downstream LLM job is the real decision-maker and may emit {@code
 * NO_CHANGE}. The budget dimension remains a deliberate stub pending the grocery module (no
 * per-recipe cost data exists yet); see {@link #filterAffectedBudget}.
 */
@org.springframework.stereotype.Component
public class AdaptationDataModelListener {

  private static final Logger LOG = LoggerFactory.getLogger(AdaptationDataModelListener.class);

  private final AdaptationService adaptationService;
  private final RecipeQueryService recipeQueryService;
  private final HardConstraintFilterService hardConstraintFilterService;
  private final NutritionQueryService nutritionQueryService;

  public AdaptationDataModelListener(
      AdaptationService adaptationService,
      RecipeQueryService recipeQueryService,
      HardConstraintFilterService hardConstraintFilterService,
      NutritionQueryService nutritionQueryService) {
    this.adaptationService = adaptationService;
    this.recipeQueryService = recipeQueryService;
    this.hardConstraintFilterService = hardConstraintFilterService;
    this.nutritionQueryService = nutritionQueryService;
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

  // --- affected-recipe filters -----------------------------------------------------------------

  /**
   * Re-evaluates the user's active USER-catalogue recipes against their FULL current hard
   * constraints and returns the recipeIds that now VIOLATE them.
   *
   * <p>We do not diff against the specific changed field on the event: a constraint change can
   * interact (e.g. a new allergen plus an existing intolerance), so re-checking the whole aggregate
   * is correct and cheap ({@link HardConstraintFilterService#filterRecipes} loads it once). The
   * slight over-selection vs the literal changed field is acceptable — the downstream LLM
   * adaptation job is the real decision-maker and may emit {@code NO_CHANGE}.
   *
   * <p>Empty when the user has no active recipes, or — because {@code filterRecipes} treats a user
   * with no constraints aggregate as "everything passes" — when there are no hard constraints to
   * violate.
   *
   * <p>Package-private (not {@code private}) so the same-package IT can assert selection /
   * non-selection directly against the real cross-module beans; the
   * {@code @TransactionalEventListener} entry points are awkward to drive deterministically in a
   * test.
   */
  Set<UUID> filterAffectedHardConstraints(HardConstraintsUpdatedEvent event) {
    UUID userId = event.userId();
    Map<UUID, List<String>> recipeKeys = recipeQueryService.findUserRecipeIngredientKeys(userId);
    if (recipeKeys.isEmpty()) {
      return Set.of();
    }
    List<UUID> passing = hardConstraintFilterService.filterRecipes(userId, recipeKeys);
    Set<UUID> affected = new LinkedHashSet<>(recipeKeys.keySet());
    affected.removeAll(passing); // affected = recipes that VIOLATE the user's current constraints
    return affected;
  }

  /**
   * Re-evaluates the user's active USER-catalogue recipes against their current nutrition targets
   * and returns the recipeIds whose per-serving nutrition violates them, via the nutrition module's
   * coarse v1 pre-filter ({@link NutritionQueryService#findRecipeIdsViolatingTargets}).
   *
   * <p>Empty when the user has no recipe with stored {@code nutrition_per_serving}, or when the
   * user has no targets row (the pre-filter returns empty in that case).
   *
   * <p>Package-private for the same reason as {@link #filterAffectedHardConstraints}.
   */
  Set<UUID> filterAffectedNutrition(NutritionTargetsChangedEvent event) {
    UUID userId = event.userId();
    Map<UUID, JsonNode> perRecipe = recipeQueryService.findUserRecipeNutrition(userId);
    if (perRecipe.isEmpty()) {
      return Set.of();
    }
    return nutritionQueryService.findRecipeIdsViolatingTargets(userId, perRecipe);
  }

  /**
   * Deliberately deferred — always empty. Unlike hard-constraints and nutrition, the budget
   * dimension cannot be evaluated per recipe in v1: there is <b>no per-recipe cost data</b> in the
   * system. No grocery module / ingredient-pricing surface exists yet (see {@code
   * tickets/grocery/01c-cost-projection-and-reference-price-source.md} and the rest of {@code
   * tickets/grocery/}), so a recipe has no cost to compare against a budget.
   *
   * <p>It is also a <b>semantic mismatch</b>: a weekly budget is a <em>plan-level</em> constraint
   * (a week of meals), not a per-meal-recipe attribute, so even with cost data this dimension would
   * adapt the PLAN, not individual recipes. The Trigger-1 cost-discipline path that owns this is
   * scoped in {@code tickets/adaptation/02b-trigger1-cost-discipline.md}. This is an intentional
   * deferral, not a TODO-to-forget — re-enable only once the grocery cost seams land.
   */
  Set<UUID> filterAffectedBudget(BudgetChangedEvent event) {
    return Collections.emptySet();
  }

  private void enqueue(UUID userId, DataModelChangeType type, Set<UUID> recipeIds, UUID traceId) {
    DataModelJobRequest req =
        new DataModelJobRequest(
            userId, type, JsonNodeFactory.instance.objectNode(), recipeIds, traceId);
    adaptationService.enqueueDataModelChangeJobs(req);
  }
}
