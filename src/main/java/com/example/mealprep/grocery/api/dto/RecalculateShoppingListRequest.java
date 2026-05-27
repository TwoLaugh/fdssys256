package com.example.mealprep.grocery.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Recalculate a shopping list from a plan + provisions snapshot. Per lld/grocery.md line 472.
 *
 * <p>DIVERGENCE (ticket 01a, locked): {@code planGeneration} (Integer, nullable) — renamed from the
 * LLD's {@code planRevision} to track the planner's {@code generation} counter. {@code null}
 * generation means latest.
 */
public record RecalculateShoppingListRequest(@NotNull UUID planId, Integer planGeneration) {}
