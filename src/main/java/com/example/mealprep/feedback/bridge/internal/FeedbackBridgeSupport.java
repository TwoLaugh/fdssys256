package com.example.mealprep.feedback.bridge.internal;

import com.example.mealprep.core.origin.ActorType;
import com.example.mealprep.core.origin.AuditMetadata;
import com.example.mealprep.core.origin.Origin;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackBridgeIdempotency;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.spi.Destination;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared infrastructure for the four real destination bridges (preference, nutrition, provisions,
 * recipe) per tickets/feedback/01g §3. Each concrete bridge implements its module's SPI interface
 * and delegates the cross-cutting concerns here:
 *
 * <ul>
 *   <li><b>Confidence floor</b> — {@link #belowConfidenceFloor(BigDecimal)} rejects {@code
 *       confidence < 0.5}. The bridge books a {@code REJECTED_LOW_CONFIDENCE} idempotency row and
 *       returns without calling the destination. Logs WARN; does NOT throw (a rejection must not
 *       poison the routing loop — design/feedback-system.md §Confidence handling).
 *   <li><b>Idempotency</b> — {@link #alreadyDispatched(UUID, Destination)} consults the {@code
 *       feedback_bridge_idempotency} table; a row younger than {@link #IDEMPOTENCY_WINDOW} for the
 *       same {@code (feedback_id, destination)} means re-processing is a no-op.
 *   <li><b>Origin attribution</b> — {@link #aiFeedbackAudit(UUID)} builds the {@code AI} {@link
 *       AuditMetadata} the destination services persist (actor_type = AI, origin_trace =
 *       feedback-&lt;id&gt;). Per the v1 in-process decision (ticket §3 bullet 3) the bridges do
 *       NOT populate the request-scoped {@link com.example.mealprep.core.origin.OriginContext}
 *       (there is no HTTP request); they construct the attribution explicitly here, preserving the
 *       origin-tracking convention (X-Origin: ai-feedback, depth 1) at the service-call surface.
 *   <li><b>Idempotency bookkeeping</b> — {@link #recordOutcome(UUID, Destination,
 *       BridgeDispatchStatus)} inserts-or-updates the row with the terminal status.
 * </ul>
 */
public abstract class FeedbackBridgeSupport {

  /** Confidence floor for AI-origin mutations (design/origin-tracking-pattern.md §Auth diff). */
  protected static final BigDecimal CONFIDENCE_FLOOR = new BigDecimal("0.5");

  /** {@code X-Origin-Trace} prefix: {@code feedback-<feedback_id>}. */
  protected static final String ORIGIN_TRACE_PREFIX = "feedback-";

  /** {@code X-Origin-Depth} for the first system-driven hop. */
  protected static final int ORIGIN_DEPTH = 1;

  /** Re-processing the same feedback within this window is a no-op (ticket §3). */
  protected static final Duration IDEMPOTENCY_WINDOW = Duration.ofMinutes(5);

  private final FeedbackBridgeIdempotencyRepository idempotencyRepository;
  private final TransactionTemplate requiresNewTxTemplate;
  private final Clock clock;

  protected FeedbackBridgeSupport(
      FeedbackBridgeIdempotencyRepository idempotencyRepository,
      TransactionTemplate requiresNewTxTemplate,
      Clock clock) {
    this.idempotencyRepository = idempotencyRepository;
    this.requiresNewTxTemplate = requiresNewTxTemplate;
    this.clock = clock;
  }

  /** SLF4J logger bound to the concrete bridge so log lines name the right class. */
  protected Logger log() {
    return LoggerFactory.getLogger(getClass());
  }

  /** True when {@code confidence} is below the 0.5 floor (null treated as below — fail-closed). */
  protected boolean belowConfidenceFloor(BigDecimal confidence) {
    return confidence == null || confidence.compareTo(CONFIDENCE_FLOOR) < 0;
  }

  /**
   * True when a {@code (feedback_id, destination)} row exists and is younger than {@link
   * #IDEMPOTENCY_WINDOW}. A row older than the window is treated as expired — the caller may
   * re-dispatch (it will overwrite the stale row's status via {@link #recordOutcome}).
   */
  protected boolean alreadyDispatched(UUID feedbackId, Destination destination) {
    Optional<FeedbackBridgeIdempotency> existing =
        requiresNewTxTemplate.execute(
            status ->
                idempotencyRepository.findByFeedbackIdAndDestination(feedbackId, destination));
    if (existing == null || existing.isEmpty()) {
      return false;
    }
    Instant dispatchedAt = existing.get().getDispatchedAt();
    Instant cutoff = clock.instant().minus(IDEMPOTENCY_WINDOW);
    return dispatchedAt.isAfter(cutoff);
  }

  /** The {@code feedback-<feedback_id>} origin trace this bridge stamps on downstream calls. */
  protected String originTrace(UUID feedbackId) {
    return ORIGIN_TRACE_PREFIX + feedbackId;
  }

  /**
   * The AI attribution the destination service writes on its audit row: {@code actor_type = AI},
   * {@code origin_trace = feedback-<feedback_id>}. Mirrors what {@code OriginFilter} would populate
   * for an {@code X-Origin: ai-feedback} HTTP request, constructed explicitly for the in-process
   * call path.
   */
  protected AuditMetadata aiFeedbackAudit(UUID feedbackId) {
    return new AuditMetadata(Origin.AI_FEEDBACK.toActorType(), originTrace(feedbackId));
  }

  /** Constant: AI is the actor for every bridge-driven mutation. */
  protected ActorType actorType() {
    return Origin.AI_FEEDBACK.toActorType();
  }

  /**
   * Insert-or-update the idempotency row with the terminal {@code status}. Insert-or-skip semantics
   * via {@code ON CONFLICT DO NOTHING}; if the slot was already claimed (concurrent invocation, or
   * a re-dispatch after window expiry), the status is updated in place so the row always reflects
   * the latest outcome.
   */
  protected void recordOutcome(
      UUID feedbackId, Destination destination, BridgeDispatchStatus status) {
    // Own transaction (REQUIRES_NEW) so the idempotency row survives even if the surrounding
    // dispatch transaction rolls back — the forensic record of "we tried, and the outcome was X"
    // must persist regardless of the destination call's fate.
    requiresNewTxTemplate.executeWithoutResult(
        txStatus -> {
          Instant now = clock.instant();
          int inserted =
              idempotencyRepository.insertIfAbsent(
                  UUID.randomUUID(), feedbackId, destination.name(), status.name(), now);
          if (inserted == 0) {
            idempotencyRepository.updateStatus(feedbackId, destination, status, now);
          }
        });
  }

  /**
   * Book a {@code FAILED} idempotency row and throw {@link FeedbackBridgeDispatchFailedException}
   * so the router classifies the routing-log row as {@code AI_UNAVAILABLE} (matching the prior Noop
   * throw semantics). The dispatcher's catch maps the throw without poisoning peer destinations or
   * the original feedback transaction (ticket §22). The idempotency table is the authoritative
   * record of the bridge outcome; the throw is how the routing log learns about it.
   */
  protected RuntimeException failed(UUID feedbackId, Destination destination, Throwable cause) {
    recordOutcome(feedbackId, destination, BridgeDispatchStatus.FAILED);
    return new FeedbackBridgeDispatchFailedException(destination, feedbackId, cause);
  }
}
