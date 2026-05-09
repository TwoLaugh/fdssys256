package com.example.mealprep.recipe.domain.entity;

/**
 * Cause of a {@code RecipeVersion} write. 01a only ever writes {@code MANUAL_CREATE}; the other
 * triggers are populated by the manual-edit / import / adaptation / branching flows that land in
 * later sub-tickets.
 */
public enum VersionTrigger {
  MANUAL_CREATE,
  MANUAL_EDIT,
  IMPORT,
  ADAPTATION_PIPELINE,
  SUBSTITUTION_PROMOTION,
  BRANCH_CREATION,
  REVERT
}
