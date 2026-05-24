package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.ai.config.PreferenceDeltaProperties;
import com.example.mealprep.feedback.ai.internal.PreferenceDeltaBatchTrigger;
import com.example.mealprep.feedback.ai.internal.PreferenceDeltaCursorService;
import com.example.mealprep.feedback.ai.internal.PreferenceDeltaTriggerScheduler;
import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator;
import com.example.mealprep.feedback.ai.internal.TasteProfileDeltaOrchestrator.RunResult;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the BATCH ({@link PreferenceDeltaBatchTrigger}) and WEEKLY ({@link
 * PreferenceDeltaTriggerScheduler}) triggers — counter accumulation / fire-on-threshold / reset,
 * and the per-user weekly sweep.
 */
class PreferenceDeltaTriggerTest {

  private final PreferenceDeltaCursorService cursorService =
      Mockito.mock(PreferenceDeltaCursorService.class);
  private final TasteProfileDeltaOrchestrator orchestrator =
      Mockito.mock(TasteProfileDeltaOrchestrator.class);
  private final PreferenceDeltaProperties properties = new PreferenceDeltaProperties(null, 5, true);

  private final PreferenceDeltaBatchTrigger batchTrigger =
      new PreferenceDeltaBatchTrigger(cursorService, orchestrator, properties);
  private final PreferenceDeltaTriggerScheduler scheduler =
      new PreferenceDeltaTriggerScheduler(orchestrator, cursorService);

  private final UUID userId = UUID.randomUUID();
  private final UUID feedbackId = UUID.randomUUID();

  // ---------------- BATCH ----------------

  @Test
  void batch_belowThreshold_accumulatesWithoutRunning() {
    when(cursorService.recordPreferenceFeedback(userId, feedbackId)).thenReturn(3);

    RunResult result = batchTrigger.onPreferenceFeedback(userId, feedbackId);

    assertThat(result).isNull();
    verify(orchestrator, never()).run(any(), any(), any(), any(), any());
    verify(cursorService, never()).markRun(any(), any());
  }

  @Test
  void batch_atThreshold_runsBatchAndResetsCursor() {
    when(cursorService.recordPreferenceFeedback(userId, feedbackId)).thenReturn(5);
    when(orchestrator.run(eq(userId), eq(TasteProfileTrigger.BATCH), any(), any(), any()))
        .thenReturn(RunResult.APPLIED);

    RunResult result = batchTrigger.onPreferenceFeedback(userId, feedbackId);

    assertThat(result).isEqualTo(RunResult.APPLIED);
    verify(orchestrator)
        .run(eq(userId), eq(TasteProfileTrigger.BATCH), eq(null), eq(null), eq(null));
    verify(cursorService).markRun(userId, TasteProfileTrigger.BATCH.name());
  }

  @Test
  void batch_atThreshold_aiUnavailable_doesNotResetCursor() {
    when(cursorService.recordPreferenceFeedback(userId, feedbackId)).thenReturn(5);
    when(orchestrator.run(eq(userId), eq(TasteProfileTrigger.BATCH), any(), any(), any()))
        .thenReturn(RunResult.AI_UNAVAILABLE);

    batchTrigger.onPreferenceFeedback(userId, feedbackId);

    // Counter intact so the next feedback / weekly sweep retries.
    verify(cursorService, never()).markRun(any(), any());
  }

  // ---------------- WEEKLY ----------------

  @Test
  void weekly_sweepsEachPendingUser_resetsAppliedAndCountsApplied() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    UUID userC = UUID.randomUUID();
    when(cursorService.usersWithPendingFeedback()).thenReturn(List.of(userA, userB, userC));
    when(orchestrator.run(eq(userA), eq(TasteProfileTrigger.WEEKLY), any(), any(), any()))
        .thenReturn(RunResult.APPLIED);
    when(orchestrator.run(eq(userB), eq(TasteProfileTrigger.WEEKLY), any(), any(), any()))
        .thenReturn(RunResult.NO_DELTAS);
    when(orchestrator.run(eq(userC), eq(TasteProfileTrigger.WEEKLY), any(), any(), any()))
        .thenReturn(RunResult.AI_UNAVAILABLE);

    int applied = scheduler.sweep();

    assertThat(applied).isEqualTo(1); // only userA applied
    verify(cursorService).markRun(userA, TasteProfileTrigger.WEEKLY.name());
    verify(cursorService).markRun(userB, TasteProfileTrigger.WEEKLY.name());
    // userC could not proceed (AI down) → counter left intact for next sweep.
    verify(cursorService, never()).markRun(userC, TasteProfileTrigger.WEEKLY.name());
  }

  @Test
  void weekly_oneUserThrows_otherUsersStillSwept() {
    UUID userA = UUID.randomUUID();
    UUID userB = UUID.randomUUID();
    when(cursorService.usersWithPendingFeedback()).thenReturn(List.of(userA, userB));
    when(orchestrator.run(eq(userA), eq(TasteProfileTrigger.WEEKLY), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));
    when(orchestrator.run(eq(userB), eq(TasteProfileTrigger.WEEKLY), any(), any(), any()))
        .thenReturn(RunResult.APPLIED);

    int applied = scheduler.sweep();

    assertThat(applied).isEqualTo(1);
    verify(orchestrator, times(1))
        .run(eq(userB), eq(TasteProfileTrigger.WEEKLY), any(), any(), any());
  }

  @Test
  void weekly_noPendingUsers_noRuns() {
    when(cursorService.usersWithPendingFeedback()).thenReturn(List.of());

    assertThat(scheduler.sweep()).isZero();
    verify(orchestrator, never()).run(any(), any(), any(), any(), any());
  }
}
