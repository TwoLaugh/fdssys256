package com.example.mealprep.adaptation.domain.service.internal;

import com.example.mealprep.adaptation.ai.RecipeAdaptationResponse;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import org.springframework.stereotype.Component;

/**
 * Confidence-floor gate: returns {@link ValidationResult#PASSED} when {@code confidence >=
 * config.lowConfidenceFloor()}, else {@link ValidationResult#LOW_CONFIDENCE}.
 *
 * <p>Per ticket 01c §Step 6 — does NOT throw. Low-confidence overrides {@code approvalPolicy} to
 * {@code PENDING_CHANGE} (defensive flag for user review even on SYSTEM catalogue) per LLD line
 * 753.
 */
@Component
public class ConfidenceFloorGate {

  private final AdaptationConfig config;

  public ConfidenceFloorGate(AdaptationConfig config) {
    this.config = config;
  }

  /** Returns {@code PASSED} or {@code LOW_CONFIDENCE}; never throws. */
  public ValidationResult evaluate(RecipeAdaptationResponse response) {
    if (response == null || response.confidence() == null) {
      return ValidationResult.LOW_CONFIDENCE;
    }
    return response.confidence().compareTo(config.lowConfidenceFloor()) >= 0
        ? ValidationResult.PASSED
        : ValidationResult.LOW_CONFIDENCE;
  }
}
