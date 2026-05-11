package com.example.mealprep.nutrition.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.nutrition.api.dto.DirectiveType;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} on a successful inbound directive persist (LLD line 901). The
 * (future) notification module subscribes to fan-out to the user; 01e has no in-tree listener.
 *
 * <p>{@code scopeKind = "nutrition-health-directive"}, {@code scopeId = directiveId}.
 */
public record HealthDirectiveReceivedEvent(
    UUID userId,
    UUID directiveId,
    DirectiveType directiveType,
    String sourcePlatform,
    Instant receivedAt,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "nutrition-health-directive";
  }

  @Override
  public UUID scopeId() {
    return directiveId;
  }
}
