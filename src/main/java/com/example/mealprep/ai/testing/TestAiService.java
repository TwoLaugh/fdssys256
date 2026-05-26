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
import com.fasterxml.jackson.databind.ObjectMapper;
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
 *
 * <p><b>Also active under the {@code e2e} profile</b> (decision D4): the prod-parity docker-compose
 * stack boots the <i>real</i> application on {@code SPRING_PROFILES_ACTIVE=e2e}, and this bean is
 * the deterministic AI double <i>inside that running app</i> — every external dependency stays
 * real, only the AI is faked. Because {@code AiServiceImpl} is unconditional, the {@code @Primary}
 * here makes this double win in both profiles. The canned-response map starts empty; AI-touching
 * E2E scenarios must register a canned response before exercising the flow (the auth smoke slice
 * touches no AI, so an empty map is correct for it).
 *
 * <p><b>Two registration modes.</b> There are two parallel canned-response registries:
 *
 * <ul>
 *   <li>{@link #register(TaskType, Object)} — a pre-built typed object. Used by the 40+ in-process
 *       ITs that have direct Java access to this bean.
 *   <li>{@link #registerJson(TaskType, String)} — a raw JSON string that is deserialised, on each
 *       {@link #execute} call, through the Spring-managed {@link ObjectMapper} into {@code
 *       task.outputType()}. This is the seam the BLACK-BOX E2E suite drives (it cannot make a Java
 *       call into this container): a scenario seeds realistic model-shaped JSON over HTTP via
 *       {@code E2eAiStubController}, and the dispatch then exercises the REAL JSON→domain
 *       wire-contract — exactly the path {@code AiServiceImpl#deserialise} runs in prod — rather
 *       than a hand-built object.
 * </ul>
 *
 * <p>When both registries hold an entry for a task type, the JSON registry wins (it is the more
 * realistic, wire-exercising path). A parse failure on the seeded JSON throws {@link
 * AiInvalidResponseException}, mirroring prod's {@code AiServiceImpl} semantics.
 */
@Service
@Profile({"test", "e2e"})
@Primary
public class TestAiService implements AiService {

  private static final Logger log = LoggerFactory.getLogger(TestAiService.class);

  /** Synthetic model-id prefix that surfaces on every {@code ai_call_log} row this bean writes. */
  public static final String TEST_MODEL_ID_PREFIX = "test-stub-";

  /** Default deterministic embedding dimension — matches {@code text-embedding-3-small}. */
  public static final int DEFAULT_EMBEDDING_DIM = 1536;

  /**
   * Built-in NO_CHANGE canned response for {@link TaskType#RECIPE_ADAPTATION}. The adaptation
   * Trigger-1 job fires automatically on EVERY recipe create (LLD §Trigger 1 — "usually
   * NO_CHANGE"), so a faithful e2e run that creates a recipe dispatches a {@code RECIPE_ADAPTATION}
   * task in the background. No scenario can reliably pre-seed that background dispatch, so without
   * a default the path hard-fails with "No canned response registered". This shape mirrors {@code
   * com.example.mealprep.adaptation.ai.RecipeAdaptationResponse}: {@code chosenCandidateIndex = -1}
   * is the NO_CHANGE signal; {@code confidence} clears the low-confidence floor and {@code
   * characterPreservationScore = 1.0} clears the character gate, so the worker reaches a clean
   * terminal outcome rather than throwing. It is stored as raw JSON (not a typed object) so this AI
   * module need not depend on the adaptation module — it is deserialised through the real {@code
   * ObjectMapper} into {@code task.outputType()} on dispatch, exactly as a seeded e2e response
   * would be.
   */
  static final String DEFAULT_RECIPE_ADAPTATION_NO_CHANGE_JSON =
      """
      {
        "chosenCandidateIndex": -1,
        "classification": "NO_CHANGE",
        "reasoning": "default e2e stub: no adaptation proposed on import",
        "nutritionalNotes": "",
        "confidence": 0.95,
        "characterPreservationScore": 1.0,
        "refinedDiff": null,
        "finalDiffJson": {},
        "plannerHints": []
      }
      """;

  private final Map<TaskType, Object> cannedResponses = new EnumMap<>(TaskType.class);
  private final Map<TaskType, String> cannedJson = new EnumMap<>(TaskType.class);

  /**
   * Built-in default JSON responses, consulted ONLY when neither explicit registry holds an entry
   * for the task type. This keeps the always-on adaptation Trigger-1 path from hard-failing while
   * leaving every explicit {@link #register}/{@link #registerJson} (and the precedence between
   * them) untouched. Re-seeded on {@link #clear()} so a mid-run reset never strands the background
   * path.
   */
  private final Map<TaskType, String> defaultJson = new EnumMap<>(TaskType.class);

  private final Map<EmbeddingTaskType, float[]> cannedEmbeddings =
      new EnumMap<>(EmbeddingTaskType.class);
  private final CopyOnWriteArrayList<RecordedCall> recordedCalls = new CopyOnWriteArrayList<>();
  private final AtomicLong syntheticLatency = new AtomicLong(1L);

  private final AiCallLogRepository repository;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;
  private final ObjectMapper objectMapper;

  public TestAiService(
      AiCallLogRepository repository,
      ApplicationEventPublisher eventPublisher,
      Clock clock,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
    this.objectMapper = objectMapper;
    seedDefaults();
  }

  /**
   * Install the built-in default responses. Idempotent; called from the ctor and {@link #clear}.
   */
  private void seedDefaults() {
    defaultJson.put(TaskType.RECIPE_ADAPTATION, DEFAULT_RECIPE_ADAPTATION_NO_CHANGE_JSON);
  }

  /** Register a canned response for a task type. Subsequent calls for that type return this. */
  public <T> TestAiService register(TaskType taskType, T response) {
    cannedResponses.put(taskType, response);
    return this;
  }

  /**
   * Register a raw JSON canned response for a task type. On each {@link #execute} call for that
   * type, the JSON is deserialised through the Spring-managed {@link ObjectMapper} into {@code
   * task.outputType()} — exercising the real JSON→domain wire-contract. This is the seam the
   * black-box E2E suite drives over HTTP (see {@code E2eAiStubController}); the in-process ITs use
   * the typed {@link #register(TaskType, Object)} instead.
   *
   * <p>Takes precedence over a typed registration for the same task type.
   */
  public TestAiService registerJson(TaskType taskType, String json) {
    cannedJson.put(taskType, json);
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

  /**
   * Forget all explicit registrations and recorded calls — call from {@code @AfterEach} in ITs.
   * Built-in defaults (see {@link #seedDefaults()}) are re-installed so the always-on adaptation
   * Trigger-1 path keeps a NO_CHANGE response after a reset.
   */
  public void clear() {
    cannedResponses.clear();
    cannedJson.clear();
    cannedEmbeddings.clear();
    recordedCalls.clear();
    seedDefaults();
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
    // JSON-canned mode (the black-box E2E seam) takes precedence: deserialise the seeded JSON
    // through the real ObjectMapper into the task's declared output type, mirroring prod's
    // AiServiceImpl#deserialise. A parse failure is an AiInvalidResponseException, exactly as prod.
    String json = cannedJson.get(task.type());
    Object canned;
    if (json != null) {
      try {
        canned = objectMapper.readValue(json, task.outputType());
      } catch (Exception ex) {
        throw new AiInvalidResponseException(
            "Failed to deserialise canned JSON for "
                + task.type()
                + " into "
                + task.outputType().getName(),
            ex);
      }
    } else if (cannedResponses.get(task.type()) == null && defaultJson.get(task.type()) != null) {
      // Neither explicit registry has an entry — fall back to a built-in default (e.g. the
      // always-on adaptation Trigger-1 NO_CHANGE response). Deserialised through the real
      // ObjectMapper into the task's output type, exactly like an explicitly-seeded JSON response.
      String defaultBody = defaultJson.get(task.type());
      try {
        canned = objectMapper.readValue(defaultBody, task.outputType());
      } catch (Exception ex) {
        throw new AiInvalidResponseException(
            "Failed to deserialise default JSON for "
                + task.type()
                + " into "
                + task.outputType().getName(),
            ex);
      }
    } else {
      canned = cannedResponses.get(task.type());
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
