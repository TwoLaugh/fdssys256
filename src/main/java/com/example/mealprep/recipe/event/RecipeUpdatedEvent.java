package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a manual edit (or any non-create version write) lands a new
 * {@code RecipeVersion} on a recipe. 01c emits the event for downstream consumers (nutrition recalc
 * in 01f, planner re-opt in a future ticket); no listener is wired in 01c.
 *
 * <p>{@code scopeKind = "recipe"}, {@code scopeId = recipeId}.
 */
public record RecipeUpdatedEvent(
    UUID recipeId,
    UUID branchId,
    UUID newVersionId,
    int newVersionNumber,
    VersionTrigger trigger,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "recipe";
  }

  @Override
  public UUID scopeId() {
    return recipeId;
  }
}
