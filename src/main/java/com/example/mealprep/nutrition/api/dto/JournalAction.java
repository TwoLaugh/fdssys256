package com.example.mealprep.nutrition.api.dto;

/**
 * Action carried by {@link com.example.mealprep.nutrition.event.FoodMoodEntryWrittenEvent}. Not
 * surfaced via REST; the (future) Feedback System listener uses it to refresh classifier context.
 */
public enum JournalAction {
  CREATED,
  UPDATED,
  DELETED
}
