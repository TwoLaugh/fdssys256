package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.domain.entity.PlannerHintRecord;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.event.PlannerHintEmittedEvent;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encapsulates the planner-hint emit + auto-invalidate flow. Called by the public {@code
 * emitPlannerHint} surface (planner-noticed hints) AND by the worker pipeline on new-version writes
 * (LLD line 769) to invalidate stale hints attached to the prior version.
 *
 * <p>Per ticket 01f §emitPlannerHint + PlannerHintEmitter and {@code lld/adaptation-pipeline.md}
 * §PlannerHintEmitter (lines 214-241).
 *
 * <p>{@link PlannerHintEmittedEvent} is published inside the tx body (Spring's
 * {@code @TransactionalEventListener(AFTER_COMMIT)} fires it after commit; publishing inside the
 * active tx is required — events published with no active tx are silently dropped, wave-3 retro
 * 0012).
 */
@Component
public class PlannerHintEmitter {

  private static final Logger LOG = LoggerFactory.getLogger(PlannerHintEmitter.class);

  private final PlannerHintRecordRepository repo;
  private final ApplicationEventPublisher events;

  public PlannerHintEmitter(PlannerHintRecordRepository repo, ApplicationEventPublisher events) {
    this.repo = repo;
    this.events = events;
  }

  /**
   * Persist a new planner-hint row + publish {@link PlannerHintEmittedEvent}. Before inserting, any
   * active hint of the same {@code hintType} on the same recipe but a <em>different</em> version is
   * invalidated — a hint scoped to a stale version no longer describes the live body.
   *
   * @param emittedByJobId the pipeline job that produced the hint; {@code null} for planner-noticed
   *     hints emitted outside any job (FK column is {@code ON DELETE SET NULL}).
   */
  @Transactional
  public PlannerHintRecord emit(PlannerHintRequest request, UUID emittedByJobId) {
    int invalidated =
        repo.invalidateForRecipeTypeOnOtherVersions(
            request.recipeId(), request.hintType(), request.versionId(), Instant.now());
    if (invalidated > 0) {
      LOG.info(
          "planner-hint emit invalidated {} prior-version hint(s) recipeId={} type={}",
          invalidated,
          request.recipeId(),
          request.hintType());
    }
    PlannerHintRecord record =
        PlannerHintRecord.builder()
            .id(UUID.randomUUID())
            .recipeId(request.recipeId())
            .versionId(request.versionId())
            .branchId(request.branchId())
            .hintType(request.hintType())
            .description(request.description())
            .payload(request.payload())
            .severity(request.severity())
            .emittedByJobId(emittedByJobId)
            .traceId(request.traceId())
            .createdAt(Instant.now())
            .build();
    repo.save(record);
    events.publishEvent(
        new PlannerHintEmittedEvent(
            record.getId(),
            record.getRecipeId(),
            record.getVersionId(),
            record.getHintType(),
            record.getSeverity(),
            record.getTraceId(),
            Instant.now()));
    return record;
  }

  /**
   * Bulk-invalidate every active hint attached to {@code oldVersionId}. Called by the worker
   * pipeline at the end of {@code processJob} when a new version supersedes the old one. Returns
   * the number of rows touched.
   */
  @Transactional
  public int invalidateHintsForOldVersion(UUID oldVersionId) {
    if (oldVersionId == null) {
      return 0;
    }
    int n = repo.invalidateForOldVersion(oldVersionId, Instant.now());
    if (n > 0) {
      LOG.info("invalidated {} hint(s) for superseded versionId={}", n, oldVersionId);
    }
    return n;
  }
}
