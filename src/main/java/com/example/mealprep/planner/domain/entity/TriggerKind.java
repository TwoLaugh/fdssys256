package com.example.mealprep.planner.domain.entity;

/** What kicked off the plan generation. Recorded on {@link Plan#getTriggerKind()}. */
public enum TriggerKind {
  USER_INITIATED,
  SCHEDULED_WEEKLY,
  MID_WEEK_REOPT
}
