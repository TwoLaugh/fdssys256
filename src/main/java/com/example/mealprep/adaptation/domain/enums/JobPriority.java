package com.example.mealprep.adaptation.domain.enums;

/**
 * Worker scheduling priority — drives the CASE-priority order in {@code findNextPendingJobs}.
 * Verbatim from {@code lld/adaptation-pipeline.md} line 89.
 */
public enum JobPriority {
  SYNC,
  ASYNC,
  BATCH
}
