package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a recipe is promoted from one catalogue to another (today:
 * SYSTEM → USER via {@code POST /promote}). Per LLD line 701 + recipe-01g ticket §Promote system →
 * user. Downstream consumers (planner index, search) attach in their own tickets.
 *
 * <p>{@code scopeKind = "recipe"}, {@code scopeId = recipeId}.
 */
public record RecipePromotedEvent(
    UUID recipeId,
    UUID userId,
    Catalogue fromCatalogue,
    Catalogue toCatalogue,
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
