package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.event.AiCallSucceededEvent;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.EmbeddingTask;
import com.example.mealprep.ai.spi.EmbeddingTaskType;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.testdata.AiTestData;
import com.example.mealprep.ai.testing.TestAiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link TestAiService}. Baseline left 42 mutants uncovered because the bean only
 * activates under the {@code test} profile and IT-only paths don't surface to Pitest. Pure-unit
 * tests here are enough to kill the conditionals, void calls, and switch branches.
 */
class TestAiServiceTest {

  private final AiCallLogRepository repository = mock(AiCallLogRepository.class);
  private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T12:00:00Z"), ZoneOffset.UTC);

  private final ObjectMapper objectMapper = new ObjectMapper();

  private TestAiService svc() {
    return new TestAiService(repository, publisher, clock, objectMapper);
  }

  // ---------------- execute() ----------------

  /**
   * Kills {@code execute:104} NegateConditionals — null task → IllegalArgument, NOT pass through.
   */
  @Test
  void execute_nullTask_throwsIllegalArgument() {
    assertThatThrownBy(() -> svc().execute(null)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Kills {@code execute:108} NegateConditionals — when no canned response is registered, throw
   * AiInvalidResponseException.
   */
  @Test
  void execute_noCannedResponse_throwsInvalidResponse() {
    TestAiService s = svc();
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    assertThatThrownBy(() -> s.execute(task))
        .isInstanceOf(AiInvalidResponseException.class)
        .hasMessageContaining("No canned response");
  }

  /**
   * Kills {@code execute:112} NegateConditionals — registered response of wrong type yields
   * AiInvalidResponseException.
   */
  @Test
  void execute_wrongTypedCannedResponse_throwsInvalidResponse() {
    TestAiService s = svc();
    s.register(TaskType.FEEDBACK_CLASSIFICATION, 42); // an Integer, but task expects String
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    assertThatThrownBy(() -> s.execute(task))
        .isInstanceOf(AiInvalidResponseException.class)
        .hasMessageContaining("expected")
        .hasMessageContaining("String");
  }

  /**
   * JSON-canned mode: a registered raw JSON string is deserialised through the ObjectMapper into
   * the task's output type, then proceeds down the normal audit/event path (row saved + event
   * published) and the deserialised object is returned.
   */
  @Test
  void execute_jsonCanned_deserialisesAndReturns() {
    TestAiService s = svc();
    s.registerJson(TaskType.FEEDBACK_CLASSIFICATION, "\"hello-from-json\"");
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();

    String result = s.execute(task);
    assertThat(result).isEqualTo("hello-from-json");
    verify(repository).save(any(AiCallLog.class));
    verify(publisher).publishEvent(any(AiCallSucceededEvent.class));
    assertThat(s.recordedCalls()).hasSize(1);
  }

  /** JSON-canned mode takes precedence over a typed registration for the same task type. */
  @Test
  void execute_jsonCanned_winsOverTypedRegistration() {
    TestAiService s = svc();
    s.register(TaskType.FEEDBACK_CLASSIFICATION, "from-typed");
    s.registerJson(TaskType.FEEDBACK_CLASSIFICATION, "\"from-json\"");
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    assertThat(s.execute(task)).isEqualTo("from-json");
  }

  /**
   * Malformed JSON in the JSON registry surfaces as AiInvalidResponseException (prod semantics).
   */
  @Test
  void execute_jsonCanned_parseFailure_throwsInvalidResponse() {
    TestAiService s = svc();
    // Not a JSON string literal for a String output type — the readValue fails.
    s.registerJson(TaskType.FEEDBACK_CLASSIFICATION, "{ this is : not valid json ]");
    AiTask<String> task =
        AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build();
    assertThatThrownBy(() -> s.execute(task))
        .isInstanceOf(AiInvalidResponseException.class)
        .hasMessageContaining("Failed to deserialise canned JSON");
  }

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code execute:130} NegateConditionals — tier-null fallback to MID
   *   <li>{@code execute:135-138} VoidMethodCall — every audit-row setter
   *   <li>{@code execute:144} VoidMethodCall — eventPublisher.publishEvent
   *   <li>{@code execute:157} NullReturnVals — return the canned response (not null)
   *   <li>{@code register:73} NullReturnVals — return `this`
   * </ul>
   */
  @Test
  void execute_happyPath_savesRow_publishesEvent_returnsCanned() {
    TestAiService s = svc();
    TestAiService returned = s.register(TaskType.FEEDBACK_CLASSIFICATION, "canned");
    assertThat(returned).isSameAs(s);

    AiTask<String> task =
        AiTestData.task(String.class)
            .ofType(TaskType.FEEDBACK_CLASSIFICATION)
            .withTier(ModelTier.HIGH)
            .build();

    String result = s.execute(task);
    assertThat(result).isEqualTo("canned");

    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    AiCallLog saved = cap.getValue();
    assertThat(saved.getStatus()).isEqualTo(CallStatus.SUCCEEDED);
    assertThat(saved.getTaskType()).isEqualTo(TaskType.FEEDBACK_CLASSIFICATION);
    assertThat(saved.getModelTier()).isEqualTo(ModelTier.HIGH);
    assertThat(saved.getModelId()).startsWith(TestAiService.TEST_MODEL_ID_PREFIX);
    assertThat(saved.getRequestTokens()).isEqualTo(0);
    assertThat(saved.getResponseTokens()).isEqualTo(0);
    assertThat(saved.getLatencyMs()).isPositive(); // 1 from the AtomicLong
    assertThat(saved.getCompletedAt()).isEqualTo(clock.instant());

    verify(publisher).publishEvent(any(AiCallSucceededEvent.class));
    assertThat(s.recordedCalls()).hasSize(1);
  }

  /**
   * Kills {@code execute:130} NegateConditionals on the {@code task.tier() == null} branch — when
   * the task has a null tier, the row's modelTier must be MID.
   */
  @Test
  void execute_nullTier_fallsBackToMid() {
    TestAiService s = svc();
    s.register(TaskType.FEEDBACK_CLASSIFICATION, "canned");
    AiTask<String> task = nullTierTask();

    s.execute(task);

    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    assertThat(cap.getValue().getModelTier()).isEqualTo(ModelTier.MID);
  }

  // ---------------- embed() ----------------

  /** Kills {@code embed:163} NegateConditionals — null task → IllegalArgument. */
  @Test
  void embed_nullTask_throwsIllegalArgument() {
    assertThatThrownBy(() -> svc().embed(null)).isInstanceOf(IllegalArgumentException.class);
  }

  /** Kills {@code embed:167} NegateConditionals — blank inputText → IllegalArgument. */
  @Test
  void embed_blankInput_throwsIllegalArgument() {
    EmbeddingTask task = stubEmbeddingTask("   ");
    assertThatThrownBy(() -> svc().embed(task)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code embed:171} NegateConditionals — registered override is preferred over the
   *       deterministic vector.
   *   <li>{@code embed:187-190} VoidMethodCall — every audit setter is invoked: latency, request
   *       tokens, response tokens, completedAt. We assert the resulting row's getter values reflect
   *       those setter calls so the void-removal mutants die.
   *   <li>{@code embed:196} VoidMethodCall — publishEvent must fire (verified).
   *   <li>{@code embed:206} NullReturnVals — return the vector (not null).
   *   <li>{@code registerEmbedding:81} NegateConditionals — null vector removes the override.
   *   <li>{@code registerEmbedding:86} NullReturnVals — return `this`.
   * </ul>
   */
  @Test
  void embed_overrideRegistered_returnsOverrideAndRecordsRow() {
    TestAiService s = svc();
    float[] override = new float[1536];
    override[0] = 0.9f;
    TestAiService returned =
        s.registerEmbedding(EmbeddingTaskType.PREFERENCE_TASTE_VECTOR, override);
    assertThat(returned).isSameAs(s);

    EmbeddingTask task = stubEmbeddingTask("hello");
    float[] result = s.embed(task);
    assertThat(result).isSameAs(override);

    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository).save(cap.capture());
    AiCallLog saved = cap.getValue();
    assertThat(saved.getStatus()).isEqualTo(CallStatus.SUCCEEDED);
    assertThat(saved.getTaskType()).isEqualTo(TaskType.EMBEDDING_PREFERENCE_TASTE_VECTOR);
    assertThat(saved.getModelTier()).isEqualTo(ModelTier.CHEAP);
    assertThat(saved.getModelId()).contains("embedding_preference_taste_vector");
    assertThat(saved.getCompletedAt()).isEqualTo(clock.instant());
    // Pin the three setters that survive when the void calls are removed.
    assertThat(saved.getLatencyMs()).isNotNull().isPositive();
    assertThat(saved.getRequestTokens()).isEqualTo(0);
    assertThat(saved.getResponseTokens()).isEqualTo(0);
    verify(publisher).publishEvent(any(AiCallSucceededEvent.class));

    // registerEmbedding(null) removes the override — second call falls back to deterministic.
    s.registerEmbedding(EmbeddingTaskType.PREFERENCE_TASTE_VECTOR, null);
    float[] fallback = s.embed(stubEmbeddingTask("hello"));
    assertThat(fallback).isNotSameAs(override).hasSize(TestAiService.DEFAULT_EMBEDDING_DIM);
  }

  /**
   * Kills {@code deterministicVector:213,214,216-219} multiple Math/Conditional mutators — same
   * input produces same output, different inputs differ, and the first emitted element matches the
   * locally-computed xorshift output so any of the bitwise / shift / float-arithmetic mutations
   * change the value.
   */
  @Test
  void embed_deterministicVector_isStableAndDistinct() {
    TestAiService s = svc();
    float[] a1 = s.embed(stubEmbeddingTask("alpha"));
    float[] a2 = s.embed(stubEmbeddingTask("alpha"));
    float[] b = s.embed(stubEmbeddingTask("beta"));
    assertThat(a1).hasSize(TestAiService.DEFAULT_EMBEDDING_DIM);
    assertThat(a1).containsExactly(a2);
    assertThat(a1).isNotEqualTo(b);
    java.util.Set<Float> set = new java.util.HashSet<>();
    for (float v : a1) set.add(v);
    assertThat(set.size()).isGreaterThan(100);

    // Pin the first element against the production xorshift formula. Any of the Math mutations
    // (shift-left → shift-right, bitwise AND → OR, float * / / / -) on lines 216-219 changes this.
    long state = "alpha".hashCode(); // seed (non-zero, so doesn't go through the seed==0 branch)
    state ^= state << 13;
    state ^= state >>> 7;
    state ^= state << 17;
    float expected0 = ((state & 0xFFFF) / 65535.0f) * 2f - 1f;
    assertThat(a1[0]).isEqualTo(expected0);

    // Also exercise the seed==0 branch via reflection (state defaults to 1L when seed==0). We
    // can't use an empty/blank input via embed() because the blank guard rejects it; reflect
    // directly into the private deterministicVector method.
    try {
      java.lang.reflect.Method det =
          com.example.mealprep.ai.testing.TestAiService.class.getDeclaredMethod(
              "deterministicVector", String.class);
      det.setAccessible(true);
      // A string whose hashCode is 0 — Java strings: ""; but any input with hash==0 works.
      // Use the well-known string with hash 0: empty string.
      float[] zeroSeed = (float[]) det.invoke(null, "");
      boolean allZero = true;
      for (float v : zeroSeed) {
        if (v != 0f) {
          allZero = false;
          break;
        }
      }
      // The seed==0 branch sets state=1L → still produces a non-trivial xorshift sequence.
      assertThat(allZero).isFalse();
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }

  /** Kills {@code embeddingAuditType:225} NullReturnVals — each switch branch returns its mate. */
  @Test
  void embed_eachEmbeddingType_mapsToCorrespondingAuditType() {
    TestAiService s = svc();
    s.embed(stubEmbeddingTask("a", EmbeddingTaskType.PREFERENCE_TASTE_VECTOR));
    s.embed(stubEmbeddingTask("b", EmbeddingTaskType.RECIPE_SEMANTIC_VECTOR));
    s.embed(stubEmbeddingTask("c", EmbeddingTaskType.JOURNAL_ENTRY_VECTOR));

    ArgumentCaptor<AiCallLog> cap = ArgumentCaptor.forClass(AiCallLog.class);
    verify(repository, times(3)).save(cap.capture());
    assertThat(cap.getAllValues().stream().map(AiCallLog::getTaskType))
        .containsExactly(
            TaskType.EMBEDDING_PREFERENCE_TASTE_VECTOR,
            TaskType.EMBEDDING_RECIPE_SEMANTIC_VECTOR,
            TaskType.EMBEDDING_JOURNAL_ENTRY_VECTOR);
  }

  /**
   * Kills:
   *
   * <ul>
   *   <li>{@code clear:91-93} VoidMethodCall — each clear sub-call (cannedResponses,
   *       cannedEmbeddings, recordedCalls). We assert all three are reset.
   *   <li>{@code recordedCalls:98} EmptyObjectReturnVals — must return the recorded list, not
   *       Collections.emptyList.
   * </ul>
   */
  @Test
  void clear_emptiesEverything() {
    TestAiService s = svc();
    s.register(TaskType.FEEDBACK_CLASSIFICATION, "ok");
    float[] overrideVec = new float[1536];
    overrideVec[0] = 0.5f;
    s.registerEmbedding(EmbeddingTaskType.PREFERENCE_TASTE_VECTOR, overrideVec);
    s.execute(AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build());
    // Before clear: embedding override is in effect (same reference returned).
    assertThat(s.embed(stubEmbeddingTask("x"))).isSameAs(overrideVec);
    assertThat(s.recordedCalls()).hasSize(2); // 1 execute + 1 embed

    s.clear();
    // (a) recordedCalls cleared
    assertThat(s.recordedCalls()).isEmpty();
    // (b) cannedResponses cleared — execute throws InvalidResponse (no canned).
    assertThatThrownBy(
            () ->
                s.execute(
                    AiTestData.task(String.class).ofType(TaskType.FEEDBACK_CLASSIFICATION).build()))
        .isInstanceOf(AiInvalidResponseException.class);
    // (c) cannedEmbeddings cleared — embed no longer returns the registered override.
    float[] afterClear = s.embed(stubEmbeddingTask("x"));
    assertThat(afterClear).isNotSameAs(overrideVec);
  }

  // ---------------- helpers ----------------

  private static EmbeddingTask stubEmbeddingTask(String text) {
    return stubEmbeddingTask(text, EmbeddingTaskType.PREFERENCE_TASTE_VECTOR);
  }

  private static EmbeddingTask stubEmbeddingTask(String text, EmbeddingTaskType type) {
    return new EmbeddingTask() {
      @Override
      public EmbeddingTaskType type() {
        return type;
      }

      @Override
      public String inputText() {
        return text;
      }

      @Override
      public Optional<UUID> userId() {
        return Optional.empty();
      }

      @Override
      public Optional<UUID> traceId() {
        return Optional.empty();
      }
    };
  }

  private static AiTask<String> nullTierTask() {
    return new AiTask<>() {
      @Override
      public TaskType type() {
        return TaskType.FEEDBACK_CLASSIFICATION;
      }

      @Override
      public ModelTier tier() {
        return null; // exercise the fallback
      }

      @Override
      public com.example.mealprep.ai.spi.PromptRef prompt() {
        return new com.example.mealprep.ai.spi.PromptRef("test/echo", 1);
      }

      @Override
      public Class<String> outputType() {
        return String.class;
      }

      @Override
      public java.util.Map<String, Object> variables() {
        return java.util.Map.of("prompt", "hi");
      }

      @Override
      public Optional<java.util.List<com.example.mealprep.ai.spi.ToolDefinition>> tools() {
        return Optional.empty();
      }

      @Override
      public Optional<UUID> userId() {
        return Optional.empty();
      }

      @Override
      public Optional<UUID> traceId() {
        return Optional.empty();
      }
    };
  }
}
