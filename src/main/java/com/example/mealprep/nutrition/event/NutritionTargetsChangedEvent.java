package com.example.mealprep.nutrition.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a user's nutrition targets are updated and at least one field
 * changed. {@code changedFieldPaths} carries the dotted-path identifiers of the changed fields
 * (e.g. {@code "calorieTarget"}, {@code "perMealDistribution"}).
 *
 * <p>{@code scopeKind = "nutrition-targets"}, {@code scopeId = targetsId}.
 *
 * <p>01a has no listeners; downstream sub-tickets attach for cache invalidation, planner re-runs,
 * etc.
 */
public record NutritionTargetsChangedEvent(
    UUID userId, UUID targetsId, Set<String> changedFieldPaths, UUID traceId, Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "nutrition-targets";
  }

  @Override
  public UUID scopeId() {
    return targetsId;
  }
}
