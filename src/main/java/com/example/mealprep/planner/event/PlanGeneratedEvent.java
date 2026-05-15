package com.example.mealprep.planner.event;

import com.example.mealprep.planner.domain.entity.TriggerKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published after a plan is persisted with status {@code GENERATED}. Consumers: optimisation-loop
 * audit (decision log already written by the generation flow), notification module ("your plan is
 * ready"), debug logger. Publishing happens in 01j; 01b only ships the record shape.
 */
public record PlanGeneratedEvent(
    UUID planId,
    UUID householdId,
    LocalDate weekStartDate,
    int generation,
    TriggerKind trigger,
    UUID triggerEventId,
    UUID decisionId,
    boolean coldStart,
    boolean aiAugmented,
    boolean qualityWarning,
    UUID traceId,
    Instant occurredAt)
    implements PlannerEvent {}
