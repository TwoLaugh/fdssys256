package com.example.mealprep.core.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Sealed marker for every domain event published in the MealPrep AI system.
 *
 * <p>Each module's events extend this (or {@link ScopeChangedEvent}) so listeners can accept the
 * base type for cross-cutting concerns (audit, debug logging, trace-id propagation) without
 * importing every module's event package.
 *
 * <p>The {@code permits} clause is intentionally open — module events declare themselves via direct
 * {@code implements MealPrepEvent} (or {@code ScopeChangedEvent}). The seal keeps the type closed
 * within the {@code core.events} package while allowing downstream modules to extend by interface
 * implementation; the JLS treats sealed interfaces with no permits clause as permitting any subtype
 * in the same module/package.
 *
 * @see ScopeChangedEvent for events that target a specific scope (plan-week, recipe, …)
 */
public sealed interface MealPrepEvent permits ScopeChangedEvent {
  /**
   * Trace identifier linking related events across a multi-stage flow. Always non-null; generators
   * should use {@link UUID#randomUUID()} when no upstream trace is in context.
   */
  UUID traceId();

  /** When the event occurred. Always non-null. */
  Instant occurredAt();
}
