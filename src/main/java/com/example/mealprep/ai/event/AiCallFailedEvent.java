package com.example.mealprep.ai.event;

import com.example.mealprep.ai.domain.entity.CallErrorKind;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when {@code AiService.execute} terminates in failure (4xx / 5xx
 * after retries / parse error). Implements {@link ScopeChangedEvent} with {@code
 * scopeKind="ai-call"}; the notification module listens for these to surface "AI features paused"
 * to the user when the failure is operational.
 */
public record AiCallFailedEvent(
    UUID callId,
    TaskType taskType,
    UUID userId,
    CallErrorKind errorKind,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "ai-call";
  }

  @Override
  public UUID scopeId() {
    return callId;
  }
}
