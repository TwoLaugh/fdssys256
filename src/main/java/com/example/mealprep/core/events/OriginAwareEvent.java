package com.example.mealprep.core.events;

import com.example.mealprep.core.origin.Origin;

/**
 * Extension of {@link MealPrepEvent} carrying the origin metadata of the request that produced the
 * event. Listeners that branch on origin (e.g. a notification service suppressing self-edited
 * notifications when origin is USER) implement this directly; cross-cutting listeners can stay on
 * the base {@link MealPrepEvent} type and ignore origin.
 *
 * <p>Existing events from earlier modules continue to implement {@link MealPrepEvent} (or {@link
 * ScopeChangedEvent}) directly — USER-origin events have no need to surface origin fields, and no
 * listener relies on origin metadata as of {@code core-02b}. New events shipping in {@code
 * feedback-01g} / adaptation / notification ticket land implement this interface and populate the
 * fields from {@link com.example.mealprep.core.origin.OriginContext}.
 *
 * <p>Per design/origin-tracking-pattern.md §"Where origin information is persisted" — domain events
 * carry {@code origin} + {@code originTrace}.
 */
public non-sealed interface OriginAwareEvent extends MealPrepEvent {

  /** The origin of the action that produced this event. Never null. */
  Origin origin();

  /** Mirrors the {@code X-Origin-Trace} header from the producing request; null for USER. */
  String originTrace();
}
