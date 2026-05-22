package com.example.mealprep.preference.exception;

import java.util.UUID;

/**
 * Thrown by {@code PreferenceArchiveUpdateService.markRePromoted} when no currently-archived (not
 * yet re-promoted) entry exists for {@code (userId, fieldPath, itemKey)}. Mapped to HTTP 404 by
 * {@code PreferenceExceptionHandler}.
 */
public class PreferenceArchiveEntryNotFoundException extends PreferenceException {

  private final UUID userId;
  private final String fieldPath;
  private final String itemKey;

  public PreferenceArchiveEntryNotFoundException(UUID userId, String fieldPath, String itemKey) {
    super(
        "No archived entry found for user "
            + userId
            + " at fieldPath '"
            + fieldPath
            + "' with itemKey '"
            + itemKey
            + "'");
    this.userId = userId;
    this.fieldPath = fieldPath;
    this.itemKey = itemKey;
  }

  public UUID userId() {
    return userId;
  }

  public String fieldPath() {
    return fieldPath;
  }

  public String itemKey() {
    return itemKey;
  }
}
