package com.example.mealprep.preference.domain.entity;

/**
 * Where a learnt ingredient preference came from. {@code FEEDBACK} = derived from user feedback;
 * {@code INFERRED} = inferred from observed plan / cook history; {@code ONBOARDING} = supplied by
 * the user at signup.
 */
public enum IngredientPreferenceSource {
  FEEDBACK,
  INFERRED,
  ONBOARDING
}
