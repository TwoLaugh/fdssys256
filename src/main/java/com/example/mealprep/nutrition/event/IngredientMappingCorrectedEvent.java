package com.example.mealprep.nutrition.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} on a successful {@code PUT
 * /api/v1/nutrition/ingredients/{searchTerm}/correction}. No listeners in 01d; the (future)
 * Feedback System subscribes to refresh its classifier context.
 *
 * <p>{@code scopeKind = "nutrition-ingredient-mapping"}, {@code scopeId = id}.
 */
public record IngredientMappingCorrectedEvent(
    UUID id, String searchTerm, UUID actorUserId, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "nutrition-ingredient-mapping";
  }

  @Override
  public UUID scopeId() {
    return id;
  }
}
