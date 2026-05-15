package com.example.mealprep.planner.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sealed marker for all planner-module domain events per LLD §Events. Listeners can accept the base
 * type for cross-cutting concerns (audit log, trace-id propagation, dev logging) without importing
 * every record.
 *
 * <p>TODO: extend {@code com.example.mealprep.core.events.MealPrepEvent} once that interface's
 * {@code permits} clause is reopened to admit the planner family. As of 01b, {@code MealPrepEvent}
 * only permits {@code ScopeChangedEvent}; widening it is a cross-cutting {@code core}-module
 * concern. Until then the planner family is self-sealed.
 */
public sealed interface PlannerEvent
    permits PlanGeneratedEvent,
        PlanAcceptedEvent,
        PlanSupersededEvent,
        PlanCompletedEvent,
        PlanRejectedEvent,
        PlanAbandonedEvent,
        ReoptTriggeredEvent,
        ReoptSuggestedEvent {

  /** Id of the plan this event concerns. Always non-null. */
  UUID planId();

  /** Trace identifier linking related events across a multi-stage flow. Always non-null. */
  UUID traceId();

  /** When the event occurred. Always non-null. */
  Instant occurredAt();
}
