package com.example.mealprep.adaptation.exception;

/**
 * Thrown when the final-diff recheck of {@code HardConstraintFilterService} (run AFTER Stage C —
 * never bypassed per HLD §Guardrails) catches the LLM stitching together an infeasible candidate.
 * Mapped to HTTP 422.
 */
public class AdaptationHardConstraintViolationException extends AdaptationException {

  public AdaptationHardConstraintViolationException(String message) {
    super(message);
  }
}
