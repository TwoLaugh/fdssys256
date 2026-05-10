package com.example.mealprep.household.exception;

/**
 * Thrown by the accept flow when the looked-up invite has {@code revokedAt != null}. Maps to HTTP
 * 410 (Gone) with type {@code .../household-invite-revoked}.
 */
public class HouseholdInviteRevokedException extends HouseholdException {

  public HouseholdInviteRevokedException(String message) {
    super(message);
  }
}
