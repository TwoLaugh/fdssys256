package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.entity.CallErrorKind;
import com.example.mealprep.ai.domain.service.internal.AiCallRecorder;
import com.example.mealprep.ai.domain.service.internal.AiServiceImpl;
import com.example.mealprep.ai.domain.service.internal.AnthropicClient;
import com.example.mealprep.ai.domain.service.internal.AnthropicResponse;
import com.example.mealprep.ai.domain.service.internal.CostBudgetGuard;
import com.example.mealprep.ai.domain.service.internal.CostCalculator;
import com.example.mealprep.ai.domain.service.internal.OpenAiEmbeddingClient;
import com.example.mealprep.ai.event.AiCallFailedEvent;
import com.example.mealprep.ai.event.AiCallSucceededEvent;
import com.example.mealprep.ai.event.CostBudgetExceededEvent;
import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link AiServiceImpl}. {@link AnthropicClient}, {@link AiCallRecorder}, and {@link
 * CostBudgetGuard} are mocked at the seam; the real {@link ObjectMapper} and {@link CostCalculator}
 * stay in.
 */
@ExtendWith(MockitoExtension.class)
class AiServiceImplTest {

  @Mock private AnthropicClient anthropicClient;
  @Mock private OpenAiEmbeddingClient embeddingClient;
  @Mock private AiCallRecorder recorder;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private CostBudgetGuard budgetGuard;

