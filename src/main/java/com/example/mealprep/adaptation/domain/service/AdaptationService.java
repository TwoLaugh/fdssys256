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

  /** Trigger 1: async — returns jobId; worker processes the row. */
  UUID enqueueImportJob(ImportJobRequest request);

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
}
