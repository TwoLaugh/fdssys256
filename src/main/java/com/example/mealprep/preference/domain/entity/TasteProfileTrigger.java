package com.example.mealprep.preference.domain.entity;

/**
 * What triggered a {@code preference_taste_profile_versions} snapshot. {@code BATCH} is the
 * per-feedback-batch AI delta apply; {@code WEEKLY} is the scheduled refresh; {@code MANUAL} is a
 * user-initiated override or refresh.
 */
public enum TasteProfileTrigger {
  BATCH,
  WEEKLY,
  MANUAL
}
