package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.config.AiProperties;
import com.example.mealprep.ai.domain.entity.CallErrorKind;
import com.example.mealprep.ai.domain.service.AiService;
import com.example.mealprep.ai.event.AiCallFailedEvent;
import com.example.mealprep.ai.event.AiCallSucceededEvent;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.EmbeddingTask;
import com.example.mealprep.ai.spi.EmbeddingTaskType;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Production {@link AiService} dispatcher. Records a PENDING audit row, calls Anthropic via {@link
 * AnthropicClient}, then UPDATEs the row to SUCCEEDED / FAILED and publishes the matching event.
 *
 * <p>Not {@code @Transactional} — the network call holds for seconds, and the audit-row writes are
 * owned by {@link AiCallRecorder}'s {@code REQUIRES_NEW} transactions so they survive caller
 * rollback.
 *
 * <p>{@link #embed} is the embedding sibling of {@link #execute}. It funnels through the same
 * audit-row path so 01b's budget guard counts embedding spend; identical input text is cached
 * in-memory for {@code mealprep.ai.embedding.cache-ttl-hours}.
 */
@Service
public class AiServiceImpl implements AiService {

  private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

  private final AnthropicClient anthropicClient;
  private final OpenAiEmbeddingClient embeddingClient;
  private final AiCallRecorder recorder;
  private final ApplicationEventPublisher eventPublisher;
  private final AiProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final Cache<String, float[]> embeddingCache;

  public AiServiceImpl(
      AnthropicClient anthropicClient,
      OpenAiEmbeddingClient embeddingClient,
      AiCallRecorder recorder,
      ApplicationEventPublisher eventPublisher,
      AiProperties properties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.anthropicClient = anthropicClient;
    this.embeddingClient = embeddingClient;
    this.recorder = recorder;
    this.eventPublisher = eventPublisher;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
    AiProperties.Embedding cfg = properties.embedding();
    this.embeddingCache =
        Caffeine.newBuilder()
            .maximumSize(cfg.cacheSize())
            .expireAfterWrite(Duration.ofHours(cfg.cacheTtlHours()))
            .build();
  }

  @Override
  public <T> T execute(AiTask<T> task) {
    if (task == null) {
      throw new IllegalArgumentException("task must not be null");
    }
    ModelTier tier = task.tier();
    String modelId = properties.modelIdFor(tier);
    UUID callId = recorder.recordPending(task, tier, modelId);
    long startNanos = System.nanoTime();
    try {
      AnthropicResponse response = anthropicClient.call(task, modelId);
      int latencyMs = elapsedMs(startNanos);
      T payload = deserialise(response.body(), task.outputType());
      recorder.recordSuccess(
          callId, response.requestTokens(), response.responseTokens(), latencyMs);
      eventPublisher.publishEvent(
          new AiCallSucceededEvent(
              callId,
              task.type(),
              task.userId().orElse(null),
              latencyMs,
              0L,
              task.traceId().orElse(null),
              Instant.now(clock)));
      log.info(
          "ai call succeeded callId={} taskType={} tier={} latencyMs={}",
          callId,
          task.type(),
          tier,
          latencyMs);
      return payload;
    } catch (AiInvalidRequestException ex) {
      finalizeFailure(callId, task, CallErrorKind.INVALID_REQUEST, startNanos);
      throw ex;
    } catch (AiInvalidResponseException ex) {
      finalizeFailure(callId, task, CallErrorKind.INVALID_RESPONSE, startNanos);
      throw ex;
    } catch (AiUnavailableException ex) {
      finalizeFailure(callId, task, CallErrorKind.AI_UNAVAILABLE, startNanos);
      throw ex;
    }
  }

  @Override
  public float[] embed(EmbeddingTask task) {
    if (task == null) {
      throw new IllegalArgumentException("task must not be null");
    }
    String inputText = task.inputText();
    if (inputText == null || inputText.isBlank()) {
      throw new IllegalArgumentException("inputText must not be empty");
    }
    String model = properties.embedding().model();
    String cacheKey = cacheKey(inputText, model);
    float[] cached = embeddingCache.getIfPresent(cacheKey);
    if (cached != null) {
      log.debug("embedding cache hit type={} model={}", task.type(), model);
      return cached;
    }
    TaskType auditType = toTaskType(task.type());
    UUID callId =
        recorder.recordEmbeddingPending(
            task.userId().orElse(null),
            task.traceId().orElse(null),
            auditType,
            ModelTier.CHEAP,
            model);
    long startNanos = System.nanoTime();
    try {
      OpenAiEmbeddingClient.EmbeddingResult result = embeddingClient.embed(inputText, model);
      int latencyMs = elapsedMs(startNanos);
      recorder.recordSuccess(callId, result.inputTokens(), 0, latencyMs);
      embeddingCache.put(cacheKey, result.vector());
      eventPublisher.publishEvent(
          new AiCallSucceededEvent(
              callId,
              auditType,
              task.userId().orElse(null),
              latencyMs,
              0L,
              task.traceId().orElse(null),
              Instant.now(clock)));
      log.info(
          "embedding succeeded callId={} type={} model={} latencyMs={}",
          callId,
          task.type(),
          model,
          latencyMs);
      return result.vector();
    } catch (AiInvalidRequestException ex) {
      finalizeEmbeddingFailure(callId, auditType, task, CallErrorKind.INVALID_REQUEST, startNanos);
      throw ex;
    } catch (AiInvalidResponseException ex) {
      finalizeEmbeddingFailure(callId, auditType, task, CallErrorKind.INVALID_RESPONSE, startNanos);
      throw ex;
    } catch (AiUnavailableException ex) {
      finalizeEmbeddingFailure(callId, auditType, task, CallErrorKind.AI_UNAVAILABLE, startNanos);
      throw ex;
    }
  }

  private <T> T deserialise(String body, Class<T> type) {
    if (type == String.class) {
      @SuppressWarnings("unchecked")
      T cast = (T) (body == null ? "" : body);
      return cast;
    }
    try {
      return objectMapper.readValue(body == null ? "" : body, type);
    } catch (Exception ex) {
      throw new AiInvalidResponseException(
          "Failed to deserialise Anthropic response into " + type.getSimpleName(), ex);
    }
  }

  private void finalizeFailure(
      UUID callId, AiTask<?> task, CallErrorKind errorKind, long startNanos) {
    int latencyMs = elapsedMs(startNanos);
    try {
      recorder.recordFailure(callId, errorKind, latencyMs);
    } catch (RuntimeException recordingEx) {
      // Audit-row update fails should not mask the underlying failure to the caller.
      log.warn(
          "failed to update ai_call_log to FAILED callId={} reason={}",
          callId,
          recordingEx.getMessage());
    }
    eventPublisher.publishEvent(
        new AiCallFailedEvent(
            callId,
            task.type(),
            task.userId().orElse(null),
            errorKind,
            task.traceId().orElse(null),
            Instant.now(clock)));
    log.info(
        "ai call failed callId={} taskType={} errorKind={} latencyMs={}",
        callId,
        task.type(),
        errorKind,
        latencyMs);
  }

  private void finalizeEmbeddingFailure(
      UUID callId,
      TaskType auditType,
      EmbeddingTask task,
      CallErrorKind errorKind,
      long startNanos) {
    int latencyMs = elapsedMs(startNanos);
    try {
      recorder.recordFailure(callId, errorKind, latencyMs);
    } catch (RuntimeException recordingEx) {
      log.warn(
          "failed to update embedding ai_call_log to FAILED callId={} reason={}",
          callId,
          recordingEx.getMessage());
    }
    eventPublisher.publishEvent(
        new AiCallFailedEvent(
            callId,
            auditType,
            task.userId().orElse(null),
            errorKind,
            task.traceId().orElse(null),
            Instant.now(clock)));
    log.info(
        "embedding failed callId={} type={} errorKind={} latencyMs={}",
        callId,
        task.type(),
        errorKind,
        latencyMs);
  }

  /** Map an {@link EmbeddingTaskType} onto its {@code EMBEDDING_*} {@link TaskType} sibling. */
  static TaskType toTaskType(EmbeddingTaskType type) {
    return switch (type) {
      case PREFERENCE_TASTE_VECTOR -> TaskType.EMBEDDING_PREFERENCE_TASTE_VECTOR;
      case RECIPE_SEMANTIC_VECTOR -> TaskType.EMBEDDING_RECIPE_SEMANTIC_VECTOR;
      case JOURNAL_ENTRY_VECTOR -> TaskType.EMBEDDING_JOURNAL_ENTRY_VECTOR;
    };
  }

  /** SHA-256 of {@code inputText + '\0' + model}, hex-encoded — keeps models cached separately. */
  static String cacheKey(String inputText, String model) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(inputText.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(model.getBytes(StandardCharsets.UTF_8));
      byte[] hash = digest.digest();
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static int elapsedMs(long startNanos) {
    long elapsedNanos = System.nanoTime() - startNanos;
    long ms = elapsedNanos / 1_000_000L;
    return (int) Math.min(ms, Integer.MAX_VALUE);
  }
}
