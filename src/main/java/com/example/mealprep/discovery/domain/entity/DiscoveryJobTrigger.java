package com.example.mealprep.discovery.domain.entity;

/** What caused a discovery job to be enqueued. Per LLD line 197. */
public enum DiscoveryJobTrigger {
  COLD_START,
  USER_INITIATED,
  SCHEDULED
}
