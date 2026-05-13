package com.example.mealprep.planner.domain.entity;

/**
 * Source of a re-opt suggestion. Listener tickets (01k) classify upstream events into one of these
 * categories before creating the suggestion row.
 */
public enum ReoptTriggerKind {
  PROVISIONS,
  NUTRITION,
  PREFERENCE,
  HOUSEHOLD_SETTINGS,
  USER
}
