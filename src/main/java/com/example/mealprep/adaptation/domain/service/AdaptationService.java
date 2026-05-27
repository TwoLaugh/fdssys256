package com.example.mealprep.adaptation.domain.service;

import com.example.mealprep.adaptation.api.dto.AcceptPendingChangeRequest;
import com.example.mealprep.adaptation.api.dto.AdaptationResultDto;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.api.dto.FeedbackJobRequest;
import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.PlanTimeRefineDirectiveRequest;
import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import java.util.List;
import java.util.UUID;

/**
 * Pipeline write surface — the four trigger entry methods plus pending-change lifecycle,
 * planner-hint emission, and the scheduled-job expiry-sweep entry.
 *
 * <p>Per LLD §Service Interfaces lines 490-512; signatures verbatim from {@code
 * lld/adaptation-pipeline.md}.
 *
 * <p><b>Naming reconciliation</b>: sibling LLDs (planner, feedback) reference an
 * "OptimiserService.adapt(...)" / "OptimiserService.handleRecipeFeedback(...)" public symbol — see
 * {@code tickets/WAVE3-NAMING-RECONCILIATION.md}. {@link AdaptationService} is the canonical name
 * per the adaptation-pipeline LLD; the sibling references are forward-references that resolve to
 * {@link #runPlanTimeRefineJob} and {@link #enqueueFeedbackJob} respectively.
 */
public interface AdaptationService {

  /**
   * Trigger 1 (default ASYNC): async — returns jobId; the worker picks the row up via {@code
   * JobReadyEvent}. Equivalent to {@link #enqueueImportJob(ImportJobRequest, JobPriority)} with
   * {@link JobPriority#ASYNC}. Retained verbatim per the LLD signature.
   */
  UUID enqueueImportJob(ImportJobRequest request);

  /**
   * Trigger 1 (priority-aware): enqueue an IMPORT-source adaptation job at the requested {@code
   * priority}. {@link JobPriority#ASYNC} publishes a {@code JobReadyEvent} so the worker processes
   * the row immediately; {@link JobPriority#BATCH} does NOT publish an event — the row is picked up
   * by the daily {@code BatchJobOrchestrator} cron (mirrors {@link #enqueueDataModelChangeJobs}).
   * Used by the Trigger-1 cost-discipline gate to route bulk-origin creates (import / discovery /
   * AI-gen) to BATCH instead of per-recipe ASYNC fan-out. Per {@code
   * tickets/adaptation/02b-trigger1-cost-discipline.md}.
   */
  UUID enqueueImportJob(ImportJobRequest request, JobPriority priority);

  /** Trigger 2: sync — enqueues + processes, returns result. Feedback module waits. */
  AdaptationResultDto enqueueFeedbackJob(FeedbackJobRequest request);

  /** Trigger 3: async batch — returns the list of enqueued job ids. */
  List<UUID> enqueueDataModelChangeJobs(DataModelJobRequest request);

  /** Trigger 4: sync — planner waits during Stage D; returns within ~10s or AiUnavailable. */
  AdaptationResultDto runPlanTimeRefineJob(PlanTimeRefineDirectiveRequest request);

  /** Accept a pending change. Optimistic-version mismatch returns 409 at the controller layer. */
  PendingChangeDto acceptPendingChange(
      UUID pendingChangeId, AcceptPendingChangeRequest request, UUID actorUserId);

  /** Reject a pending change. Already-rejected returns 422 (not idempotent 200). */
  PendingChangeDto rejectPendingChange(
      UUID pendingChangeId, RejectPendingChangeRequest request, UUID actorUserId);

  /** Public so peer modules (notably the planner) can emit a hint they noticed. */
  PlannerHintDto emitPlannerHint(PlannerHintRequest request, UUID actorUserId);

  /** Scheduled-job entry; returns the number of rows touched. */
  int sweepExpiredPendingChanges();

  /**
   * Admin: re-enqueue a fresh copy of a FAILED job. The original must be {@code FAILED} (else 409);
   * the new row chains {@code parent_decision_id = oldJob.id} for the audit trail and starts {@code
   * PENDING}. Returns the new job's DTO.
   */
  com.example.mealprep.adaptation.api.dto.AdaptationJobDto retryFailedJob(UUID jobId);
}