  private final AiProperties properties =
      new AiProperties("k", null, "haiku-id", "sonnet-id", "opus-id", 60, 3, null, null, null);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);
  private final CostCalculator costCalculator = new CostCalculator();

  private AiServiceImpl service() {
    return new AiServiceImpl(
        anthropicClient,
        embeddingClient,
        recorder,
        eventPublisher,
        properties,
        objectMapper,
        fixedClock,
        budgetGuard,
        costCalculator);
  }

  @Test
  void execute_happyPath_writesPendingThenSuccess_andPublishesEvent() {
    UUID callId = UUID.randomUUID();
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    when(recorder.recordPending(eq(task), eq(ModelTier.CHEAP), eq("haiku-id"))).thenReturn(callId);
    when(anthropicClient.call(eq(task), eq("haiku-id")))
        .thenReturn(new AnthropicResponse("ok", 12, 4, "haiku-id"));

    String result = service().execute(task);

    assertThat(result).isEqualTo("ok");
    verify(recorder).recordPending(task, ModelTier.CHEAP, "haiku-id");
    verify(budgetGuard).checkOrThrow(task);
    verify(recorder).recordSuccess(eq(callId), eq(12), eq(4), anyInt(), anyLong());
    verify(recorder, never()).recordFailure(any(), any(), anyInt());
    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(events.capture());
    assertThat(events.getValue()).isInstanceOf(AiCallSucceededEvent.class);
    AiCallSucceededEvent ev = (AiCallSucceededEvent) events.getValue();
    assertThat(ev.callId()).isEqualTo(callId);
    assertThat(ev.taskType()).isEqualTo(TaskType.FEEDBACK_CLASSIFICATION);
    // Haiku/CHEAP rate: 79 micropence per input token, 395 micropence per output token.
    assertThat(ev.costMicroPence()).isEqualTo(12L * 79L + 4L * 395L);
  }

  @Test
  void execute_propagatesTraceIdToEvent() {
    UUID callId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    AiTask<String> task =
        AiTestData.task(String.class)
            .ofType(TaskType.FEEDBACK_CLASSIFICATION)
            .withTraceId(traceId)
            .build();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);
    when(anthropicClient.call(any(), any()))
        .thenReturn(new AnthropicResponse("ok", null, null, "haiku-id"));

    service().execute(task);

    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(events.capture());
    AiCallSucceededEvent ev = (AiCallSucceededEvent) events.getValue();
    assertThat(ev.traceId()).isEqualTo(traceId);
  }

  @Test
  void execute_4xxFromUpstream_recordsFailure_publishesFailedEvent_rethrows() {
    UUID callId = UUID.randomUUID();
    AiTask<String> task = AiTestData.task(String.class).build();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);
    when(anthropicClient.call(any(), any()))
        .thenThrow(new AiInvalidRequestException("bad request"));

    assertThatThrownBy(() -> service().execute(task)).isInstanceOf(AiInvalidRequestException.class);

    verify(recorder).recordFailure(eq(callId), eq(CallErrorKind.INVALID_REQUEST), anyInt());
    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(events.capture());
    assertThat(events.getValue()).isInstanceOf(AiCallFailedEvent.class);
    assertThat(((AiCallFailedEvent) events.getValue()).errorKind())
        .isEqualTo(CallErrorKind.INVALID_REQUEST);
  }

  @Test
  void execute_5xxAfterRetries_recordsAiUnavailable() {
    UUID callId = UUID.randomUUID();
    AiTask<String> task = AiTestData.task(String.class).build();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);
    when(anthropicClient.call(any(), any()))
        .thenThrow(new AiUnavailableException("retries exhausted"));

    assertThatThrownBy(() -> service().execute(task)).isInstanceOf(AiUnavailableException.class);
    verify(recorder).recordFailure(eq(callId), eq(CallErrorKind.AI_UNAVAILABLE), anyInt());
  }

  @Test
  void execute_responseDoesNotDeserialise_recordsInvalidResponse() {
    UUID callId = UUID.randomUUID();
    AiTask<TypedPayload> task =
        AiTestData.task(TypedPayload.class).ofType(TaskType.RECIPE_ADAPTATION).build();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);
    when(anthropicClient.call(any(), any()))
        .thenReturn(new AnthropicResponse("not-json", null, null, "haiku-id"));

    assertThatThrownBy(() -> service().execute(task))
        .isInstanceOf(AiInvalidResponseException.class);
    verify(recorder).recordFailure(eq(callId), eq(CallErrorKind.INVALID_RESPONSE), anyInt());
  }

  @Test
  void execute_typedDeserialise_succeeds() {
    UUID callId = UUID.randomUUID();
    AiTask<TypedPayload> task = AiTestData.task(TypedPayload.class).build();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);
    when(anthropicClient.call(any(), any()))
        .thenReturn(new AnthropicResponse("{\"answer\":\"42\"}", 1, 1, "haiku-id"));

    TypedPayload result = service().execute(task);
    assertThat(result.answer()).isEqualTo("42");
  }

  @Test
  void execute_recorderFailureOnFinalize_doesNotMaskOriginal() {
    UUID callId = UUID.randomUUID();
    AiTask<String> task = AiTestData.task(String.class).build();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);
    when(anthropicClient.call(any(), any()))
        .thenThrow(new AiUnavailableException("upstream blew up"));
    Mockito.doThrow(new RuntimeException("audit save failed"))
        .when(recorder)
        .recordFailure(any(), any(), anyInt());

    assertThatThrownBy(() -> service().execute(task))
        .isInstanceOf(AiUnavailableException.class)
        .hasMessageContaining("upstream blew up");
    // Event still publishes; the recorder failure was logged, not surfaced.
    verify(eventPublisher, times(1)).publishEvent(any(AiCallFailedEvent.class));
  }

  @Test
  void execute_nullTask_throwsIllegalArgument() {
    assertThatThrownBy(() -> service().execute(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void embed_nullTask_throwsIllegalArgument() {
    assertThatThrownBy(() -> service().embed(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void embed_emptyInput_throwsIllegalArgument_withoutCallingClient() {
    AiServiceImpl svc = service();
    assertThatThrownBy(() -> svc.embed(new StubEmbeddingTask("")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> svc.embed(new StubEmbeddingTask("   ")))
        .isInstanceOf(IllegalArgumentException.class);
    verify(embeddingClient, never()).embed(any(), any());
    verify(recorder, never()).recordEmbeddingPending(any(), any(), any(), any(), any());
  }

  @Test
  void embed_happyPath_recordsCallLog_publishesEvent_returnsVector() {
    UUID callId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    float[] vec = new float[1536];
    vec[0] = 0.5f;
    when(recorder.recordEmbeddingPending(
            eq(userId),
            any(),
            eq(com.example.mealprep.ai.spi.TaskType.EMBEDDING_PREFERENCE_TASTE_VECTOR),
            eq(ModelTier.CHEAP),
            eq("text-embedding-3-small")))
        .thenReturn(callId);
    when(embeddingClient.embed(eq("hello"), eq("text-embedding-3-small")))
        .thenReturn(new OpenAiEmbeddingClient.EmbeddingResult(vec, 7));

    StubEmbeddingTask task = new StubEmbeddingTask("hello", userId, null);
    float[] result = service().embed(task);

    assertThat(result).isSameAs(vec);
    verify(recorder).recordSuccess(eq(callId), eq(7), eq(0), anyInt());
    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(events.capture());
    AiCallSucceededEvent ev = (AiCallSucceededEvent) events.getValue();
    assertThat(ev.callId()).isEqualTo(callId);
    assertThat(ev.taskType())
        .isEqualTo(com.example.mealprep.ai.spi.TaskType.EMBEDDING_PREFERENCE_TASTE_VECTOR);
  }

  @Test
  void embed_cacheHit_returnsSameInstance_withoutSecondClientCall() {
    UUID callId = UUID.randomUUID();
    float[] vec = new float[1536];
    when(recorder.recordEmbeddingPending(any(), any(), any(), any(), any())).thenReturn(callId);
    when(embeddingClient.embed(eq("same-input"), eq("text-embedding-3-small")))
        .thenReturn(new OpenAiEmbeddingClient.EmbeddingResult(vec, 3));

    AiServiceImpl svc = service();
    float[] first = svc.embed(new StubEmbeddingTask("same-input"));
    float[] second = svc.embed(new StubEmbeddingTask("same-input"));

    assertThat(first).isSameAs(vec);
    assertThat(second).isSameAs(first);
    verify(embeddingClient, times(1)).embed(any(), any());
    verify(recorder, times(1)).recordEmbeddingPending(any(), any(), any(), any(), any());
  }

  @Test
  void embed_differentInputs_doNotShareCacheEntry() {
    UUID callId = UUID.randomUUID();
    float[] vecA = new float[1536];
    float[] vecB = new float[1536];
    when(recorder.recordEmbeddingPending(any(), any(), any(), any(), any())).thenReturn(callId);
    when(embeddingClient.embed(eq("alpha"), eq("text-embedding-3-small")))
        .thenReturn(new OpenAiEmbeddingClient.EmbeddingResult(vecA, 1));
    when(embeddingClient.embed(eq("beta"), eq("text-embedding-3-small")))
        .thenReturn(new OpenAiEmbeddingClient.EmbeddingResult(vecB, 1));

    AiServiceImpl svc = service();
    assertThat(svc.embed(new StubEmbeddingTask("alpha"))).isSameAs(vecA);
    assertThat(svc.embed(new StubEmbeddingTask("beta"))).isSameAs(vecB);
    verify(embeddingClient, times(2)).embed(any(), any());
  }

  @Test
  void embed_unavailable_recordsFailure_publishesFailedEvent_rethrows() {
    UUID callId = UUID.randomUUID();
    when(recorder.recordEmbeddingPending(any(), any(), any(), any(), any())).thenReturn(callId);
    when(embeddingClient.embed(any(), any())).thenThrow(new AiUnavailableException("openai 5xx"));

    assertThatThrownBy(() -> service().embed(new StubEmbeddingTask("x")))
        .isInstanceOf(AiUnavailableException.class);
    verify(recorder).recordFailure(eq(callId), eq(CallErrorKind.AI_UNAVAILABLE), anyInt());
    verify(eventPublisher).publishEvent(any(AiCallFailedEvent.class));
  }

  @Test
  void embed_invalidRequest_recordsFailure_doesNotCache() {
    UUID callId = UUID.randomUUID();
    when(recorder.recordEmbeddingPending(any(), any(), any(), any(), any())).thenReturn(callId);
    when(embeddingClient.embed(any(), any()))
        .thenThrow(new AiInvalidRequestException("bad"))
        .thenReturn(new OpenAiEmbeddingClient.EmbeddingResult(new float[1536], 1));

    AiServiceImpl svc = service();
    assertThatThrownBy(() -> svc.embed(new StubEmbeddingTask("retryable")))
        .isInstanceOf(AiInvalidRequestException.class);
    // Cache miss must NOT have stored a successful entry; second call hits the client again.
    svc.embed(new StubEmbeddingTask("retryable"));
    verify(embeddingClient, times(2)).embed(any(), any());
    verify(recorder).recordFailure(eq(callId), eq(CallErrorKind.INVALID_REQUEST), anyInt());
  }

  /** Minimal {@link com.example.mealprep.ai.spi.EmbeddingTask} for unit tests. */
  private static final class StubEmbeddingTask
      implements com.example.mealprep.ai.spi.EmbeddingTask {
    private final String inputText;
    private final UUID userId;
    private final UUID traceId;

    StubEmbeddingTask(String inputText) {
      this(inputText, null, null);
    }

    StubEmbeddingTask(String inputText, UUID userId, UUID traceId) {
      this.inputText = inputText;
      this.userId = userId;
      this.traceId = traceId;
    }

    @Override
    public com.example.mealprep.ai.spi.EmbeddingTaskType type() {
      return com.example.mealprep.ai.spi.EmbeddingTaskType.PREFERENCE_TASTE_VECTOR;
    }

    @Override
    public String inputText() {
      return inputText;
    }

    @Override
    public java.util.Optional<UUID> userId() {
      return java.util.Optional.ofNullable(userId);
    }

    @Override
    public java.util.Optional<UUID> traceId() {
      return java.util.Optional.ofNullable(traceId);
    }
  }

  @Test
  void execute_budgetExceeded_recordsBudgetFailure_publishesBothEvents_doesNotCallAnthropic() {
    UUID callId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    AiTask<String> task = AiTestData.task(String.class).withUserId(userId).build();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);
    AiCostBudgetExceededException budgetEx =
        new AiCostBudgetExceededException(
            userId,
            new BigDecimal("48.00"),
            new BigDecimal("50.00"),
            Duration.ofHours(24),
            Duration.ofSeconds(3600));
    Mockito.doThrow(budgetEx).when(budgetGuard).checkOrThrow(task);

    assertThatThrownBy(() -> service().execute(task))
        .isInstanceOf(AiCostBudgetExceededException.class);

    // PENDING row written first, then budget rejection finalises it FAILED with BUDGET_EXCEEDED.
    verify(recorder).recordPending(task, ModelTier.CHEAP, "haiku-id");
    verify(recorder).recordFailure(eq(callId), eq(CallErrorKind.BUDGET_EXCEEDED), anyInt());
    // Anthropic was never called.
    verify(anthropicClient, never()).call(any(), any());
    // recordSuccess is never called for a budget rejection.
    verify(recorder, never()).recordSuccess(any(), any(), any(), anyInt());
    verify(recorder, never()).recordSuccess(any(), any(), any(), anyInt(), anyLong());

    ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher, times(2)).publishEvent(events.capture());
    List<Object> captured = events.getAllValues();
    assertThat(captured).hasSize(2);
    assertThat(captured.get(0)).isInstanceOf(AiCallFailedEvent.class);
    assertThat(((AiCallFailedEvent) captured.get(0)).errorKind())
        .isEqualTo(CallErrorKind.BUDGET_EXCEEDED);
    assertThat(captured.get(1)).isInstanceOf(CostBudgetExceededEvent.class);
    CostBudgetExceededEvent budgetEvent = (CostBudgetExceededEvent) captured.get(1);
    assertThat(budgetEvent.userId()).isEqualTo(userId);
    assertThat(budgetEvent.spentPence()).isEqualByComparingTo(new BigDecimal("48.00"));
    assertThat(budgetEvent.limitPence()).isEqualByComparingTo(new BigDecimal("50.00"));
  }

  @Test
  void execute_budgetExceededAndRecorderFails_doesNotMaskException() {
    UUID callId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    AiTask<String> task = AiTestData.task(String.class).withUserId(userId).build();
    when(recorder.recordPending(any(), any(), any())).thenReturn(callId);
    AiCostBudgetExceededException budgetEx =
        new AiCostBudgetExceededException(
            userId,
            new BigDecimal("50.00"),
            new BigDecimal("50.00"),
            Duration.ofHours(24),
            Duration.ofSeconds(60));
    Mockito.doThrow(budgetEx).when(budgetGuard).checkOrThrow(task);
    Mockito.doThrow(new RuntimeException("audit save failed"))
        .when(recorder)
        .recordFailure(any(), any(), anyInt());

    assertThatThrownBy(() -> service().execute(task))
        .isInstanceOf(AiCostBudgetExceededException.class);
    // Both events still publish even when the audit-row update fails.
    verify(eventPublisher, times(1)).publishEvent(any(AiCallFailedEvent.class));
    verify(eventPublisher, times(1)).publishEvent(any(CostBudgetExceededEvent.class));
  }

  /** Minimal Jackson-compatible payload for typed-deserialisation cases. */
  public static final class TypedPayload {
    private String answer;

    public TypedPayload() {}

    public String answer() {
      return answer;
    }

    public void setAnswer(String answer) {
      this.answer = answer;
    }
  }
}
