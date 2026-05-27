package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Trigger 1 entry — listens for {@link RecipeCreatedEvent} from the recipe module and (when the
 * recipe is a plausible adaptation candidate) enqueues an IMPORT-source adaptation job. Per LLD
 * line 693 and the Trigger-1 cost-discipline gate ({@code
 * tickets/adaptation/02b-trigger1-cost-discipline.md}).
 *
 * <p>Follows the round-7 rule: {@code @TransactionalEventListener(AFTER_COMMIT)} +
 * {@code @Transactional(REQUIRES_NEW)} because the listener body writes an {@code AdaptationJob}
 * row.
 *
 * <p><b>Cross-module rule</b>: the listener cannot inject {@code RecipeRepository} (ArchUnit), so
 * the owning {@code userId} and the recipe's {@code dataQuality} travel on the event itself. It MAY
 * inject the published cross-module read/filter seams ({@code RecipeQueryService}, {@code
 * HardConstraintFilterService}, {@code NutritionQueryService}) — same as {@link
 * AdaptationDataModelListener} — to run a cheap deterministic pre-filter before spending an LLM
 * call.
 *
 * <p><b>Cost-discipline gate (02b).</b> The pre-02b behaviour enqueued an ASYNC {@code
 * RECIPE_ADAPTATION} job UNCONDITIONALLY for every create (manual, import, AI-gen, discovery) —
 * most calls did nothing, and bulk discovery/import seeding caused per-recipe LLM fan-out. The gate
 * decides by {@link RecipeCreatedEvent#dataQuality()} (which encodes the recipe's origin):
 *
 * <ul>
 *   <li><b>USER_VERIFIED</b> (clean manual create): run a single-recipe conflict pre-filter
 *       (hard-constraint + nutrition). Enqueue an ASYNC job ONLY when it plausibly conflicts; SKIP
 *       (enqueue nothing) otherwise. A deliberately-typed clean recipe is a weak candidate.
 *   <li><b>IMPORTED / WEB_DISCOVERED / AI_GENERATED</b> (bulk-origin / messy): enqueue at BATCH
 *       priority — the daily {@code BatchJobOrchestrator} processes it, avoiding immediate
 *       per-recipe LLM fan-out on bulk seeding/import. (These are strong adaptation candidates but
 *       must not thundering-herd.)
 * </ul>
 *
 * <p>The {@code USER}-catalogue → {@code PENDING_CHANGE} / {@code SYSTEM} → {@code DIRECT}
 * approval-policy rule is unchanged (decided inside {@link
 * AdaptationService#enqueueImportJob(ImportJobRequest, JobPriority)} from the catalogue). v1 is a
 * silent pending-change — no new user-facing surface.
 */
@org.springframework.stereotype.Component
public class AdaptationImportListener {

  private static final Logger LOG = LoggerFactory.getLogger(AdaptationImportListener.class);

  private final AdaptationService adaptationService;
  private final RecipeQueryService recipeQueryService;
  private final HardConstraintFilterService hardConstraintFilterService;
  private final NutritionQueryService nutritionQueryService;

  public AdaptationImportListener(
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
  public void onRecipeCreated(RecipeCreatedEvent event) {
    decideAndEnqueue(event);
  }

  /**
   * The Trigger-1 cost-discipline decision (origin → action). Package-private (not {@code private})
   * so the same-package IT can drive it directly against the REAL cross-module beans with seeded
   * rows — the {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW} entry point
   * is awkward to drive deterministically (mirrors {@link AdaptationDataModelListener}'s
   * package-private filter methods).
   *
   * @return the enqueued jobId, or {@link Optional#empty()} when the clean-manual-create pre-filter
   *     SKIPped (no job, no LLM spend).
   */
  Optional<UUID> decideAndEnqueue(RecipeCreatedEvent event) {
    UUID recipeId = event.recipeId();
    UUID userId = event.userId();
    DataQuality dataQuality = event.dataQuality();

    if (dataQuality == DataQuality.USER_VERIFIED) {
      // Clean manual create: only adapt if it plausibly conflicts with the owner's constraints.
      if (!plausiblyConflicts(userId, recipeId)) {
        LOG.info(
            "Trigger-1 SKIP: clean USER_VERIFIED recipe {} (user {}) has no hard-constraint /"
                + " nutrition conflict — no adaptation job enqueued (cost discipline)",
            recipeId,
            userId);
        return Optional.empty();
      }
      UUID jobId = adaptationService.enqueueImportJob(importJobRequest(event), JobPriority.ASYNC);
      LOG.info(
          "Trigger-1 ASYNC: conflicting USER_VERIFIED recipe {} -> enqueued adaptation job {}",
          recipeId,
          jobId);
      return Optional.of(jobId);
    }

    // IMPORTED / WEB_DISCOVERED / AI_GENERATED: bulk / messy origin -> BATCH (no JobReadyEvent;
    // the daily BatchJobOrchestrator picks it up), so bulk seeding doesn't fan out per-recipe LLM
    // calls.
    UUID jobId = adaptationService.enqueueImportJob(importJobRequest(event), JobPriority.BATCH);
    LOG.info(
        "Trigger-1 BATCH: {}-origin recipe {} -> enqueued adaptation job {} (daily orchestrator)",
        dataQuality,
        recipeId,
        jobId);
    return Optional.of(jobId);
  }

  /**
   * Single-recipe conflict pre-filter for a clean USER_VERIFIED create. True when the new recipe
   * violates one of the owner's hard constraints OR (if its nutrition is already computed) violates
   * their nutrition targets.
   *
   * <p>Nutrition is computed asynchronously and may not yet exist at create time — a {@code null}
   * nutrition node is fine (treated as "no nutrition conflict to detect yet"; a later data-model
   * change or recompute is Trigger 3's job).
   */
  boolean plausiblyConflicts(UUID userId, UUID recipeId) {
    List<String> keys =
        recipeQueryService.findUserRecipeIngredientKeys(userId).getOrDefault(recipeId, List.of());
    boolean hardConflict =
        !keys.isEmpty()
            && !hardConstraintFilterService.checkRecipe(userId, recipeId, keys).passes();
    if (hardConflict) {
      return true;
    }
    JsonNode nut = recipeQueryService.findUserRecipeNutrition(userId).get(recipeId);
    return nut != null
        && !nutritionQueryService
            .findRecipeIdsViolatingTargets(userId, Map.of(recipeId, nut))
            .isEmpty();
  }

  private static ImportJobRequest importJobRequest(RecipeCreatedEvent event) {
    return new ImportJobRequest(
        event.recipeId(),
        event.userId(),
        event.catalogue(),
        event.dataQuality(),
        null,
        event.traceId());
  }
}
