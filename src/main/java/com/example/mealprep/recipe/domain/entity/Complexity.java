package com.example.mealprep.recipe.domain.entity;

/** Recipe complexity tier. Nullable in 01a — user-supplied or absent. AI inference lands later. */
public enum Complexity {
  MINIMAL,
  MODERATE,
  INVOLVED
}
