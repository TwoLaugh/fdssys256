package com.example.mealprep.nutrition.domain.entity;

/**
 * Direction of enforcement for a calorie / macro target. {@code UPPER_LIMIT} is an at-most ceiling,
 * {@code LOWER_FLOOR} is an at-least floor, {@code BOTH_BOUNDED} is the symmetric tolerance band.
 */
public enum EnforcementDirection {
  UPPER_LIMIT,
  LOWER_FLOOR,
  BOTH_BOUNDED
}
