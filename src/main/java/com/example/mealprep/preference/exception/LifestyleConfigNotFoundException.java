package com.example.mealprep.preference.exception;

import java.util.UUID;

/**
 * Thrown when a lifestyle-config lookup, update, or mark-reviewed targets a user with no aggregate
 * row yet. Mapped to HTTP 404 by {@code PreferenceExceptionHandler}. The aggregate is created by
 * the {@code initialise} flow — typically the first POST to {@code /lifestyle-config} from the
 * onboarding wizard.
 */
public class LifestyleConfigNotFoundException extends PreferenceException {

  private final UUID userId;

  public LifestyleConfigNotFoundException(UUID userId) {
    super("Lifestyle config not found for user " + userId);
    this.userId = userId;
  }

  public UUID userId() {
    return userId;
  }
}
