package com.example.mealprep.planner.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when an {@code ACTIVE} plan is replaced by a mid-week re-opt promotion (transition to
 * {@code SUPERSEDED}). {@code planId} is the OLD plan's id; {@code replacedByPlanId} is the new
 * plan's id. Consumed by grocery (diff orders), notification, and the historical decision log.
 * Publishing happens in 01i when the new plan is promoted.
 */
public record PlanSupersededEvent(
    UUID planId,
    UUID replacedByPlanId,
    UUID householdId,
    LocalDate weekStartDate,
    UUID traceId,
    Instant occurredAt)
    implements PlannerEvent {}
