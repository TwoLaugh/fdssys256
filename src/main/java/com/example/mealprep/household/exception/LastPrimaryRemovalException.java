package com.example.mealprep.household.exception;

/**
 * Thrown when removing or demoting the only remaining {@code PRIMARY} member of a household while
 * other (non-primary) members remain. Callers must promote another member to {@code PRIMARY} first.
 * Maps to HTTP 409 with type {@code .../last-primary-removal}.
 */
public class LastPrimaryRemovalException extends HouseholdException {

  public LastPrimaryRemovalException(String message) {
    super(message);
  }
}
