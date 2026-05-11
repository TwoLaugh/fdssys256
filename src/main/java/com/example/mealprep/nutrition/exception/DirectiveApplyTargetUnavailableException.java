package com.example.mealprep.nutrition.exception;

/**
 * Thrown by {@code NoopDirectiveApplyTarget} when an accept-flow tries to apply a {@code
 * preference_model} directive while no real {@code DirectiveApplyTarget} bean is wired (preference
 * module integration ships in preference-01c). Mapped to HTTP 422 so callers see a clear
 * "preference module not wired" error rather than a silent no-op.
 */
public class DirectiveApplyTargetUnavailableException extends NutritionException {

  public DirectiveApplyTargetUnavailableException(String message) {
    super(message);
  }
}
