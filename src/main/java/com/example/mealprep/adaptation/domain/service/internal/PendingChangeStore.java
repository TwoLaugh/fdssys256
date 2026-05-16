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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically supersedes any existing PENDING change for {@code (recipe_id, dimension)} and inserts
 * a new PENDING row. The partial unique index {@code (recipe_id, change_dimension) WHERE status =
 * 'PENDING'} serialises concurrent supersession.
 *
 * <p>Per ticket 01c §Step 7 / LLD lines 157, 759-760.
 *
 * <p>Race retry: on {@link DataIntegrityViolationException} (loser of a concurrent insert race), we
 * retry once — fetch the now-existing PENDING row, mark our row as SUPERSEDED preemptively, insert
 * a SUPERSEDED-flagged row attached to our job.
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
   * Supersede + insert. Returns the newly-inserted {@link PendingChange} id. Publishes a {@code
   * PendingChangeCreatedEvent} which downstream listeners filter on {@code AFTER_COMMIT}.
   */
  @Transactional
  public UUID create(
      AdaptationJob job,
      RecipeAdaptationResponse response,
      ChangeDimension dimension,
      UUID baseVersionId,
      UUID baseBranchId,
      String promptTemplateVersion) {
    return createInternal(
        job, response, dimension, baseVersionId, baseBranchId, promptTemplateVersion, false);
  }

  private UUID createInternal(
      AdaptationJob job,
      RecipeAdaptationResponse response,
      ChangeDimension dimension,
      UUID baseVersionId,
      UUID baseBranchId,
      String promptTemplateVersion,
      boolean retryAsSuperseded) {
    Instant now = Instant.now();
    // Supersede any active PENDING for (recipe, dimension).
    Optional<PendingChange> existing =
        repository.findByRecipeIdAndChangeDimensionAndStatus(
            job.getRecipeId(), dimension, PendingChangeStatus.PENDING);
    UUID newId = UUID.randomUUID();
    existing.ifPresent(
        e -> {
          e.setStatus(PendingChangeStatus.SUPERSEDED);
          e.setSupersededBy(newId);
          e.setResolvedAt(now);
          repository.save(e);
        });

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
            .status(
                retryAsSuperseded ? PendingChangeStatus.SUPERSEDED : PendingChangeStatus.PENDING)
            .createdAt(now)
            .expiresAt(now.plus(config.pendingChangeExpiryDays(), ChronoUnit.DAYS))
            .build();
    try {
      repository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException ex) {
      if (retryAsSuperseded) {
        LOG.warn("PendingChangeStore.create unique-constraint hit even on retry path", ex);
        throw ex;
      }
      LOG.warn(
          "PendingChangeStore.create lost supersession race; retrying as SUPERSEDED for job={}",
          job.getId());
      return createInternal(
          job, response, dimension, baseVersionId, baseBranchId, promptTemplateVersion, true);
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
