package com.example.mealprep.core.types;

/**
 * Canonical meal-slot kinds shared across modules. {@code CUSTOM} is the escape hatch for
 * household-defined slots (e.g. "post-workout shake") that don't fit the four standard meals.
 */
public enum SlotKind {
  BREAKFAST,
  LUNCH,
  DINNER,
  SNACK,
  CUSTOM
}
