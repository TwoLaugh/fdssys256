package com.example.mealprep.adaptation.event;

import com.example.mealprep.core.events.ScopeChangedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Sealed sub-root for the adaptation module's event hierarchy. Permits the nine concrete record
 * variants enumerated in the LLD §Events block (lines 654-682). Cross-cutting listeners (audit, MDC
 * propagation, debug logging) subscribe on this type; listeners that care about a specific
 * lifecycle phase subscribe on the concrete record.
 *
 * <p>Extends {@link ScopeChangedEvent} (the non-sealed sub-root) so cross-cutting listeners can
 * still route by scope kind without importing the adaptation package. Each concrete variant
 * projects {@code scopeKind = "recipe"} (or {@code "pending-change"} / {@code "planner-hint"} as
 * appropriate) and {@code scopeId = recipeId} (the most useful scope for routing).
 *
 * <p>Mirrors the pattern established by {@code provisions.event.ProvisionChangedEvent} per
 * style-guide convention.
 */
public sealed interface AdaptationEvent extends ScopeChangedEvent
    permits AdaptationJobStartedEvent,
        AdaptationCandidateProducedEvent,
        AdaptationJobCompletedEvent,
        AdaptationJobFailedEvent,
        PendingChangeCreatedEvent,
        PendingChangeSupersededEvent,
        PendingChangeAcceptedEvent,
        PendingChangeRejectedEvent,
        PlannerHintEmittedEvent {

  /** Recipe targeted by this event — present on every variant. Always non-null. */
  UUID recipeId();

  @Override
  UUID traceId();

  @Override
  Instant occurredAt();
}
