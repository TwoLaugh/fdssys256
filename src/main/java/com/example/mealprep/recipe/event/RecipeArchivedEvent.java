package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a recipe transitions to archived (manual admin trigger,
 * daily-scanner inactivity flag, or user-demotion side-effect). Per LLD line 698 + recipe-01g
 * ticket. Downstream consumers (planner index invalidation, search re-rank) attach in their own
 * tickets.
 *
 * <p>{@code scopeKind = "recipe"}, {@code scopeId = recipeId}.
 */
public record RecipeArchivedEvent(
    UUID recipeId, ArchiveCause cause, UUID traceId, Instant occurredAt)
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
