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
import com.example.mealprep.ai.spi.ModelTier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
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
 */
@Service
public class AiServiceImpl implements AiService {

  private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

  private final AnthropicClient anthropicClient;
  private final AiCallRecorder recorder;
  private final ApplicationEventPublisher eventPublisher;
  private final AiProperties properties;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public AiServiceImpl(
      AnthropicClient anthropicClient,
      AiCallRecorder recorder,
      ApplicationEventPublisher eventPublisher,
      AiProperties properties,
      ObjectMapper objectMapper,
      Clock clock) {
    this.anthropicClient = anthropicClient;
    this.recorder = recorder;
    this.eventPublisher = eventPublisher;
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.clock = clock;
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

  private static int elapsedMs(long startNanos) {
    long elapsedNanos = System.nanoTime() - startNanos;
    long ms = elapsedNanos / 1_000_000L;
    return (int) Math.min(ms, Integer.MAX_VALUE);
  }
}
