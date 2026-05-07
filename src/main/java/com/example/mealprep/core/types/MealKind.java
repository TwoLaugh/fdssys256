package com.example.mealprep.core.types;

/**
 * Type of meal as it appears in a recipe's metadata or a logged intake entry.
 *
 * <p>Distinct from {@link SlotKind} — a {@code SlotKind} is the planner's structural slot for a
 * given day; a {@code MealKind} is the meal-type tag a recipe carries. Most recipes are tagged with
 * one or two kinds (e.g. a porridge recipe is {@code BREAKFAST}; a stir-fry is {@code LUNCH} and
 * {@code DINNER}).
 */
public enum MealKind {
  BREAKFAST,
  LUNCH,
  DINNER,
  SNACK,
  SIDE,
  DESSERT,
  DRINK
}
