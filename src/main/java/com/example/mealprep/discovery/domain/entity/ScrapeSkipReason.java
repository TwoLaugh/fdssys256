package com.example.mealprep.discovery.domain.entity;

/**
 * Reason a candidate fetch was skipped before producing a {@code SUCCESS} ingest. Populated only
 * when {@code ScrapeOutcome} is {@code SKIPPED} (or one of its specialised siblings). Per LLD line
 * 197.
 */
public enum ScrapeSkipReason {
  DUPLICATE,
  HARD_CONSTRAINT,
  LOW_CONFIDENCE,
  RATE_LIMITED,
  ROBOTS_DISALLOWED,
  JOB_QUOTA_REACHED,
  AI_FILTER_REJECTED
}
