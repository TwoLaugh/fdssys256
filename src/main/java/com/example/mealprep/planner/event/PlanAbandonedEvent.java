package com.example.mealprep.planner.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when the user abandons an {@code ACTIVE} plan mid-week (transition to {@code
 * ABANDONED}). Consumed by grocery (cancel outstanding orders where possible) and notification.
 * Publishing happens in 01j.
 */
public record PlanAbandonedEvent(
    UUID planId,
    UUID householdId,
    LocalDate weekStartDate,
    String reason,
    UUID traceId,
    Instant occurredAt)
    implements PlannerEvent {}
