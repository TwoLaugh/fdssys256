package com.example.mealprep.feedback.ai.internal;

import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator.RunResult;
import com.example.mealprep.feedback.config.FeedbackAsyncConfig;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.event.TasteProfileRefreshRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * MANUAL trigger (preference-01g §6-7): listens for {@link TasteProfileRefreshRequestedEvent}
 * (published AFTER_COMMIT by {@code TasteProfileServiceImpl.triggerRefresh} — which had no listener
 * before this ticket) and runs a delta-update.
 *
 * <p><b>AFTER_COMMIT + REQUIRES_NEW (decision-log 0010).</b> This listener fires after the {@code
 * triggerRefresh} tx commits, then writes via {@code applyDeltas}. A plain {@code @Transactional}
 * does NOT commit in the AFTER_COMMIT phase, so the listener method is deliberately <b>not</b>
 * {@code @Transactional}; the orchestrator wraps its {@code applyDeltas} write in a {@code
 * REQUIRES_NEW} {@code TransactionTemplate}, and the AI call runs strictly outside any tx (mirrors
 * {@code FeedbackClassificationListener}).
 *
 * <p>The trigger was user-initiated but the <em>application</em> is AI — the applier already stamps
 * {@code actor_type=AI} on the delta-apply audit row (the user-side {@code REFRESH_TRIGGERED} /
 * {@code actor=USER} row was written by {@code triggerRefresh}). The event's {@code traceId} is the
 * origin trace; {@code feedbackRangeStart}/{@code feedbackRangeEnd}, when both present, override
 * the since-cursor default for an explicit-range refresh.
 */
@Component
public class PreferenceRefreshRequestedListener {

  private static final Logger log =
      LoggerFactory.getLogger(PreferenceRefreshRequestedListener.class);

  private final TasteProfileDeltaOrchestrator orchestrator;

  public PreferenceRefreshRequestedListener(TasteProfileDeltaOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  /**
   * Fired AFTER_COMMIT of {@code triggerRefresh}. Falls back to a synchronous in-thread run; the AI
   * call inside the orchestrator is what dominates the latency, but the controller already returned
   * the current state to the user (the refresh is documented as asynchronous at the API edge).
   *
   * <p>{@code fallbackExecution = true} so a manual {@code triggerRefresh} invoked outside a
   * transaction (e.g. a future direct service call) still drives the listener rather than being
   * silently dropped.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Async(FeedbackAsyncConfig.CLASSIFICATION_POOL)
  public void onRefreshRequested(TasteProfileRefreshRequestedEvent event) {
    handle(event);
  }

  /** Visible for unit/integration testing without a surrounding transaction. */
  public RunResult handle(TasteProfileRefreshRequestedEvent event) {
    String rangeStart = blankToNull(event.feedbackRangeStart());
    String rangeEnd = blankToNull(event.feedbackRangeEnd());
    // Honour the explicit range only when BOTH ends are supplied (ticket edge case); otherwise the
    // orchestrator uses the since-cursor default.
    boolean explicitRange = rangeStart != null && rangeEnd != null;
    try {
      RunResult result =
          orchestrator.run(
              event.userId(),
              TasteProfileTrigger.MANUAL,
              event.traceId(),
              explicitRange ? rangeStart : null,
              explicitRange ? rangeEnd : null);
      log.info(
          "preference manual refresh handled userId={} traceId={} result={}",
          event.userId(),
          event.traceId(),
          result);
      return result;
    } catch (RuntimeException e) {
      // Never let an exception escape the AFTER_COMMIT listener edge.
      log.error(
          "preference manual refresh failed userId={} traceId={}",
          event.userId(),
          event.traceId(),
          e);
      return RunResult.DELTA_INVALID;
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
