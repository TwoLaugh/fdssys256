package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallErrorKind;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.internal.AiCallRecorder;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@code AiCallRecorder}. Baseline left every recorder mutant uncovered because the
 * production path runs inside REQUIRES_NEW transactions exercised only by ITs; pure-unit coverage
 * here is enough to kill the setters/getters and the {@code orElseThrow} branches.
 *
 * <p>The collaborator is the {@link AiCallLogRepository} (legitimate mock); the {@link Clock} is
 * fixed so {@code completed_at} is deterministic.
 */
@ExtendWith(MockitoExtension.class)
class AiCallRecorderTest {

  @Mock private AiCallLogRepository repository;

  private final Instant now = Instant.parse("2026-05-08T12:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private AiCallRecorder recorder() {
    return new AiCallRecorder(repository, clock);
  }

  // ---------------- recordPending ----------------

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code recordPending:51} NullReturnVals (return value must be a real UUID).
   *   <li>VoidMethodCall on {@code repository.save} — verified.
   * </ul>
   */
  @Test
  void recordPending_savesPendingRowAndReturnsItsId() {
    AiTask<String> task =
        AiTestData.task(String.class)
            .ofType(TaskType.FEEDBACK_CLASSIFICATION)
            .withUserId(UUID.randomUUID())
            .build();

    UUID returned = recorder().recordPending(task, ModelTier.CHEAP, "haiku-id");

    assertThat(returned).isNotNull();
    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    AiCallLog saved = cap.getValue();
    assertThat(saved.getId()).isEqualTo(returned);
    assertThat(saved.getStatus()).isEqualTo(CallStatus.PENDING);
    assertThat(saved.getTaskType()).isEqualTo(TaskType.FEEDBACK_CLASSIFICATION);
    assertThat(saved.getModelTier()).isEqualTo(ModelTier.CHEAP);
    assertThat(saved.getModelId()).isEqualTo("haiku-id");
    assertThat(saved.getPromptRefName()).isEqualTo("test/echo");
    assertThat(saved.getPromptRefVersion()).isEqualTo(1);
    assertThat(saved.getUserId()).isEqualTo(task.userId().orElseThrow());
  }

  /** A system-initiated task (no userId / traceId) should still record. */
  @Test
  void recordPending_systemInitiated_savesWithNullUserAndTrace() {
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    recorder().recordPending(task, ModelTier.MID, "sonnet-id");

    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    assertThat(cap.getValue().getUserId()).isNull();
    assertThat(cap.getValue().getTraceId()).isNull();
    assertThat(cap.getValue().getModelTier()).isEqualTo(ModelTier.MID);
  }

  // ---------------- recordEmbeddingPending ----------------

  /**
   * Kills {@code recordEmbeddingPending:67} NullReturnVals — and the prompt-ref-null assertions.
   */
  @Test
  void recordEmbeddingPending_savesPendingRow_withNullPromptRefFields() {
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    UUID returned =
        recorder()
            .recordEmbeddingPending(
                userId,
                traceId,
                TaskType.EMBEDDING_PREFERENCE_TASTE_VECTOR,
                ModelTier.CHEAP,
                "text-embedding-3-small");
    assertThat(returned).isNotNull();
    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    AiCallLog saved = cap.getValue();
    assertThat(saved.getId()).isEqualTo(returned);
    assertThat(saved.getUserId()).isEqualTo(userId);
    assertThat(saved.getTraceId()).isEqualTo(traceId);
    assertThat(saved.getStatus()).isEqualTo(CallStatus.PENDING);
    assertThat(saved.getTaskType()).isEqualTo(TaskType.EMBEDDING_PREFERENCE_TASTE_VECTOR);
    assertThat(saved.getModelId()).isEqualTo("text-embedding-3-small");
    assertThat(saved.getPromptRefName()).isNull();
    assertThat(saved.getPromptRefVersion()).isNull();
  }

  // ---------------- recordSuccess ----------------

  /**
   * Kills {@code recordSuccess(callId, ..., latencyMs):74} VoidMethodCall — the four-arg overload
   * must delegate with cost==0.
   */
  @Test
  void recordSuccess_fourArg_delegatesWithZeroCost() {
    UUID callId = UUID.randomUUID();
    AiCallLog row =
        new AiCallLog(
            callId,
            null,
            null,
            TaskType.FEEDBACK_CLASSIFICATION,
            ModelTier.CHEAP,
            "haiku-id",
            "p",
            1,
            CallStatus.PENDING);
    when(repository.findById(callId)).thenReturn(Optional.of(row));

    recorder().recordSuccess(callId, 5, 7, 42);

    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    AiCallLog saved = cap.getValue();
    assertThat(saved.getStatus()).isEqualTo(CallStatus.SUCCEEDED);
    assertThat(saved.getRequestTokens()).isEqualTo(5);
    assertThat(saved.getResponseTokens()).isEqualTo(7);
    assertThat(saved.getLatencyMs()).isEqualTo(42);
    assertThat(saved.getCostMicroPence()).isEqualTo(0L);
    assertThat(saved.getCompletedAt()).isEqualTo(now);
  }

  /**
   * Kills {@code recordSuccess(...,cost):92-99} VoidMethodCall — every setter must fire. The Math
   * max-on-cost mutator is killed by feeding a negative cost and asserting it clamps to zero.
   */
  @Test
  void recordSuccess_fiveArg_setsAllFields_andClampsNegativeCostToZero() {
    UUID callId = UUID.randomUUID();
    AiCallLog row =
        new AiCallLog(
            callId,
            null,
            null,
            TaskType.RECIPE_ADAPTATION,
            ModelTier.MID,
            "sonnet-id",
            "r",
            2,
            CallStatus.PENDING);
    when(repository.findById(callId)).thenReturn(Optional.of(row));

    recorder().recordSuccess(callId, 11, 22, 333, -1L);

    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    AiCallLog saved = cap.getValue();
    assertThat(saved.getStatus()).isEqualTo(CallStatus.SUCCEEDED);
    assertThat(saved.getRequestTokens()).isEqualTo(11);
    assertThat(saved.getResponseTokens()).isEqualTo(22);
    assertThat(saved.getLatencyMs()).isEqualTo(333);
    assertThat(saved.getCostMicroPence()).isEqualTo(0L); // clamped from -1
    assertThat(saved.getCompletedAt()).isEqualTo(now);
  }

  /** Positive cost is preserved verbatim. */
  @Test
  void recordSuccess_positiveCost_isPreserved() {
    UUID callId = UUID.randomUUID();
    AiCallLog row =
        new AiCallLog(
            callId,
            null,
            null,
            TaskType.RECIPE_ADAPTATION,
            ModelTier.MID,
            "sonnet-id",
            "r",
            2,
            CallStatus.PENDING);
    when(repository.findById(callId)).thenReturn(Optional.of(row));

    recorder().recordSuccess(callId, 1, 1, 1, 12345L);

    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    assertThat(cap.getValue().getCostMicroPence()).isEqualTo(12345L);
  }

  /**
   * Kills {@code lambda$recordSuccess$0:92} NullReturnVals — when the row is missing, throw
   * IllegalStateException (not return null).
   */
  @Test
  void recordSuccess_rowMissing_throwsIllegalState() {
    UUID callId = UUID.randomUUID();
    when(repository.findById(callId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> recorder().recordSuccess(callId, 1, 1, 1, 1L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(callId.toString());
    verify(repository, times(0)).save(any());
  }

  // ---------------- recordFailure ----------------

  /**
   * Kills {@code recordFailure:108-112} VoidMethodCall — every setter must fire, with the right
   * CallStatus + errorKind + completedAt + latency.
   */
  @Test
  void recordFailure_setsAllFields() {
    UUID callId = UUID.randomUUID();
    AiCallLog row =
        new AiCallLog(
            callId,
            null,
            null,
            TaskType.FEEDBACK_CLASSIFICATION,
            ModelTier.CHEAP,
            "haiku-id",
            "p",
            1,
            CallStatus.PENDING);
    when(repository.findById(callId)).thenReturn(Optional.of(row));

    recorder().recordFailure(callId, CallErrorKind.AI_UNAVAILABLE, 999);

    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    AiCallLog saved = cap.getValue();
    assertThat(saved.getStatus()).isEqualTo(CallStatus.FAILED);
    assertThat(saved.getErrorKind()).isEqualTo(CallErrorKind.AI_UNAVAILABLE);
    assertThat(saved.getLatencyMs()).isEqualTo(999);
    assertThat(saved.getCompletedAt()).isEqualTo(now);
  }

  /** Kills {@code lambda$recordFailure$1:108} NullReturnVals — missing row → IllegalState. */
  @Test
  void recordFailure_rowMissing_throwsIllegalState() {
    UUID callId = UUID.randomUUID();
    when(repository.findById(callId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> recorder().recordFailure(callId, CallErrorKind.AI_UNAVAILABLE, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(callId.toString());
  }
}
