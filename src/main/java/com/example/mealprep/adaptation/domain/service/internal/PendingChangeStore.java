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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically supersedes any existing PENDING change for {@code (recipe_id, dimension)} and inserts
 * a new PENDING row. The partial unique index {@code (recipe_id, change_dimension) WHERE status =
 * 'PENDING'} serialises concurrent supersession.
 *
 * <p>Per ticket 01c §Step 7 / LLD lines 157, 759-760.
 *
 * <p>Transaction: {@link #create} is {@code @Transactional} REQUIRED — it MUST run in the caller's
 * transaction because {@code adaptation_pending_changes.job_id} FKs the {@code adaptation_jobs} row
 * created earlier in that SAME, not-yet-committed transaction (the sync feedback path {@code
 * enqueueFeedbackJob → processSyncJob → processJob → create}). A {@code REQUIRES_NEW} tx could not
 * see that uncommitted job row and would violate {@code adaptation_pending_changes_job_id_fkey}.
 *
 * <p>Concurrency: the partial unique index {@code (recipe_id, change_dimension) WHERE
 * status='PENDING'} is the source-of-truth serialiser, but same-recipe jobs are already serialised
 * upstream by the per-recipe advisory lock in {@code processJob}, so {@code create} sees a stable
 * single PENDING row. A genuine concurrent insert that slipped past the lock violates the partial
 * unique index and fails the job terminally — it CANNOT be retried in a fresh transaction, because
 * the {@code job_id} FK still references the original tx's uncommitted job row (see above).
 */
@Component
public class PendingChangeStore {

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
   * Supersede + insert, in the caller's transaction. Returns the newly-inserted {@link
   * PendingChange} id. Publishes a {@code PendingChangeCreatedEvent} which downstream listeners
   * filter on {@code AFTER_COMMIT}.
   *
   * <p>{@code @Transactional} REQUIRED: runs in the SAME tx as the {@code adaptation_jobs} row this
   * change's {@code job_id} FKs (a {@code REQUIRES_NEW} tx can't see that uncommitted row). See the
   * class javadoc.
   */
  @Transactional
  public UUID create(
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
    // PENDING row, so the no-race path never self-collides; a genuine concurrent insert still
    // violates and fails the job terminally.
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
