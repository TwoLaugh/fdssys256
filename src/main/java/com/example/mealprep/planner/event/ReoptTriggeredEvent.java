package com.example.mealprep.planner.event;

import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when a mid-week re-opt pass begins for an {@code ACTIVE} plan. Carries the classified
 * trigger kind and the upstream event id so the decision log can trace cause → effect. Publishing
 * happens in 01i.
 */
public record ReoptTriggeredEvent(
    UUID planId,
    UUID householdId,
    LocalDate weekStartDate,
    ReoptTriggerKind trigger,
    UUID triggerEventId,
    UUID traceId,
    Instant occurredAt)
    implements PlannerEvent {}
