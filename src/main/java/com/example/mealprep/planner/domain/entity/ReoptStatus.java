package com.example.mealprep.planner.domain.entity;

/** Status of a {@link ReoptSuggestion}. Default {@code PENDING} on insert. */
public enum ReoptStatus {
  PENDING,
  ACCEPTED,
  DISMISSED,
  EXPIRED
}
