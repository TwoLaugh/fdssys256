package com.example.mealprep.household.exception;

import java.util.UUID;

/**
 * Thrown when {@code GET /api/v1/households/current} is called for a user with no {@code
 * household_member} row. Mapped to HTTP 404 by {@code HouseholdExceptionHandler}.
 */
public class HouseholdNotFoundException extends HouseholdException {

  private final UUID userId;

  public HouseholdNotFoundException(UUID userId) {
    super("No household found for user " + userId);
    this.userId = userId;
  }

  public UUID userId() {
    return userId;
  }
}
