package com.example.mealprep.auth.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} on successful registration. Listeners (preference, nutrition,
 * household — landing in later modules) seed per-user records.
 *
 * <p>Implements {@link ScopeChangedEvent} with {@code scopeKind="user"}. Carries no PII beyond the
 * username — observers expecting more must look up the user via {@code AuthQueryService}.
 */
public record UserRegisteredEvent(
    UUID userId, String username, Instant registeredAt, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "user";
  }

  @Override
  public UUID scopeId() {
    return userId;
  }
}
