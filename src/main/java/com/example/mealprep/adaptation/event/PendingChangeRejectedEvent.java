package com.example.mealprep.adaptation.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a user rejects a pending change. The prompt-quality dashboard
 * subscribes to surface reject-rates per template version.
 *
 * <p>Per LLD §Events lines 677-678.
 */
public record PendingChangeRejectedEvent(
    UUID pendingChangeId, UUID recipeId, UUID userId, UUID traceId, Instant occurredAt)
    implements AdaptationEvent {

  @Override
  public String scopeKind() {
    return "recipe";
  }

  @Override
  public UUID scopeId() {
    return recipeId;
  }
}
