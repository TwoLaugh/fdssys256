package com.example.mealprep.planner.api.dto;

/**
 * The two kinds of in-meal addition the planner can bolt onto a slot's main recipe (Phase 2 of the
 * portion-scaling + additions design — {@code design/nutrition/portion-scaling-and-additions.md}).
 *
 * <ul>
 *   <li>{@link #INGREDIENT} — a raw whole food (½ avocado, a drizzle of olive oil, a cup of
 *       berries, a handful of nuts). Nutrition is USDA-derived and carried on the {@link Addition}
 *       itself; {@code ingredientMappingKey} drives the allergy check + the grocery line.
 *   <li>{@link #SIDE_RECIPE} — a pool recipe tagged as a side. Nutrition is the recipe's own
 *       per-serving figures; {@code recipeId} references it.
 * </ul>
 */
public enum AdditionKind {
  INGREDIENT,
  SIDE_RECIPE
}
