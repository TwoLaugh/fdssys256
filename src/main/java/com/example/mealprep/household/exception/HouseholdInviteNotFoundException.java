package com.example.mealprep.household.exception;

/**
 * Thrown when an invite lookup (by id on revoke, by code on accept) yields no row. Also surfaced
 * when an invite exists but belongs to another household than the caller's — to avoid leaking
 * existence. Maps to HTTP 404 with type {@code .../household-invite-not-found}.
 */
public class HouseholdInviteNotFoundException extends HouseholdException {

  public HouseholdInviteNotFoundException(String message) {
    super(message);
  }
}
