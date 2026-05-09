package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a new {@code RecipeVersion} is written. Required by
 * recipe-01h's async embedding listener; no listener in 01a.
 *
 * <p>{@code scopeKind = "recipe-version"}, {@code scopeId = versionId}.
 */
public record RecipeVersionCreatedEvent(
    UUID versionId,
    UUID recipeId,
    UUID branchId,
    int versionNumber,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "recipe-version";
  }

  @Override
  public UUID scopeId() {
    return versionId;
  }
}
