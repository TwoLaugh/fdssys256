package com.example.mealprep.nutrition.exception;

import java.util.UUID;

/**
 * Thrown when a journal-entry write or read targets an id that does not exist, is owned by a
 * different user, or whose {@code onDate} disagrees with the URL path. Mapped to HTTP 404 by {@code
 * NutritionExceptionHandler} — ownership / path-date mismatches are deliberately surfaced as 404
 * (not 403) so the API does not leak the existence of other users' entries.
 */
public class JournalEntryNotFoundException extends NutritionException {

  private final UUID entryId;

  public JournalEntryNotFoundException() {
    super("Journal entry not found");
    this.entryId = null;
  }

  public JournalEntryNotFoundException(UUID entryId) {
    super("Journal entry not found: " + entryId);
    this.entryId = entryId;
  }

  public UUID entryId() {
    return entryId;
  }
}
