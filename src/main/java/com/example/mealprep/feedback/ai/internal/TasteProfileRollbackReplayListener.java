package com.example.mealprep.feedback.ai.internal;

import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator.RunResult;
import com.example.mealprep.feedback.config.FeedbackAsyncConfig;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.event.TasteProfileRollbackReplayRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ROLLBACK-replay trigger (preference-01h §5-7): listens for {@link
 * TasteProfileRollbackReplayRequestedEvent} (published AFTER_COMMIT by {@code
 * TasteProfileServiceImpl.rollbackTasteProfile} once the document has been reverted and the {@code
 * feedbackCursor} reset to the rolled-back-to version's anchor) and re-runs the 01g delta pipeline
 * to re-derive the AI deltas forward from that cursor.
 *
 * <p>This honours the {@code FeedbackReplayService} seam named in {@code lld/preference.md:571}:
 * the preference module delegates replay rather than consuming {@code FeedbackProcessedEvent}
 * itself (locked decision, {@code lld/preference.md:692}). The replay re-derivation is the {@code
 * preference-01g} {@link TasteProfileDeltaOrchestrator} re-run — the same collaborator the BATCH
 * and MANUAL triggers drive.
 *
 * <p><b>AFTER_COMMIT + REQUIRES_NEW (decision-log 0010).</b> This listener fires after the rollback
 * tx commits, then writes via {@code applyDeltas}. A plain {@code @Transactional} does NOT commit
 * in the AFTER_COMMIT phase, so the listener method is deliberately <b>not</b>
 * {@code @Transactional}; the orchestrator wraps its {@code applyDeltas} write in a {@code
 * REQUIRES_NEW} {@code TransactionTemplate}, and the AI call runs strictly outside any tx (mirrors
 * {@code PreferenceRefreshRequestedListener} and {@code FeedbackClassificationListener}).
 *
 * <p>The trigger was user-initiated (a rollback) but the replay <em>application</em> is AI — the
 * applier stamps {@code actor_type = AI} on the re-derived delta-apply audit row (the user-side
 * {@code ROLLED_BACK} / {@code actor = USER} row was already written by {@code
 * rollbackTasteProfile} inside the rollback tx). The event's {@code traceId} carries the rollback
 * origin trace; {@code fromFeedbackCursor} / {@code toCursorBefore}, when both present, bound the
 * explicit replay range.
 *
 * <p><b>Replay-pending note (preference-01h §6).</b> The 01g orchestrator gathers its batch from
 * the profile's last-applied cursor; the exact deterministic re-gather window is owned by 01g. This
 * listener wires the delegation (the document revert + cursor reset is already fully persisted by
 * the rollback tx — revert works standalone); the forward re-derivation rides the orchestrator. If
 * the orchestrator finds no feedback to replay it logs and no-ops, which is the correct degraded
 * behaviour.
 */
@Component
public class TasteProfileRollbackReplayListener {

  private static final Logger log =
      LoggerFactory.getLogger(TasteProfileRollbackReplayListener.class);

  private final TasteProfileDeltaOrchestrator orchestrator;

  public TasteProfileRollbackReplayListener(TasteProfileDeltaOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  /**
   * Fired AFTER_COMMIT of {@code rollbackTasteProfile}. {@code fallbackExecution = true} so a
   * rollback driven outside a transaction (e.g. a future direct service call) still triggers the
   * replay rather than being silently dropped.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  @Async(FeedbackAsyncConfig.CLASSIFICATION_POOL)
  public void onRollbackReplayRequested(TasteProfileRollbackReplayRequestedEvent event) {
    handle(event);
  }

  /** Visible for unit/integration testing without a surrounding transaction. */
  public RunResult handle(TasteProfileRollbackReplayRequestedEvent event) {
    String rangeStart = blankToNull(event.fromFeedbackCursor());
    String rangeEnd = blankToNull(event.toCursorBefore());
    // Honour the explicit replay range only when BOTH ends are supplied; otherwise the orchestrator
    // uses the since-cursor default (which the rollback already reset to the target version).
    boolean explicitRange = rangeStart != null && rangeEnd != null;
    try {
      RunResult result =
          orchestrator.run(
              event.userId(),
              TasteProfileTrigger.BATCH,
              event.traceId(),
              explicitRange ? rangeStart : null,
              explicitRange ? rangeEnd : null);
      log.info(
          "preference rollback replay handled userId={} restoredVersion={} traceId={} result={}",
          event.userId(),
          event.restoredDocumentVersion(),
          event.traceId(),
          result);
      return result;
    } catch (RuntimeException e) {
      // Never let an exception escape the AFTER_COMMIT listener edge — the revert is already
      // persisted; a failed replay is a degraded-but-recoverable state (the next feedback / weekly
      // sweep re-derives).
      log.error(
          "preference rollback replay failed userId={} restoredVersion={} traceId={}",
          event.userId(),
          event.restoredDocumentVersion(),
          event.traceId(),
          e);
      return RunResult.DELTA_INVALID;
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
