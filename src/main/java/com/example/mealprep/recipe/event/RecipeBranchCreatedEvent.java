package com.example.mealprep.recipe.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a new {@code RecipeBranch} lands on a recipe. recipe-01d
 * emits the event for downstream consumers (planner per-recipe bundle invalidation in 01f); no
 * listener wired in 01d.
 *
 * <p><b>LLD divergence</b>: LLD §Events section doesn't declare this event explicitly. 01d adds it
 * for symmetry with {@code RecipeUpdatedEvent} (manual-edit "something happened") and because the
 * planner's per-recipe cache key includes {@code branchId} — a new branch is a new cache key.
 *
 * <p>{@code scopeKind = "recipe-branch"}, {@code scopeId = branchId}.
 */
public record RecipeBranchCreatedEvent(
    UUID recipeId,
    UUID branchId,
    UUID parentBranchId,
    UUID branchPointVersionId,
    BigDecimal divergenceScore,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "recipe-branch";
  }

  @Override
  public UUID scopeId() {
    return branchId;
  }
}
