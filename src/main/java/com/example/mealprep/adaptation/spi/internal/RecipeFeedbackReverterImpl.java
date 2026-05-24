package com.example.mealprep.adaptation.spi.internal;

import com.example.mealprep.adaptation.api.dto.RejectPendingChangeRequest;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.exception.PendingChangeNotFoundException;
import com.example.mealprep.adaptation.exception.PendingChangeNotPendingException;
import com.example.mealprep.feedback.spi.RecipeFeedbackReverter;
import com.example.mealprep.feedback.spi.RevertContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Real {@link RecipeFeedbackReverter} (feedback-01h §1-4). As a plain {@code @Component} it
 * out-ranks the feedback module's {@code NoopFeedbackRevertersConfiguration}
 * {@code @Bean @ConditionalOnMissingBean}, so with the adaptation module on the classpath the
 * feedback service resolves this bean. (The Noop stays for feedback-only test slices that don't
 * load adaptation.)
 *
 * <p>When a RECIPE routing is corrected away, the original feedback dispatch enqueued an adaptation
 * job ({@code RecipeFeedbackBridge → AdaptationService.enqueueFeedbackJob}) which — for an {@code
 * AWAITING_USER_APPROVAL} outcome — created a {@link PendingChange}. The recipe bridge records the
 * adaptation {@code jobId} on the routing's {@code destinationResultJson}; this reverter resolves
 * the pending change from that job id and <b>cancels</b> it by reusing {@link
 * AdaptationService#rejectPendingChange} (feedback-01h §3 decision: reuse the reject surface rather
 * than invent a cancel pipeline). The reject's "already-resolved → 422" guard ({@link
 * PendingChangeNotPendingException}) is exactly the "already approved or applied → log-only" branch
 * (lld/feedback.md §Flow 4 step 3 / line 797): it is caught and downgraded to a log line.
 *
 * <p><b>Transaction phase (decision-log 0010)</b>: invoked INSIDE {@code
 * correctMisclassification}'s default {@code @Transactional} (REQUIRED) tx; {@code
 * rejectPendingChange} is plain {@code @Transactional} (REQUIRED) so it JOINS that tx — the reject
 * + {@code PendingChangeRejectedEvent} commit atomically with the correction bookkeeping. No {@code
 * REQUIRES_NEW}.
 *
 * <p><b>Best-effort &amp; never-throws</b>: the SPI contract requires the reverter not to block the
 * correction. No job handle, no pending change for the job, an already-resolved change (terminal
 * state), or any adaptation domain failure is caught internally and logged WARN; the correction
 * still records ground truth and fires the synthetic replay (which re-routes correctly regardless).
 */
@Component
public class RecipeFeedbackReverterImpl implements RecipeFeedbackReverter {

  private static final Logger log = LoggerFactory.getLogger(RecipeFeedbackReverterImpl.class);

  /** Note the bridge persists for the reject — short, names the corrective origin. */
  private static final RejectPendingChangeRequest REVERT_REJECT_NOTE =
      new RejectPendingChangeRequest("auto-cancelled by misclassification correction");

  private final AdaptationService adaptationService;
  private final PendingChangeRepository pendingChangeRepository;

  public RecipeFeedbackReverterImpl(
      AdaptationService adaptationService, PendingChangeRepository pendingChangeRepository) {
    this.adaptationService = adaptationService;
    this.pendingChangeRepository = pendingChangeRepository;
  }

  @Override
  public void revert(RevertContext ctx) {
    UUID jobId = readJobId(ctx.destinationResultJson());
    if (jobId == null) {
      log.warn(
          "recipe revert is log-only; no adaptation jobId on routing {} — previous suggestion kept",
          ctx.originalRoutingId());
      return;
    }

    Optional<PendingChange> pending = pendingChangeRepository.findByJobId(jobId);
    if (pending.isEmpty()) {
      // The feedback job classified as NO_CHANGE / VERSION / SUBSTITUTION (no pending change), or
      // the change has been hard-deleted. Nothing awaiting approval to cancel.
      log.warn(
          "recipe revert is log-only; no pending change for adaptation job {} on routing {} —"
              + " previous suggestion kept",
          jobId,
          ctx.originalRoutingId());
      return;
    }
    PendingChange pc = pending.get();

    if (pc.getStatus() != PendingChangeStatus.PENDING) {
      // Already approved / applied / superseded / expired — the suggestion is no longer cancellable
      // (lld/feedback.md line 797: "if already approved or applied, the correction is log-only").
      log.warn(
          "recipe revert is log-only; pending change {} (job {}) is {} on routing {} — previous"
              + " adaptation kept",
          pc.getId(),
          jobId,
          pc.getStatus(),
          ctx.originalRoutingId());
      return;
    }

    try {
      adaptationService.rejectPendingChange(pc.getId(), REVERT_REJECT_NOTE, ctx.userId());
      log.info(
          "recipe revert cancelled pending adaptation {} (job {}) on routing {} via reject",
          pc.getId(),
          jobId,
          ctx.originalRoutingId());
    } catch (PendingChangeNotPendingException terminalState) {
      // Raced into a terminal state between our read and the reject (the 422 guard) — treat exactly
      // as the already-applied branch: log-only, do not throw.
      log.warn(
          "recipe revert is log-only; pending change {} (job {}) reached a terminal state before"
              + " cancel on routing {} — previous adaptation kept",
          pc.getId(),
          jobId,
          ctx.originalRoutingId());
    } catch (PendingChangeNotFoundException vanished) {
      // Hard-deleted between read and reject (or a cross-tenant mismatch surfaced as 404).
      log.warn(
          "recipe revert is log-only; pending change {} (job {}) not found at cancel on routing {}",
          pc.getId(),
          jobId,
          ctx.originalRoutingId());
    }
  }

  /** The adaptation job id the recipe bridge records on the routing's destination result. */
  private static UUID readJobId(JsonNode destinationResultJson) {
    if (destinationResultJson == null) {
      return null;
    }
    JsonNode node = destinationResultJson.path("jobId");
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    try {
      return UUID.fromString(node.asText());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }
}
