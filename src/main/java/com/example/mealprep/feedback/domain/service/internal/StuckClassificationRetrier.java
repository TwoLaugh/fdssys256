package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.config.FeedbackRetrySweepProperties;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sibling component for the per-entry retry-sweep transaction (feedback-01i). Extracted out of
 * {@code FeedbackServiceImpl} so the {@code REQUIRES_NEW} {@link Transactional} boundary is
 * honoured by Spring's proxy — calling a {@code @Transactional} method from another method of the
 * <em>same</em> bean bypasses the proxy and silently drops the annotation (wave-3 self-invocation
 * gotcha). Each entry is handled in its own transaction so one poison entry does not roll back the
 * others' escalations, mirroring {@link ClarificationExpirer}.
 *
 * <p>Two branches, gated on the entry's age since {@code createdAt}:
 *
 * <ul>
 *   <li><b>Escalate</b> (age &gt; {@code escalateAfter}, default 24h) — flip to {@code FAILED},
 *       stamp {@code lastClassifiedAt}, and publish a terminal {@link FeedbackProcessedEvent}
 *       ({@code partialFailure=true}) so the user's confirmation view + downstream observers see
 *       the terminal state. The row stays in the table for postmortem (LLD line 576).
 *   <li><b>Re-classify</b> (else) — re-publish {@link FeedbackSubmittedEvent} carrying the entry's
 *       <em>existing</em> {@code traceId}. The AFTER_COMMIT {@code @Async} {@link
 *       FeedbackClassificationListener} picks it up, flips {@code RECEIVED → CLASSIFYING} +
 *       increments attempts in its own {@code REQUIRES_NEW} tx, and runs the AI call off the
 *       scheduler thread. Re-using the proven async pipeline keeps the AI call off the
 *       {@code @Scheduled} thread and keeps the decision log linked across attempts — exactly as
 *       {@code answerClarificationQuery} does.
 * </ul>
 *
 * <p>Both writes/publishes happen inside this active {@code REQUIRES_NEW} tx so
 * {@code @TransactionalEventListener(AFTER_COMMIT)} consumers fire on commit (Spring silently drops
 * AFTER_COMMIT events published with no active tx).
 *
 * <p>Idempotency: the entry is re-read under the new transaction and the method no-ops if the entry
 * raced to a non-retryable status (e.g. another sweep tick or the async listener already moved it
 * to {@code ROUTED}/{@code CLARIFICATION_PENDING}/{@code FAILED}). A genuinely mid-AI-call {@code
 * CLASSIFYING} entry (seconds old) is never selected by the 5-min stuck window, so a re-published
 * event is at worst an idempotent re-attempt, never corruption.
 */
@Component
public class StuckClassificationRetrier {

  /** Statuses the sweep treats as "stuck" and eligible for retry/escalation. */
  static final Set<SubmissionStatus> RETRYABLE_STATUSES =
      EnumSet.of(SubmissionStatus.RECEIVED, SubmissionStatus.CLASSIFYING);

  private final FeedbackEntryRepository entryRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final FeedbackRetrySweepProperties properties;
  private final Clock clock;

  public StuckClassificationRetrier(
      FeedbackEntryRepository entryRepository,
      ApplicationEventPublisher eventPublisher,
      FeedbackRetrySweepProperties properties,
      Clock clock) {
    this.entryRepository = entryRepository;
    this.eventPublisher = eventPublisher;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Retry or escalate a single stuck entry in its own transaction. Re-reads the row and no-ops if
   * it raced to a non-retryable state (idempotent — concurrent sweep or the async listener
   * progressed it). Returns {@code true} when this call took a terminal escalation action, {@code
   * false} otherwise (re-classify dispatched, or no-op) — purely a log/test signal, not
   * behavioural.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean retryOne(UUID feedbackId) {
    FeedbackEntry entry = entryRepository.findById(feedbackId).orElse(null);
    if (entry == null || !RETRYABLE_STATUSES.contains(entry.getSubmissionStatus())) {
      return false; // raced to a terminal / non-retryable state — no-op, idempotent.
    }

    Instant now = clock.instant();
    boolean escalate = entry.getCreatedAt().isBefore(now.minus(properties.escalateAfter()));

    if (escalate) {
      int rows =
          entryRepository.updateSubmissionStatusAndLastClassifiedAt(
              feedbackId, SubmissionStatus.FAILED, now);
      if (rows == 0) {
        return false; // entry vanished concurrently — nothing to escalate.
      }
      // Mirror FeedbackClassificationListener.markFailed: terminal FeedbackProcessedEvent so the
      // confirmation view + downstream observers see the terminal state. Published inside this tx
      // so the AFTER_COMMIT listener fires.
      eventPublisher.publishEvent(
          new FeedbackProcessedEvent(
              feedbackId,
              entry.getUserId(),
              Set.of(),
              Set.of(),
              true,
              false,
              entry.getTraceId(),
              now));
      return true;
    }

    // Re-classify: re-publish FeedbackSubmittedEvent with the SAME traceId (keeps the decision log
    // linked across attempts — exactly as answerClarificationQuery does). The AFTER_COMMIT @Async
    // listener owns the CLASSIFYING flip + AI call.
    eventPublisher.publishEvent(
        new FeedbackSubmittedEvent(
            feedbackId,
            entry.getUserId(),
            entry.getUiContext() == null ? null : entry.getUiContext().screen(),
            entry.getTraceId(),
            now));
    return false;
  }
}
