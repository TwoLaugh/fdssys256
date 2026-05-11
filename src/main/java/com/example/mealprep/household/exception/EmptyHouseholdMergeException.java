package com.example.mealprep.household.exception;

import java.util.UUID;

/**
 * Thrown when {@code HouseholdMergeService.mergeSoftPreferencesForSlot} is invoked on a household
 * with zero members. Reachable: 01d's {@code removeMember} invariant permits the only-member case,
 * preserving the empty household. Mapped to HTTP 422 by {@code HouseholdExceptionHandler}.
 */
public class EmptyHouseholdMergeException extends HouseholdException {

  private final UUID householdId;

  public EmptyHouseholdMergeException(UUID householdId) {
    super("Cannot merge soft preferences — household " + householdId + " has zero members");
    this.householdId = householdId;
  }

  public UUID householdId() {
    return householdId;
  }
}
