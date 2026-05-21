package com.example.mealprep.preference.domain.entity;

/**
 * Lifecycle of an {@code ActiveExperiment} embedded in a taste profile document. {@code TESTING} =
 * open, gathering evidence; {@code PROMOTED} = confirmed and merged back into a stable preference;
 * {@code DISCARDED} = falsified.
 */
public enum ExperimentStatus {
  TESTING,
  PROMOTED,
  DISCARDED
}
