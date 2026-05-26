package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a recipe is created. 01a has no listeners; downstream
 * consumers (planner, nutrition, ai for embedding) attach in their own tickets.
 *
 * <p>{@code scopeKind = "recipe"}, {@code scopeId = recipeId}.
 *
 * <p>Carries the owning {@code userId} and the recipe's {@code dataQuality} so the adaptation
 * Trigger-1 listener can build a faithful {@code ImportJobRequest} without a cross-module
 * repository read (ArchUnit forbids it) and without the v1 placeholder values it previously fell
 * back to.
 */
public record RecipeCreatedEvent(
    UUID recipeId,
    Catalogue catalogue,
    UUID userId,
    DataQuality dataQuality,
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
