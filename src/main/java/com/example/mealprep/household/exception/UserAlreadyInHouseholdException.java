package com.example.mealprep.household.exception;

import java.util.UUID;

/**
 * Thrown by {@code createHousehold} when the calling user is already a member of a household. Maps
 * to HTTP 409 (the {@code UNIQUE (user_id)} DB constraint on {@code household_member} backs this at
 * a layer below the service).
 */
public class UserAlreadyInHouseholdException extends HouseholdException {

  private final UUID userId;

  public UserAlreadyInHouseholdException(UUID userId) {
    super("User " + userId + " is already a member of a household");
    this.userId = userId;
  }

  public UUID userId() {
    return userId;
  }
}
