package com.example.mealprep.planner.event;

import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Published when a re-opt pass produces a suggested set of slot changes for the user to review.
 * Carries the suggestion id, classified trigger, affected slots, and a short human-readable
 * summary. Consumed by notification (push the suggestion to the user). Publishing happens in 01i.
 */
public record ReoptSuggestedEvent(
    UUID planId,
    UUID householdId,
    LocalDate weekStartDate,
    UUID suggestionId,
    ReoptTriggerKind trigger,
    UUID triggerEventId,
    List<UUID> affectedSlotIds,
    String summary,
    UUID traceId,
    Instant occurredAt)
    implements PlannerEvent {}
