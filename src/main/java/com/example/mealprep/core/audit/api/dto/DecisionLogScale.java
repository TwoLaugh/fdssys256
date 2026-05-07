package com.example.mealprep.core.audit.api.dto;

/**
 * Coarse classifier for the scale at which a decision was made.
 *
 * <p>Per {@code lld/optimisation-loop.md}, the loop applies cleanly at two scales: WEEK (Meal
 * Planner) and RECIPE (Recipe Adaptation Pipeline). {@link #OTHER} covers decisions that aren't
 * loop iterations — manual edits, audit-log entries from cross-cutting concerns, etc.
 */
public enum DecisionLogScale {
  WEEK,
  RECIPE,
  OTHER
}
