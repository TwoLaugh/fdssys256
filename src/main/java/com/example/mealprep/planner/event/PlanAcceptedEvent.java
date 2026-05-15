package com.example.mealprep.planner.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when the user accepts a {@code GENERATED} plan (transition to {@code ACTIVE}). Consumed
 * by grocery (initial Tesco order assembly) and notification. Publishing happens in 01j.
 */
public record PlanAcceptedEvent(
    UUID planId, UUID householdId, LocalDate weekStartDate, UUID traceId, Instant occurredAt)
    implements PlannerEvent {}
