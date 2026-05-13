package com.example.mealprep.adaptation.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a user accepts a pending change. Carries the resulting
 * version id (the row {@code RecipeWriteApi} just inserted) so subscribers can join to the
 * catalogue side.
 *
 * <p>Per LLD §Events lines 674-675.
 */
public record PendingChangeAcceptedEvent(
    UUID pendingChangeId,
    UUID recipeId,
    UUID userId,
    UUID resultingVersionId,
    boolean wasModified,
    UUID traceId,
    Instant occurredAt)
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
