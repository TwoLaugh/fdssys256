package com.example.mealprep.grocery.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Recalculate a shopping list from a plan + provisions snapshot. Per lld/grocery.md line 472.
 *
 * <p>DIVERGENCE (ticket 01a, locked): {@code planGeneration} (Integer, nullable) — renamed from the
 * LLD's {@code planRevision} to track the planner's {@code generation} counter. {@code null}
 * generation means latest.
 *
 * <p>{@code force} (frontend-gaps: grocery-recalculate-pantry-drift) governs same-generation
 * behaviour. {@code false} (default) keeps the idempotent contract — a recalculate for an existing
 * {@code (planId, planGeneration)} returns the cached list unchanged. {@code true} rebuilds the
 * existing list's lines in place to pick up pantry/provisions drift within the generation,
 * preserving decided fulfilment (bought-marks) by mapping key; the provisions drift listener
 * ({@code ItemSpoiled} / {@code ItemRanOut}) sends {@code force=true}.
 */
public record RecalculateShoppingListRequest(
    @NotNull UUID planId, Integer planGeneration, boolean force) {

  /**
   * Plain recalculate (force=false) — the idempotent plan-generated path. Kept so the many existing
   * two-arg call sites compile unchanged.
   */
  public RecalculateShoppingListRequest(UUID planId, Integer planGeneration) {
    this(planId, planGeneration, false);
  }
}
