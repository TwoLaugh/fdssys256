package com.example.mealprep.nutrition.domain.entity;

/**
 * Direction of a single-field feedback-driven target adjustment (nutrition-01i). {@code INCREASE}
 * nudges the named target up by the relative magnitude; {@code DECREASE} nudges it down.
 * Module-local per the style guide — the feedback classifier emits the lowercase string {@code
 * increase} / {@code decrease}, which the bridge maps onto this enum.
 */
public enum AdjustmentDirection {
  INCREASE,
  DECREASE
}
