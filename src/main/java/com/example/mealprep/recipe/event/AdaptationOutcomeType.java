package com.example.mealprep.recipe.event;

/**
 * Discriminator on {@link RecipeAdaptedEvent} identifying the kind of adaptation outcome. Per LLD
 * line 704 / ticket recipe-01f.
 */
public enum AdaptationOutcomeType {
  NEW_VERSION,
  BRANCH,
  SUBSTITUTION
}
