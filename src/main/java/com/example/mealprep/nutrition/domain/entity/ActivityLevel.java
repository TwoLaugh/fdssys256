package com.example.mealprep.nutrition.domain.entity;

/**
 * Per-day activity level used to layer calorie / carb adjustments on top of the base targets. The
 * actual per-day log table ships in 01b; 01a only persists the adjustment rules.
 */
public enum ActivityLevel {
  REST_DAY,
  LIGHT_ACTIVITY,
  TRAINING_DAY,
  HEAVY_TRAINING
}
