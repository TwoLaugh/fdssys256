package com.example.mealprep.nutrition.domain.entity;

/**
 * Relative magnitude of a single-field feedback-driven target adjustment (nutrition-01i). Each step
 * maps to a percentage of the current value via {@code FeedbackAdjustmentProperties} ({@code SMALL}
 * = 5%, {@code MODERATE} = 10%, {@code LARGE} = 20% by default). Module-local per the style guide —
 * the feedback classifier emits the lowercase string {@code small} / {@code moderate} / {@code
 * large}, which the bridge maps onto this enum.
 */
public enum AdjustmentMagnitude {
  SMALL,
  MODERATE,
  LARGE
}
