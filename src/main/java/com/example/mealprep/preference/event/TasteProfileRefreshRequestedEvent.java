package com.example.mealprep.preference.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Fired by the POST {@code /refresh-now} endpoint to request the feedback module's {@code
 * TasteProfileDeltaTask} to schedule an AI delta apply. In 01c, no listener exists — the event is
 * published but consumed by the (deferred) delta-applier ticket. That's fine: an event with no
 * consumer is a no-op.
 *
 * <p>{@code scopeKind = "taste-profile"}, {@code scopeId = userId}.
 */
public record TasteProfileRefreshRequestedEvent(
    UUID userId,
    UUID tasteProfileId,
    String feedbackRangeStart,
    String feedbackRangeEnd,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "taste-profile";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
