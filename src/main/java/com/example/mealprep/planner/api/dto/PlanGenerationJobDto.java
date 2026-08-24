package com.example.mealprep.planner.api.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Status of an asynchronous plan-generation job, returned by the async generate endpoint (202 +
 * this body) and its status-poll endpoint. The frontend submits a generate request, shows a
 * processing state, and polls {@code GET /api/v1/plans/generate/jobs/{jobId}} until {@code status}
 * is terminal:
 *
 * <ul>
 *   <li>{@code COMPLETED} → {@code planId} is the persisted plan to load + review ({@code replayed}
 *       true when an Idempotency-Key replay returned the cached plan without re-composing);
 *   <li>{@code FAILED} → {@code errorCode} is a short machine token for the failure (e.g. {@code
 *       lease-conflict}, {@code error}); {@code planId} is null.
 * </ul>
 *
 * <p>{@code householdId}/{@code weekStartDate} echo the request so the poller can authorise + route
 * without re-deriving them. Jobs are tracked in-memory (a generation completes in well under the
 * process lifetime); they do not survive a restart — the durable-job/SSE upgrade is backlog
 * (planner async + push-channel tickets).
 */
public record PlanGenerationJobDto(
    UUID jobId,
    PlanGenerationStatus status,
    UUID planId,
    String errorCode,
    UUID householdId,
    LocalDate weekStartDate,
    boolean replayed) {

  /** A freshly-scheduled job: RUNNING, no plan yet. */
  public static PlanGenerationJobDto running(
      UUID jobId, UUID householdId, LocalDate weekStartDate) {
    return new PlanGenerationJobDto(
        jobId, PlanGenerationStatus.RUNNING, null, null, householdId, weekStartDate, false);
  }

  /** A completed job carrying the persisted plan id; {@code replayed} marks an idempotency hit. */
  public PlanGenerationJobDto completed(UUID planId, boolean replayed) {
    return new PlanGenerationJobDto(
        jobId, PlanGenerationStatus.COMPLETED, planId, null, householdId, weekStartDate, replayed);
  }

  /** A failed job carrying a short error token. */
  public PlanGenerationJobDto failed(String errorCode) {
    return new PlanGenerationJobDto(
        jobId, PlanGenerationStatus.FAILED, null, errorCode, householdId, weekStartDate, false);
  }
}
