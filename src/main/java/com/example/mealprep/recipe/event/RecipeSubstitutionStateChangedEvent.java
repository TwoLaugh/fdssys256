package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a substitution transitions state (accept, reject, or
 * promote-to-version). Idempotent no-op transitions ({@code ACCEPTED -> ACCEPTED}, {@code REJECTED
 * -> REJECTED}) do NOT emit this event.
 *
 * <p>{@code scopeKind = "recipe-substitution"}, {@code scopeId = substitutionId}.
 */
public record RecipeSubstitutionStateChangedEvent(
    UUID substitutionId,
    UUID recipeId,
    UUID versionId,
    SubstitutionState previousState,
    SubstitutionState newState,
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
