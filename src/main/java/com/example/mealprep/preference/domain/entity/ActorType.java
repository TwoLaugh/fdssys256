package com.example.mealprep.preference.domain.entity;

/**
 * Classification of the actor behind a taste-profile audit row. {@code USER} = end-user request
 * (manual override, refresh button); {@code AI} = applied by the delta pipeline (deferred ticket);
 * {@code SYSTEM} = background process (initialise, scheduled refresh).
 *
 * <p>Anticipates the origin-tracking pattern landed by {@code tickets/core/02b}; the values line up
 * with that pattern's actor classification so cross-module readers (e.g. the feedback bridge) can
 * write the right value from day one.
 */
public enum ActorType {
  USER,
  AI,
  SYSTEM
}
