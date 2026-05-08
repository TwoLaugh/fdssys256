package com.example.mealprep.ai.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when {@code CostBudgetGuard} rejects an AI call because the
 * rolling-window per-user cap was reached. Implements {@link ScopeChangedEvent} with {@code
 * scopeKind="ai-budget"} so downstream listeners (notification module, throttling of other AI
 * features) can react per user without coupling to the AI module's concrete event type.
 *
 * <p>{@code spentPence} / {@code limitPence} are pence (not micropence) for human-readable
 * downstream formatting; {@code window} matches the configured rolling window.
 */
public record CostBudgetExceededEvent(
    UUID userId,
    BigDecimal spentPence,
    BigDecimal limitPence,
    Duration window,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "ai-budget";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
