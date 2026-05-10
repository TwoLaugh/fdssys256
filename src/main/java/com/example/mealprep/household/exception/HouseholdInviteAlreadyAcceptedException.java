package com.example.mealprep.household.exception;

/**
 * Thrown when the accept flow encounters an invite with {@code acceptedAt != null}, or when the
 * revoke flow encounters one. Maps to HTTP 409 (Conflict) with type {@code
 * .../household-invite-already-accepted}.
 */
public class HouseholdInviteAlreadyAcceptedException extends HouseholdException {

  public HouseholdInviteAlreadyAcceptedException(String message) {
    super(message);
  }
}
