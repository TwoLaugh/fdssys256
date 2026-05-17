package com.example.mealprep.planner.domain.entity;

/**
 * Lifecycle status of a {@link MealPrepPlanReoptSuggestion} (planner-01i).
 *
 * <p>Distinct from {@link ReoptStatus} (01a's listener-dedupe row, which uses {@code DISMISSED}).
 * Ticket 01i invariant #10 + #14 lock this exact set: {@code PENDING} on create, {@code ACCEPTED} /
 * {@code REJECTED} via 01j's user action, {@code EXPIRED} via the 01l / follow-up sweep. The budget
 * guard (#14) counts {@code PENDING + REJECTED} against {@code maxSuggestionsPerPlan}.
 */
public enum ReoptSuggestionStatus {
  PENDING,
  ACCEPTED,
  REJECTED,
  EXPIRED
}
