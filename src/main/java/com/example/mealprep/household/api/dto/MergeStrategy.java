package com.example.mealprep.household.api.dto;

/**
 * Strategy tag on {@link MergedSoftPreferencesDto}. v1 ships a single value; numerical-weighting
 * tuning is out of scope per LLD line 204.
 */
public enum MergeStrategy {
  MEAN_WEIGHTED_BY_PRIORITY
}
