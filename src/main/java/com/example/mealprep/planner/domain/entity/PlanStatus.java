package com.example.mealprep.planner.domain.entity;

/**
 * Lifecycle states for a {@link Plan}. 01a defines all seven values per LLD §Entities, but only
 * uses {@code GENERATED} and {@code ACTIVE} for fixture data; the remaining transitions are reached
 * via the state machine landing in planner-01b.
 */
public enum PlanStatus {
  DRAFT,
  GENERATED,
  ACTIVE,
  SUPERSEDED,
  COMPLETED,
  REJECTED,
  ABANDONED
}
