package com.example.mealprep.ai.testing;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.event.AiCallSucceededEvent;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.EmbeddingTask;
import com.example.mealprep.ai.spi.EmbeddingTaskType;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test-profile {@link AiService} replacement. Returns canned responses keyed by {@link TaskType};
 * NEVER makes an HTTP call. Downstream module ITs depend on this so they don't blow real API tokens
 * running in CI.
 *
 * <p>Bean is {@code @Primary} so {@code @SpringBootTest @ActiveProfiles("test")} picks it up over
 * {@code AiServiceImpl}. The {@code model_id} stored on the audit row is a synthetic prefix ({@link
 * #TEST_MODEL_ID_PREFIX}) so an IT can assert that no row was ever recorded against a real
 * Anthropic model id.
 */
@Service
@Profile("test")
@Primary
public class TestAiService implements AiService {

  private static final Logger log = LoggerFactory.getLogger(TestAiService.class);

  /** Synthetic model-id prefix that surfaces on every {@code ai_call_log} row this bean writes. */
  public static final String TEST_MODEL_ID_PREFIX = "test-stub-";

  /** Default deterministic embedding dimension — matches {@code text-embedding-3-small}. */
  public static final int DEFAULT_EMBEDDING_DIM = 1536;

  private final Map<TaskType, Object> cannedResponses = new EnumMap<>(TaskType.class);
  private final Map<EmbeddingTaskType, float[]> cannedEmbeddings =
      new EnumMap<>(EmbeddingTaskType.class);
  private final CopyOnWriteArrayList<RecordedCall> recordedCalls = new CopyOnWriteArrayList<>();
  private final AtomicLong syntheticLatency = new AtomicLong(1L);

  private final AiCallLogRepository repository;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public TestAiService(
      AiCallLogRepository repository, ApplicationEventPublisher eventPublisher, Clock clock) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /** Register a canned response for a task type. Subsequent calls for that type return this. */
  public <T> TestAiService register(TaskType taskType, T response) {
    cannedResponses.put(taskType, response);
    return this;
  }

  /**
   * Override the default deterministic vector for a given embedding type. Pass {@code null} to fall
   * back to the {@code inputText.hashCode()}-derived default.
   */
  public TestAiService registerEmbedding(EmbeddingTaskType type, float[] vector) {
    if (vector == null) {
      cannedEmbeddings.remove(type);
    } else {
      cannedEmbeddings.put(type, vector);
    }
    return this;
  }

  /** Forget all registrations and recorded calls — call from {@code @AfterEach} in ITs. */
  public void clear() {
    cannedResponses.clear();
    cannedEmbeddings.clear();
    recordedCalls.clear();
  }

  /** What was dispatched. Order is the call order. */
  public java.util.List<RecordedCall> recordedCalls() {
    return java.util.List.copyOf(recordedCalls);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public <T> T execute(AiTask<T> task) {
    if (task == null) {
      throw new IllegalArgumentException("task must not be null");
    }
    Object canned = cannedResponses.get(task.type());
    if (canned == null) {
      throw new AiInvalidResponseException(
          "No canned response registered for TaskType " + task.type());
    }
    if (!task.outputType().isInstance(canned)) {
      throw new AiInvalidResponseException(
          "Canned response for "
              + task.type()
              + " is "
              + canned.getClass().getName()
              + " but task expected "
              + task.outputType().getName());
    }

    UUID callId = UUID.randomUUID();
    int latencyMs = (int) syntheticLatency.getAndIncrement();
    AiCallLog row =
        new AiCallLog(
            callId,
            task.userId().orElse(null),
            task.traceId().orElse(null),
            task.type(),
            task.tier() == null ? ModelTier.MID : task.tier(),
            TEST_MODEL_ID_PREFIX + task.type().name().toLowerCase(),
            task.prompt().name(),
            task.prompt().version(),
            CallStatus.SUCCEEDED);
    row.setLatencyMs(latencyMs);
    row.setRequestTokens(0);
    row.setResponseTokens(0);
    row.setCompletedAt(Instant.now(clock));
    repository.save(row);

    recordedCalls.add(
        new RecordedCall(
            callId, task.type(), task.userId().orElse(null), task.traceId().orElse(null)));
    eventPublisher.publishEvent(
        new AiCallSucceededEvent(
            callId,
            task.type(),
            task.userId().orElse(null),
            latencyMs,
            0L,
            task.traceId().orElse(null),
            Instant.now(clock)));

    log.debug("test ai call dispatched callId={} taskType={}", callId, task.type());
    @SuppressWarnings("unchecked")
    T cast = (T) canned;
    return cast;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public float[] embed(EmbeddingTask task) {
    if (task == null) {
      throw new IllegalArgumentException("task must not be null");
    }
    String input = task.inputText();
    if (input == null || input.isBlank()) {
      throw new IllegalArgumentException("inputText must not be empty");
    }
    float[] override = cannedEmbeddings.get(task.type());
    float[] vector = override != null ? override : deterministicVector(input);

    UUID callId = UUID.randomUUID();
    int latencyMs = (int) syntheticLatency.getAndIncrement();
    TaskType auditType = embeddingAuditType(task.type());
    AiCallLog row =
        new AiCallLog(
            callId,
            task.userId().orElse(null),
            task.traceId().orElse(null),
            auditType,
            ModelTier.CHEAP,
            TEST_MODEL_ID_PREFIX + auditType.name().toLowerCase(),
            null,
            null,
            CallStatus.SUCCEEDED);
    row.setLatencyMs(latencyMs);
    row.setRequestTokens(0);
    row.setResponseTokens(0);
    row.setCompletedAt(Instant.now(clock));
    repository.save(row);

    recordedCalls.add(
        new RecordedCall(
            callId, auditType, task.userId().orElse(null), task.traceId().orElse(null)));
    eventPublisher.publishEvent(
        new AiCallSucceededEvent(
            callId,
            auditType,
            task.userId().orElse(null),
            latencyMs,
            0L,
            task.traceId().orElse(null),
            Instant.now(clock)));
    log.debug("test embedding dispatched callId={} type={}", callId, task.type());
    return vector;
  }

  /** Reproducible vector derived from {@code inputText.hashCode()} — same input = same output. */
  private static float[] deterministicVector(String inputText) {
    int seed = inputText.hashCode();
    float[] out = new float[DEFAULT_EMBEDDING_DIM];
    long state = seed == 0 ? 1L : seed;
    for (int i = 0; i < out.length; i++) {
      // Simple xorshift — deterministic, no Random allocation, no HTTP traffic.
      state ^= state << 13;
      state ^= state >>> 7;
      state ^= state << 17;
      out[i] = ((state & 0xFFFF) / 65535.0f) * 2f - 1f;
    }
    return out;
  }

  private static TaskType embeddingAuditType(EmbeddingTaskType type) {
    return switch (type) {
      case PREFERENCE_TASTE_VECTOR -> TaskType.EMBEDDING_PREFERENCE_TASTE_VECTOR;
      case RECIPE_SEMANTIC_VECTOR -> TaskType.EMBEDDING_RECIPE_SEMANTIC_VECTOR;
      case JOURNAL_ENTRY_VECTOR -> TaskType.EMBEDDING_JOURNAL_ENTRY_VECTOR;
    };
  }

  /** What the IT can inspect. */
  public record RecordedCall(UUID callId, TaskType taskType, UUID userId, UUID traceId) {}
}
