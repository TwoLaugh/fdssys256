package com.example.mealprep.provisions.api.dto;

import com.example.mealprep.provisions.domain.entity.StapleStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/v1/provisions/inventory/{itemId}/status} (pantry.md §9 Q1). A focused
 * staple-status edit for a STATUS-tracked item — the single-tap STOCKED → LOW → OUT chip on the
 * Pantry page, without re-sending the whole item via PUT.
 *
 * <p>{@code newStatus} is the absolute replacement value. {@code expectedVersion} carries the JPA
 * {@code @Version} the caller last saw; a mismatch surfaces as 409. A transition to {@code OUT} on
 * a staple publishes {@code ItemRanOutEvent} (the replenishment promise — same single rule as the
 * full PUT path) and every effective change writes one {@code actor = USER} audit row ({@code
 * fieldChanged: status}); a no-op (same status) writes nothing and does not bump {@code version}.
 */
public record AdjustInventoryStatusRequest(@NotNull StapleStatus newStatus, long expectedVersion) {}
