package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a recipe is created. 01a has no listeners; downstream
 * consumers (planner, nutrition, ai for embedding) attach in their own tickets.
 *
 * <p>{@code scopeKind = "recipe"}, {@code scopeId = recipeId}.
 */
public record RecipeCreatedEvent(
    UUID recipeId, Catalogue catalogue, UUID traceId, Instant occurredAt)
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
