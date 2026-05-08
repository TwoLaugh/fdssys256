package com.example.mealprep.ai.event;

import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when {@code AiService.execute} completes successfully. Implements
 * {@link ScopeChangedEvent} with {@code scopeKind="ai-call"} so cross-cutting listeners (cost
 * dashboards, notification module) can subscribe by base type.
 *
 * <p>Cost is reported in micro-pence; 01a always emits {@code 0} (cost calculation lands in 01b).
 */
public record AiCallSucceededEvent(
    UUID callId,
    TaskType taskType,
    UUID userId,
    int latencyMs,
    long costMicroPence,
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
