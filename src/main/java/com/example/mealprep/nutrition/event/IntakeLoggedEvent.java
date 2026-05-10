package com.example.mealprep.nutrition.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import com.example.mealprep.nutrition.domain.entity.IntakeAuditAction;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} after each intake-write (confirm / override / edit / skip /
 * snack-add / snack-remove). Skipped on idempotent confirm (re-confirm of an already-CONFIRMED slot
 * writes no audit row and emits no event).
 *
 * <p>{@code mealSlot} is null for {@code SNACK_*} actions; {@code snackId} is null for non-{@code
 * SNACK_*} actions.
 *
 * <p>{@code scopeKind = "nutrition-intake-day"}, {@code scopeId = intakeDayId}. No listeners in 01b
 * — emitted for downstream consumers (future divergence detector, planner re-opt offer).
 */
public record IntakeLoggedEvent(
    UUID userId,
    UUID intakeDayId,
    LocalDate onDate,
    IntakeAuditAction action,
    MealSlot mealSlot,
    UUID snackId,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "nutrition-intake-day";
  }

  @Override
  public UUID scopeId() {
    return intakeDayId;
  }
}
