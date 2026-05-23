package com.example.mealprep.feedback.ai.internal;

import com.example.mealprep.feedback.ai.config.PreferenceDeltaProperties;
import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator.RunResult;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * BATCH trigger (preference-01g §6): increments a per-user PREFERENCE-routed counter on every
 * processed feedback whose destinations include {@link Destination#PREFERENCE}; on the {@code
 * batchThreshold}-th (default 5) it runs a delta-update and resets the counter.
 *
 * <p>The counter lives in the feedback module ({@link PreferenceDeltaCursorService}) — the
 * preference module does NOT consume {@code FeedbackProcessedEvent} (locked decision,
 * lld/preference.md:692). This listener fires AFTER_COMMIT + {@code @Async} so the AI call never
 * runs on the publisher's thread and the cursor increment sees the committed routing state. It is
 * NOT itself {@code @Transactional}: the cursor write opens its own {@code REQUIRES_NEW} tx via
 * {@link PreferenceDeltaCursorService}, and the orchestrator's apply does likewise — the AI call
 * happens between, outside any tx.
 */
@Component
public class PreferenceDeltaBatchTrigger {

  private static final Logger log = LoggerFactory.getLogger(PreferenceDeltaBatchTrigger.class);

  private final PreferenceDeltaCursorService cursorService;
  private final TasteProfileDeltaOrchestrator orchestrator;
  private final PreferenceDeltaProperties properties;

  public PreferenceDeltaBatchTrigger(
      PreferenceDeltaCursorService cursorService,
      TasteProfileDeltaOrchestrator orchestrator,
      PreferenceDeltaProperties properties) {
    this.cursorService = cursorService;
    this.orchestrator = orchestrator;
    this.properties = properties;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Async
  public void onFeedbackProcessed(FeedbackProcessedEvent event) {
    if (!event.destinationsTouched().contains(Destination.PREFERENCE)) {
      return;
    }
    onPreferenceFeedback(event.userId(), event.feedbackId());
  }

  /**
   * Increment the user's pending count; on the Nth PREFERENCE-routed feedback run a BATCH delta
   * -update and reset. Visible for unit/integration testing (call directly, no surrounding tx).
   *
   * <p>Returns the {@link RunResult} when a run fired on this call, else {@code null} (still
   * accumulating). Purely a log/test signal.
   */
  public RunResult onPreferenceFeedback(java.util.UUID userId, java.util.UUID feedbackId) {
    int pending;
    try {
      pending = cursorService.recordPreferenceFeedback(userId, feedbackId);
    } catch (DataIntegrityViolationException raced) {
      // A concurrent first-insert won the unique index; re-read-and-increment is safe (the trigger
      // is at-least-once). One retry covers the race.
      pending = cursorService.recordPreferenceFeedback(userId, feedbackId);
    }

    int threshold = properties.batchThreshold();
    if (pending < threshold) {
      log.debug(
          "preference batch trigger accumulating userId={} pending={} threshold={}",
          userId,
          pending,
          threshold);
      return null;
    }

    log.info(
        "preference batch trigger fired userId={} pending={} threshold={}",
        userId,
        pending,
        threshold);
    RunResult result = orchestrator.run(userId, TasteProfileTrigger.BATCH, null, null, null);
    // Reset whenever the batch was processed (applied / conservatively empty / rejected); leave the
    // counter intact when the run could not proceed (AI down / no profile / empty batch) so the
    // next
    // feedback or the weekly sweep retries.
    if (result == RunResult.APPLIED
        || result == RunResult.NO_DELTAS
        || result == RunResult.DELTA_INVALID) {
      cursorService.markRun(userId, TasteProfileTrigger.BATCH.name());
    }
    return result;
  }
}
