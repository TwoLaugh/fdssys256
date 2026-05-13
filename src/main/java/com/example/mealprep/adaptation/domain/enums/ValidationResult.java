package com.example.mealprep.adaptation.domain.enums;

/**
 * Outcome of the trace-level validation gates (hard-filter recheck, character-preservation,
 * confidence floor). Verbatim from {@code lld/adaptation-pipeline.md} line 177.
 */
public enum ValidationResult {
  PASSED,
  FAILED_HARD,
  LOW_CONFIDENCE,
  CHARACTER_BREAK,
  NO_CHANGE
}
