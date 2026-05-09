package com.example.mealprep.nutrition.domain.entity;

/**
 * High-level goal that frames a user's targets. Drives the goal-defaults resolver in a later
 * sub-ticket; in 01a the value is just a label persisted on {@link NutritionTargets}.
 */
public enum Goal {
  LOSE_WEIGHT,
  MAINTAIN,
  GAIN_WEIGHT
}
