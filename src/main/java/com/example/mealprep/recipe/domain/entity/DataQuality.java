package com.example.mealprep.recipe.domain.entity;

/**
 * Indicates how confident we are in the recipe's accuracy. Defaults to {@code USER_VERIFIED} for
 * {@code manual_create}.
 */
public enum DataQuality {
  USER_VERIFIED,
  IMPORTED,
  AI_GENERATED,
  WEB_DISCOVERED
}
