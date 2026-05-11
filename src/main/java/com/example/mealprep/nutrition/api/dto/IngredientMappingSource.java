package com.example.mealprep.nutrition.api.dto;

/**
 * Where the canonical nutrition row for an ingredient came from. Stored as the lowercase variant in
 * the DB ({@code usda} / {@code open_food_facts} / {@code manual}); persisted via JPA {@code
 * EnumType.STRING} on a {@code varchar(24)} column.
 */
public enum IngredientMappingSource {
  USDA,
  OPEN_FOOD_FACTS,
  MANUAL
}
