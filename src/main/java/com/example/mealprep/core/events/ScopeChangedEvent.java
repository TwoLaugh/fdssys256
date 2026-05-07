package com.example.mealprep.core.events;

import java.util.UUID;

/**
 * Sub-interface of {@link MealPrepEvent} for events that target a specific bounded scope — a
 * plan-week, a recipe, a user's preference profile, etc.
 *
 * <p>Listeners that route by scope (e.g. invalidate cache for "this week's plan") inspect {@link
 * #scopeKind()} and {@link #scopeId()} without needing to know the concrete event type.
 */
public non-sealed interface ScopeChangedEvent extends MealPrepEvent {
  /**
   * Scope category, e.g. {@code "plan-week"}, {@code "recipe"}, {@code "preference"}. Always
   * non-null. Convention: lowercase-hyphenated.
   */
  String scopeKind();

  /** Identifier of the specific scope instance. Always non-null. */
  UUID scopeId();
}
