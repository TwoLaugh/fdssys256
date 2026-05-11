package com.example.mealprep.recipe.api.dto;

/**
 * Reason a substitution was created. Verbatim from LLD line 161-180 (RecipeSubstitution column
 * {@code reason}). Stored as a varchar(32) on the {@code recipe_substitutions} row.
 */
public enum SubstitutionReason {
  BUDGET,
  AVAILABILITY,
  DIETARY_TEMP,
  EQUIPMENT
}
