package com.example.mealprep.preference.exception;

import java.util.UUID;

/**
 * Thrown when a rollback targets a {@code document_version} that has no snapshot row in {@code
 * preference_taste_profile_versions} for the user's profile. Mapped to HTTP 404 by {@code
 * PreferenceExceptionHandler}. Distinct from {@link TasteProfileNotFoundException} (which means the
 * profile aggregate itself is absent) so callers can tell "no profile" apart from "profile exists,
 * but that version was never recorded".
 */
public class TasteProfileVersionNotFoundException extends PreferenceException {

  private final UUID userId;
  private final int documentVersion;

  public TasteProfileVersionNotFoundException(UUID userId, int documentVersion) {
    super("Taste profile version " + documentVersion + " not found for user " + userId);
    this.userId = userId;
    this.documentVersion = documentVersion;
  }

  public UUID userId() {
    return userId;
  }

  public int documentVersion() {
    return documentVersion;
  }
}
