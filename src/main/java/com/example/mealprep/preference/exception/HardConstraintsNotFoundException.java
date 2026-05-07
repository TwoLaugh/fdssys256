package com.example.mealprep.preference.exception;

import java.util.UUID;

/**
 * Thrown when a hard-constraints lookup or update targets a user with no aggregate row yet. Mapped
 * to HTTP 404 by {@code GlobalExceptionHandler}. The aggregate is initialised at user-creation; a
 * 404 here means {@code initialiseHardConstraints} has not yet been called for this user.
 */
public class HardConstraintsNotFoundException extends PreferenceException {

  private final UUID userId;

  public HardConstraintsNotFoundException(UUID userId) {
    super("Hard constraints not found for user " + userId);
    this.userId = userId;
  }

  public UUID userId() {
    return userId;
  }
}
