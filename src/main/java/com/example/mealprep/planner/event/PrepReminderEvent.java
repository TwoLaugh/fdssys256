package com.example.mealprep.planner.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Published {@code AFTER_COMMIT} when a prep-reminder trigger fires for an upcoming planned meal
 * slot that has an advance-prep step (e.g. marinate, soak). The scanner that produces these ships
 * in a sibling ticket; this record is the minimal contract the notification payload needs.
 *
 * <p>Consumed by the notification module ({@code PlannerEventListener.onPrepReminder}) to raise a
 * {@code PLANNER_PREP_REMINDER} notification, bundled per {@code mealSlotId}.
 *
 * <p>This event implements {@link ScopeChangedEvent} directly rather than joining the sealed {@code
 * PlannerEvent} hierarchy: it is a per-slot reminder, not a plan-lifecycle event, and is keyed on a
 * meal slot rather than a plan. {@code scopeKind = "planned-meal-slot"}, {@code scopeId =
 * plannedMealSlotId}.
 */
public record PrepReminderEvent(
    UUID userId,
    UUID plannedMealSlotId,
    UUID recipeId,
    String prepStep,
    Instant prepBy,
    UUID traceId,
    Instant occurredAt)
    implements ScopeChangedEvent {

  @Override
  public String scopeKind() {
    return "planned-meal-slot";
  }

  @Override
  public UUID scopeId() {
    return plannedMealSlotId;
  }
}
