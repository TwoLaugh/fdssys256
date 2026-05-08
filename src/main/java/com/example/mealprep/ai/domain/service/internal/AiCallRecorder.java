package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallErrorKind;
import com.example.mealprep.ai.domain.entity.CallStatus;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.spi.AiTask;
import com.example.mealprep.ai.spi.ModelTier;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wraps the INSERT-PENDING / UPDATE-final write pattern in {@code REQUIRES_NEW} so the audit row
 * survives a caller transaction rollback — same shape as {@code DecisionLogServiceImpl.write}.
 *
 * <p>{@code costMicroPence} is wired into the entity here but always set to {@code 0} for 01a;
 * 01b's cost calculator fills it in once tier pricing is in place.
 */
@Component
public class AiCallRecorder {

  private final AiCallLogRepository repository;
  private final Clock clock;

  public AiCallRecorder(AiCallLogRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /** Insert a fresh PENDING row and return its id. Visible to its own tx via REQUIRES_NEW. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UUID recordPending(AiTask<?> task, ModelTier tier, String modelId) {
    UUID callId = UUID.randomUUID();
    AiCallLog row =
        new AiCallLog(
            callId,
            task.userId().orElse(null),
            task.traceId().orElse(null),
            task.type(),
            tier,
            modelId,
            task.prompt().name(),
            task.prompt().version(),
            CallStatus.PENDING);
    repository.save(row);
    return callId;
  }

  /** Update an existing row to SUCCEEDED with token + latency stats. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordSuccess(
      UUID callId, Integer requestTokens, Integer responseTokens, int latencyMs) {
    AiCallLog row =
        repository
            .findById(callId)
            .orElseThrow(() -> new IllegalStateException("ai_call_log row missing: " + callId));
    row.setStatus(CallStatus.SUCCEEDED);
    row.setRequestTokens(requestTokens);
    row.setResponseTokens(responseTokens);
    row.setLatencyMs(latencyMs);
    row.setCompletedAt(Instant.now(clock));
    // costMicroPence stays 0 for 01a; 01b will recompute and update.
    repository.save(row);
  }

  /** Update an existing row to FAILED with the classified error kind. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(UUID callId, CallErrorKind errorKind, int latencyMs) {
    AiCallLog row =
        repository
            .findById(callId)
            .orElseThrow(() -> new IllegalStateException("ai_call_log row missing: " + callId));
    row.setStatus(CallStatus.FAILED);
    row.setErrorKind(errorKind);
    row.setLatencyMs(latencyMs);
    row.setCompletedAt(Instant.now(clock));
    repository.save(row);
  }
}
