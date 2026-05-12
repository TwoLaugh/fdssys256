package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Umbrella event published {@code AFTER_COMMIT} when a version's derived data — nutrition,
 * fingerprint, or embedding — is back-filled WITHOUT a new version being created. Per recipe-01f
 * ticket "LLD divergence" note: the LLD splits version-creation into {@code RecipeUpdatedEvent} +
 * {@code RecipeAdaptedEvent}; the parent's per-module guidance adds this umbrella for derived-data
 * back-fills so downstream caches refresh without re-triggering nutrition recalc.
 *
 * <p>{@code scopeKind = "recipe"}, {@code scopeId = recipeId}.
 */
public record RecipeEvolvedEvent(
    UUID recipeId, UUID versionId, EvolvedReason reason, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "recipe";
  }

  @Override
  public UUID scopeId() {
    return recipeId;
  }

  /** Why the version's derived data changed. */
  public enum EvolvedReason {
    NUTRITION_RECALCULATED,
    FINGERPRINT_REFRESHED,
    EMBEDDING_STORED,
    EMBEDDING_FAILED
  }
}
