package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.ai.internal.PreferenceRefreshRequestedListener;
import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator;
import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator.RunResult;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.event.TasteProfileRefreshRequestedEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Unit tests for the MANUAL trigger listener — trigger type, trace, explicit-range honouring. */
class PreferenceRefreshRequestedListenerTest {

  private final TasteProfileDeltaOrchestrator orchestrator =
      Mockito.mock(TasteProfileDeltaOrchestrator.class);
  private final PreferenceRefreshRequestedListener listener =
      new PreferenceRefreshRequestedListener(orchestrator);

  private final UUID userId = UUID.randomUUID();
  private final UUID profileId = UUID.randomUUID();
  private final UUID traceId = UUID.randomUUID();

  @Test
  void handle_noRange_runsManualWithSinceCursorDefault() {
    when(orchestrator.run(
            eq(userId), eq(TasteProfileTrigger.MANUAL), eq(traceId), eq(null), eq(null)))
        .thenReturn(RunResult.APPLIED);

    RunResult result = listener.handle(event(null, null));

    assertThat(result).isEqualTo(RunResult.APPLIED);
    verify(orchestrator)
        .run(eq(userId), eq(TasteProfileTrigger.MANUAL), eq(traceId), eq(null), eq(null));
  }

  @Test
  void handle_explicitRange_passesBothEndsThrough() {
    when(orchestrator.run(
            eq(userId),
            eq(TasteProfileTrigger.MANUAL),
            eq(traceId),
            eq("feedback-A"),
            eq("feedback-B")))
        .thenReturn(RunResult.APPLIED);

    listener.handle(event("feedback-A", "feedback-B"));

    verify(orchestrator)
        .run(
            eq(userId),
            eq(TasteProfileTrigger.MANUAL),
            eq(traceId),
            eq("feedback-A"),
            eq("feedback-B"));
  }

  @Test
  void handle_partialRange_treatedAsNoOverride() {
    // Only one end present → not an explicit range; orchestrator gets null overrides.
    when(orchestrator.run(
            eq(userId), eq(TasteProfileTrigger.MANUAL), eq(traceId), eq(null), eq(null)))
        .thenReturn(RunResult.NO_DELTAS);

    listener.handle(event("feedback-A", null));

    verify(orchestrator)
        .run(eq(userId), eq(TasteProfileTrigger.MANUAL), eq(traceId), eq(null), eq(null));
  }

  @Test
  void handle_orchestratorThrows_neverEscapesListenerEdge() {
    when(orchestrator.run(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));

    RunResult result = listener.handle(event(null, null));

    assertThat(result).isEqualTo(RunResult.DELTA_INVALID);
  }

  private TasteProfileRefreshRequestedEvent event(String start, String end) {
    return new TasteProfileRefreshRequestedEvent(
        userId, profileId, start, end, traceId, Instant.now());
  }
}
