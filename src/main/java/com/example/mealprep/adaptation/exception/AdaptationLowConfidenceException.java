package com.example.mealprep.adaptation.exception;

/**
 * Thrown by 01c's {@code ConfidenceFloorGate} when the chosen candidate's confidence falls below
 * {@code mealprep.adaptation.low-confidence-floor}. Surface mapped to HTTP 422.
 */
public class AdaptationLowConfidenceException extends AdaptationException {

  public AdaptationLowConfidenceException(String message) {
    super(message);
  }
}
