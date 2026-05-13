package com.example.mealprep.adaptation.domain.enums;

/**
 * Why an {@code AdaptationJob} reached the terminal {@code FAILED} status. Stored on {@code
 * adaptation_jobs.failure_reason}; verbatim from {@code lld/adaptation-pipeline.md} line 94.
 */
public enum JobFailureReason {
  HARD_FILTER,
  LOW_CONFIDENCE,
  CHARACTER_BREAK,
  AI_UNAVAILABLE,
  LLM_ERROR,
  REBASE_EXHAUSTED,
  WRITE_API_CONFLICT,
  TIMEOUT,
  UNKNOWN
}
