package com.example.mealprep.auth.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} on successful login. Suppressed for failed attempts. The auth
 * module emits but does not consume; downstream observability listeners will plug in over time.
 */
public record UserLoggedInEvent(
    UUID userId,
    UUID sessionId,
    String ipAddress,
    String userAgent,
    Instant loggedInAt,
    UUID traceId,
    Instant occurredAt)
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
