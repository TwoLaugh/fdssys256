package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} by the (future) Adaptation Pipeline's write path through {@code
 * RecipeWriteApi} — fires on every adapter-driven new version, new branch, or substitution. Carries
 * {@code adapterTraceId} so downstream consumers can join back to the pipeline run that produced
 * this outcome. Per LLD lines 704-706.
 *
 * <p>{@code scopeKind = "recipe"}, {@code scopeId = recipeId}.
 */
public record RecipeAdaptedEvent(
    UUID recipeId,
    UUID branchId,
    UUID newVersionId,
    AdaptationOutcomeType outcomeType,
    UUID adapterTraceId,
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
