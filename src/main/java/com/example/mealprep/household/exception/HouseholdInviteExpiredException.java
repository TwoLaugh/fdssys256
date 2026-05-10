package com.example.mealprep.household.exception;

/**
 * Thrown by the accept flow when {@code expiresAt} is in the past. Maps to HTTP 410 (Gone) with
 * type {@code .../household-invite-expired}.
 */
public class HouseholdInviteExpiredException extends HouseholdException {

  public HouseholdInviteExpiredException(String message) {
    super(message);
  }
}
