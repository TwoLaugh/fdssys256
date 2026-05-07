package com.example.mealprep.auth.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} on a successful password change. Listeners (security telemetry,
 * "you changed your password from a new device" notifications — landing in later modules) react
 * accordingly.
 *
 * <p>{@code sessionsRevokedCount} excludes the calling session: the calling session is re-issued
 * (old row revoked, new row inserted with a fresh token), not counted as a revoke. The count
 * reflects how many other devices got bounced.
 */
public record UserPasswordChangedEvent(
    UUID userId, int sessionsRevokedCount, UUID traceId, Instant occurredAt)
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
