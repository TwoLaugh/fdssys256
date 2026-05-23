package com.example.mealprep.preference.exception;

/**
 * Thrown by {@code PreferenceDirectiveApplyTarget} when a directive routed to {@code
 * preference_model} carries an {@code action} that does not map to a hard-constraint mutation.
 *
 * <p>The {@code preference_model} route only handles ingredient-restriction-shaped directives (LLD
 * lines 1009, 1016); a {@code target_adjustment} should route {@code nutrition_model}, so an
 * unmapped action here is a routing bug worth surfacing loudly rather than silently no-op'ing.
 * Mapped to HTTP 422 by {@code PreferenceExceptionHandler}; the in-process throw propagates up
 * through the nutrition {@code DirectiveApplier} to the directive-accept endpoint, which returns a
 * clean 422 (never a 500).
 */
public class InvalidDirectivePreferenceRouteException extends PreferenceException {

  public InvalidDirectivePreferenceRouteException(String message) {
    super(message);
  }
}
