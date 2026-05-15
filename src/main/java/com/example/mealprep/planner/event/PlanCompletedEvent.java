package com.example.mealprep.planner.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when the weekly sweep transitions a finished {@code ACTIVE} plan to {@code COMPLETED}.
 * Consumed by notification ("week complete"), feedback module, and analytics. Publishing happens in
 * 01k alongside the {@code @Scheduled} sweep.
 */
public record PlanCompletedEvent(
    UUID planId, UUID householdId, LocalDate weekStartDate, UUID traceId, Instant occurredAt)
    implements PlannerEvent {}
