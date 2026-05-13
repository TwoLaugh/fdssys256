package com.example.mealprep.adaptation.domain.enums;

/**
 * Lifecycle status of an {@code AdaptationJob}; walks {@code PENDING -> RUNNING -> DONE | FAILED}
 * per {@code lld/adaptation-pipeline.md} line 80.
 */
public enum JobStatus {
  PENDING,
  RUNNING,
  DONE,
  FAILED
}
