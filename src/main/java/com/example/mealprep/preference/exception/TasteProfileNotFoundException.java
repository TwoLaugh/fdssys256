package com.example.mealprep.preference.exception;

import java.util.UUID;

/**
 * Thrown when a taste-profile lookup targets a user with no aggregate row yet. Mapped to HTTP 404
 * by {@code PreferenceExceptionHandler}. The aggregate is initialised lazily; a 404 here means
 * {@code TasteProfileUpdateService.initialise} has not yet been called for this user.
 */
public class TasteProfileNotFoundException extends PreferenceException {

  private final UUID userId;

  public TasteProfileNotFoundException(UUID userId) {
    super("Taste profile not found for user " + userId);
    this.userId = userId;
  }

  public UUID userId() {
    return userId;
  }
}
