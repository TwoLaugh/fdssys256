package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a recipe's image is uploaded or replaced via {@code POST
 * /api/v1/recipes/{recipeId}/image}. No listeners in recipe-02a; emitted for downstream consumers
 * (CDN cache invalidation, planner UI cache flush, notification module's "recipe updated" chain).
 *
 * <p>{@code scopeKind = "recipe"}, {@code scopeId = recipeId}.
 */
public record RecipeImageUpdatedEvent(
    UUID recipeId, String imageUrl, UUID actorUserId, UUID traceId, Instant occurredAt)
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
