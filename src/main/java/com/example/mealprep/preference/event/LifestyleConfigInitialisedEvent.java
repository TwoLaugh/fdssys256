package com.example.mealprep.preference.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a user's lifestyle config is created via {@code initialise()}
 * — the onboarding moment. Separate from {@link LifestyleConfigChangedEvent} so listeners can
 * differentiate "user just onboarded" from "user changed an existing setting".
 *
 * <p>{@code scopeKind = "lifestyle-config"}, {@code scopeId = userId}.
 */
public record LifestyleConfigInitialisedEvent(
    UUID userId, UUID lifestyleConfigId, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "lifestyle-config";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
