package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a new substitution proposal lands. Lets the planner's
 * per-recipe cache invalidate proactively.
 *
 * <p>{@code scopeKind = "recipe-substitution"}, {@code scopeId = substitutionId}.
 */
public record RecipeSubstitutionCreatedEvent(
    UUID substitutionId,
    UUID recipeId,
    UUID versionId,
    UUID branchId,
    SubstitutionReason reason,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "recipe-substitution";
  }

  @Override
  public UUID scopeId() {
    return substitutionId;
  }
}
