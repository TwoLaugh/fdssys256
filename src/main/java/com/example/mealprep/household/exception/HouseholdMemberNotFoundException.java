package com.example.mealprep.household.exception;

import java.util.UUID;

/**
 * Thrown by the member-admin endpoints when the target {@code memberId} doesn't exist OR exists in a
 * different household than the caller's. The latter case is collapsed into "not found" to avoid
 * leaking existence of members from other households. Maps to HTTP 404 with type {@code
 * .../household-member-not-found}.
 */
public class HouseholdMemberNotFoundException extends HouseholdException {

  public HouseholdMemberNotFoundException(UUID memberId) {
    super("Household member " + memberId + " not found");
  }

  public HouseholdMemberNotFoundException(String message) {
    super(message);
  }
}
