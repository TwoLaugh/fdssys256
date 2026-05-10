package com.example.mealprep.recipe.api.dto;

/**
 * Action emitted by the {@code VersionDiffer} for each change entry in the diff. {@code MODIFIED}
 * carries both {@code from} and {@code to}; {@code ADDED} only {@code to}; {@code REMOVED} only
 * {@code from}.
 */
public enum ChangeAction {
  ADDED,
  REMOVED,
  MODIFIED
}
