package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.event.PendingChangeCreatedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically supersedes any existing PENDING change for {@code (recipe_id, dimension)} and inserts
 * a new PENDING row. The partial unique index {@code (recipe_id, change_dimension) WHERE status =
 * 'PENDING'} serialises concurrent supersession.
 *
 * <p>Per ticket 01c §Step 7 / LLD lines 157, 759-760.
 *
 * <p>Concurrency: the partial unique index {@code (recipe_id, change_dimension) WHERE
 * status='PENDING'} is the source-of-truth serialiser. Two jobs for the same {@code (recipe,
 * dimension)} can still race past the {@code findBy} (e.g. a recipe's adapt-on-create IMPORT job
 * and a feedback-to-that-recipe FEEDBACK job): the loser's INSERT hits the index. We recover by
 * retrying ONCE in a brand-new transaction — each attempt runs in its own {@code REQUIRES_NEW} tx
 * (via the {@code self} proxy), so on violation it rolls back cleanly and the retry's re-read sees
 * the winner's now-committed PENDING, supersedes it (last-writer-wins, consistent with the no-race
 * path) and inserts. Recovering in the original (already-aborted) transaction is impossible in
 * Postgres, which is why {@link #create} is itself non-transactional and delegates to the {@code
 * REQUIRES_NEW} {@link #attemptCreate}.
 */
@Component
public class PendingChangeStore {

  private static final Logger LOG = LoggerFactory.getLogger(PendingChangeStore.class);

  private final PendingChangeRepository repository;
  private final ApplicationEventPublisher events;
  private final AdaptationConfig config;

  public PendingChangeStore(
      PendingChangeRepository repository,
      ApplicationEventPublisher events,
      AdaptationConfig config) {
    this.repository = repository;
    this.events = events;
    this.config = config;
  }

  /**
   * Self-proxy so {@link #create} invokes the {@code REQUIRES_NEW} {@link #attemptCreate} through
   * Spring's transactional proxy — a plain {@code this.attemptCreate(...)} would bypass it and the
   * retry would not get a fresh transaction. Mirrors the {@code @Autowired @Lazy} self-injection
   * used elsewhere in this module (e.g. {@code AdaptationLockAcquirer}).
   */
  @Autowired @Lazy private PendingChangeStore self;

  /**
   * Supersede + insert. Returns the newly-inserted {@link PendingChange} id. Publishes a {@code
   * PendingChangeCreatedEvent} which downstream listeners filter on {@code AFTER_COMMIT}.
   *
   * <p>Non-transactional orchestrator: delegates to the {@code REQUIRES_NEW} {@link #attemptCreate}
   * and, if that loses a concurrent {@code (recipe, dimension)} PENDING race (its own tx rolls back
   * on the unique-index violation), retries exactly ONCE in a brand-new transaction. See the class
   * javadoc.
   */
  public UUID create(
      AdaptationJob job,
      RecipeAdaptationResponse response,
      ChangeDimension dimension,
      UUID baseVersionId,
      UUID baseBranchId,
      String promptTemplateVersion) {
    try {
      return self.attemptCreate(
          job, response, dimension, baseVersionId, baseBranchId, promptTemplateVersion);
    } catch (DataIntegrityViolationException race) {
      LOG.warn(
          "PendingChangeStore lost (recipe={}, dimension={}) PENDING race for job={}; retrying in a"
              + " fresh transaction",
          job.getRecipeId(),
          dimension,
          job.getId());
      return self.attemptCreate(
          job, response, dimension, baseVersionId, baseBranchId, promptTemplateVersion);
    }
  }

  /**
   * One supersede-then-insert attempt in its OWN transaction, so a unique-index violation rolls
   * back THIS tx cleanly and {@link #create} can retry in a fresh one (Postgres cannot continue an
   * aborted transaction). MUST be invoked via the {@code self} proxy for the {@code REQUIRES_NEW}
   * advice to apply. A {@link DataIntegrityViolationException} is allowed to propagate to {@link
   * #create}.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID attemptCreate(
      AdaptationJob job,
      RecipeAdaptationResponse response,
      ChangeDimension dimension,
      UUID baseVersionId,
      UUID baseBranchId,
      String promptTemplateVersion) {
    Instant now = Instant.now();
    UUID newId = UUID.randomUUID();
    // Supersede any active PENDING for (recipe, dimension). saveAndFlush forces the UPDATE to hit
    // the DB BEFORE the INSERT below — Hibernate otherwise orders INSERTs before UPDATEs, which
    // would leave two PENDING rows at insert time and self-collide on the partial unique index
    // (recipe_id, change_dimension) WHERE status='PENDING'. With the flush, there is only ever one
    // PENDING row, so the no-race path (incl. the retry's re-read of a now-committed winner) never
    // self-collides; a genuine concurrent insert still violates and propagates to create()'s retry.
    //
    // The supersededBy pointer is NOT set on this flush: the FK superseded_by -> id is NOT
    // deferrable, so pointing the superseded row at newId before that row physically exists would
    // violate the FK here. The partial unique index only cares about status, so we flip status +
    // resolvedAt now and back-fill the supersededBy pointer AFTER the new row is inserted below.
    //
    // We mutate the managed finder entity and call repository.flush() directly rather than
    // saveAndFlush(e): the finder already returns a managed instance, so a plain flush emits the
    // UPDATE immediately. Routing through save() would merge() (creating a second managed copy) and
    // leave the UPDATE queued, which Hibernate then re-orders AFTER the new-row INSERT — exactly
    // the
    // INSERT-before-UPDATE trap we are trying to avoid (it would momentarily leave two PENDING rows
    // and self-collide on the partial unique index).
    PendingChange superseded =
        repository
            .findByRecipeIdAndChangeDimensionAndStatus(
                job.getRecipeId(), dimension, PendingChangeStatus.PENDING)
            .orElse(null);
    if (superseded != null) {
      superseded.setStatus(PendingChangeStatus.SUPERSEDED);
      superseded.setResolvedAt(now);
      repository.flush();
    }

    JsonNode diff = diffNode(response);
    PendingChange entity =
        PendingChange.builder()
            .id(newId)
            .recipeId(job.getRecipeId())
            .userId(job.getUserId())
            .jobId(job.getId())
            .traceId(job.getTraceId())
            .changeDimension(dimension)
            .proposedDiff(diff)
            .proposedClassification(response.classification())
            .baseVersionId(baseVersionId)
            .baseBranchId(baseBranchId)
            .reasoning(response.reasoning() == null ? "" : response.reasoning())
            .nutritionalNotes(response.nutritionalNotes())
            .confidence(safe(response.confidence()))
            .impactScore(BigDecimal.valueOf(0.5))
            .promptTemplateVersion(promptTemplateVersion == null ? "v0" : promptTemplateVersion)
            .status(PendingChangeStatus.PENDING)
            .createdAt(now)
            .expiresAt(now.plus(config.pendingChangeExpiryDays(), ChronoUnit.DAYS))
            .build();
    repository.saveAndFlush(entity);
    // Back-fill the superseded row's pointer now that the new PENDING row physically exists,
    // satisfying the non-deferrable superseded_by -> id FK. Same tx, so it commits atomically with
    // the insert. `superseded` is still the managed finder instance, so mutate + flush emits the
    // UPDATE directly (no merge re-ordering).
    if (superseded != null) {
      superseded.setSupersededBy(newId);
      repository.flush();
    }
    events.publishEvent(
        new PendingChangeCreatedEvent(
            newId,
            job.getRecipeId(),
            job.getUserId(),
            dimension,
            safe(response.confidence()),
            BigDecimal.valueOf(0.5),
            job.getTraceId(),
            now));
    return newId;
  }

  private static JsonNode diffNode(RecipeAdaptationResponse response) {
    if (response.finalDiffJson() != null) {
      return response.finalDiffJson();
    }
    return JsonNodeFactory.instance.objectNode();
  }

  private static BigDecimal safe(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
