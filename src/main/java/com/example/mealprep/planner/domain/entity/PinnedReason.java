package com.example.mealprep.planner.domain.entity;

/**
 * Reason a {@link MealSlot} is pinned during mid-week re-opt. Nullable on the slot — null means the
 * slot is regenerable. Set during re-opt scope building (planner-01i).
 */
public enum PinnedReason {
  EATEN,
  COOKED,
  COOKING,
  SKIPPED,
  USER_PINNED
}
