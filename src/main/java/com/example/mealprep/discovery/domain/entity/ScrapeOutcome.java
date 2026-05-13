package com.example.mealprep.discovery.domain.entity;

/** Per-fetch outcome recorded on a {@code DiscoveryScrapeLog} row. Per LLD line 197. */
public enum ScrapeOutcome {
  SUCCESS,
  SKIPPED,
  RATE_LIMITED,
  ROBOTS_DISALLOWED,
  HTTP_ERROR,
  EXTRACTION_FAILED,
  DUPLICATE,
  HARD_CONSTRAINT_VIOLATION
}
