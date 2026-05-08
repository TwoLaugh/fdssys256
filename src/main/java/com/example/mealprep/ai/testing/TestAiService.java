package com.example.mealprep.ai.testing;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.event.AiCallSucceededEvent;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.spi.AiTask;
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

  private final Map<TaskType, Object> cannedResponses = new EnumMap<>(TaskType.class);
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

  /** Forget all registrations and recorded calls — call from {@code @AfterEach} in ITs. */
  public void clear() {
    cannedResponses.clear();
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

  /** What the IT can inspect. */
  public record RecordedCall(UUID callId, TaskType taskType, UUID userId, UUID traceId) {}
}
