package com.example.mealprep.planner.domain.entity;

/**
 * Reason a {@link MealSlot} is pinned during mid-week re-opt. Nullable on the slot — null means the
 * slot is regenerable. Set during re-opt scope building (planner-01i).
 *
 * <p>{@code LOCK_WINDOW} / {@code TRIGGER_AFFECTED} are 01i additions (additive — existing values
 * untouched): a PLANNED slot inside its lock-hours window is pinned ({@code LOCK_WINDOW}), and the
 * trigger's own affected slot under a provisions trigger is pinned ({@code TRIGGER_AFFECTED} — the
 * 01k listener already replaced it; the re-opt must not second-guess it) per invariant #5.
 */
public enum PinnedReason {
  EATEN,
  COOKED,
  COOKING,
  SKIPPED,
  USER_PINNED,
  LOCK_WINDOW,
  TRIGGER_AFFECTED
}
