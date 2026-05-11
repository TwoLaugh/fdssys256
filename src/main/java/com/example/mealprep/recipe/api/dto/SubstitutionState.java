package com.example.mealprep.recipe.api.dto;

/**
 * State machine of a {@code RecipeSubstitution}: PROPOSED -> ACCEPTED / REJECTED, ACCEPTED ->
 * SUPERSEDED on promote-to-version.
 *
 * <p><b>LLD divergence</b>: LLD line 176 declares the column as {@code active | inactive |
 * promoted}; 01e renames to a four-state machine ({@code PROPOSED}, {@code ACCEPTED}, {@code
 * REJECTED}, {@code SUPERSEDED}). See ticket 01e for the rename rationale.
 */
public enum SubstitutionState {
  PROPOSED,
  ACCEPTED,
  REJECTED,
  SUPERSEDED
}
