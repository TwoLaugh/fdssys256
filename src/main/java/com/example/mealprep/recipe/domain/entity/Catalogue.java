package com.example.mealprep.recipe.domain.entity;

/**
 * Distinguishes user-created recipes from system-provided ones. {@code USER} is the default for
 * {@code POST /recipes}; {@code SYSTEM} catalogue entries are seeded outside the user write path.
 */
public enum Catalogue {
  USER,
  SYSTEM
}
