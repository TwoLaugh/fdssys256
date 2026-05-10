package com.example.mealprep.household.exception;

/**
 * Thrown when a caller attempts to perform an operation that requires a particular {@code
 * HouseholdRole} they do not hold (e.g. {@code PUT /settings} requires {@code primary}). Also
 * covers the "not a member of the target household" case so non-members do not get a 404 leak on
 * write paths. Maps to HTTP 403 with type {@code .../insufficient-household-role}.
 */
public class InsufficientHouseholdRoleException extends HouseholdException {

  public InsufficientHouseholdRoleException(String message) {
    super(message);
  }
}
