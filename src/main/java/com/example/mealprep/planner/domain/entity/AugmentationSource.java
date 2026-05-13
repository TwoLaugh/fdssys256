package com.example.mealprep.planner.domain.entity;

/**
 * Origin of a Phase-2 augmentation applied to a {@link ScheduledRecipe}. Populated by planner-01h
 * ({@code Phase2Augmenter}); null until Phase-2 runs.
 */
public enum AugmentationSource {
  LLM,
  USER
}
