package com.example.mealprep.recipe.domain.entity;

/**
 * Whether the recipe's nutrition has been calculated. 01a always defaults to {@code PENDING};
 * actual calculation lives in the nutrition module's calculation service.
 */
public enum NutritionStatus {
  CALCULATED,
  PENDING,
  PARTIAL
}
