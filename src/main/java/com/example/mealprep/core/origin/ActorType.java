package com.example.mealprep.core.origin;

/**
 * Coarse audit-row classifier of who acted: a {@link #USER} directly, an {@link #AI} pipeline, or a
 * {@link #SYSTEM} scheduled / background process. Persisted in the {@code actor_type} column on
 * every per-module audit log.
 *
 * <p>Derived from {@link Origin#toActorType()} — never set directly by callers. Existing audit rows
 * predating {@code core-02b} have {@code actor_type = NULL}; reads must treat that as {@link
 * #USER}.
 */
public enum ActorType {
  USER,
  AI,
  SYSTEM
}
