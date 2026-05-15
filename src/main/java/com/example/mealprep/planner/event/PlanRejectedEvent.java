package com.example.mealprep.planner.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when the user rejects a {@code GENERATED} plan (transition to {@code REJECTED}).
 * Consumed by the optimisation-loop feedback path (signals "previous candidate was bad").
 * Publishing happens in 01j.
 */
public record PlanRejectedEvent(
    UUID planId,
    UUID householdId,
    LocalDate weekStartDate,
    String reason,
    UUID traceId,
    Instant occurredAt)
    implements PlannerEvent {}
