package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator;
import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator.RunResult;
import com.example.mealprep.feedback.ai.internal.TasteProfileRollbackReplayListener;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.event.TasteProfileRollbackReplayRequestedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the ROLLBACK-replay trigger listener (preference-01h) — trigger type, trace,
 * explicit-range honouring, and the never-escape-the-edge contract.
 */
class TasteProfileRollbackReplayListenerTest {

  private final TasteProfileDeltaOrchestrator orchestrator =
      Mockito.mock(TasteProfileDeltaOrchestrator.class);
  private final TasteProfileRollbackReplayListener listener =
      new TasteProfileRollbackReplayListener(orchestrator);

  private final UUID userId = UUID.randomUUID();
  private final UUID profileId = UUID.randomUUID();
  private final UUID traceId = UUID.randomUUID();

  @Test
  void handle_bothCursorsPresent_runsBatchWithExplicitRange() {
    when(orchestrator.run(
            eq(userId),
            eq(TasteProfileTrigger.BATCH),
            eq(traceId),
            eq("feedback-from"),
            eq("feedback-to")))
        .thenReturn(RunResult.APPLIED);

    RunResult result = listener.handle(event("feedback-from", "feedback-to"));

    assertThat(result).isEqualTo(RunResult.APPLIED);
    verify(orchestrator)
        .run(
            eq(userId),
            eq(TasteProfileTrigger.BATCH),
            eq(traceId),
            eq("feedback-from"),
            eq("feedback-to"));
  }

  @Test
  void handle_onlyFromCursor_treatedAsNoOverride_sinceCursorDefault() {
    when(orchestrator.run(
            eq(userId), eq(TasteProfileTrigger.BATCH), eq(traceId), eq(null), eq(null)))
        .thenReturn(RunResult.SKIPPED_EMPTY_BATCH);

    listener.handle(event("feedback-from", null));

    verify(orchestrator)
        .run(eq(userId), eq(TasteProfileTrigger.BATCH), eq(traceId), eq(null), eq(null));
  }

  @Test
  void handle_noCursors_runsBatchWithSinceCursorDefault() {
    when(orchestrator.run(
            eq(userId), eq(TasteProfileTrigger.BATCH), eq(traceId), eq(null), eq(null)))
        .thenReturn(RunResult.NO_DELTAS);

    RunResult result = listener.handle(event(null, null));

    assertThat(result).isEqualTo(RunResult.NO_DELTAS);
  }

  @Test
  void handle_orchestratorThrows_neverEscapesListenerEdge() {
    when(orchestrator.run(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));

    RunResult result = listener.handle(event("feedback-from", "feedback-to"));

    assertThat(result).isEqualTo(RunResult.DELTA_INVALID);
  }

  private TasteProfileRollbackReplayRequestedEvent event(String from, String to) {
    return new TasteProfileRollbackReplayRequestedEvent(
        userId, profileId, 16, from, to, traceId, Instant.now());
  }
}
