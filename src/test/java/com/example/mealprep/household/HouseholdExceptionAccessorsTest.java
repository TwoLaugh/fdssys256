package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.exception.EmptyHouseholdMergeException;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.UserAlreadyInHouseholdException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure accessor tests for the exception classes that carry an id() property. Existing
 * HouseholdExceptionHandlerTest asserts the ProblemDetail content but never reads the {@code
 * userId()} / {@code householdId()} accessor, so the corresponding Pitest NullReturnVals mutants
 * were uncovered. This file pins them.
 */
class HouseholdExceptionAccessorsTest {

  /** kills HouseholdNotFoundException.java:19 NullReturnVals on the userId() getter. */
  @Test
  void householdNotFoundException_userId_returnsConstructorArg() {
    UUID userId = UUID.randomUUID();
    HouseholdNotFoundException ex = new HouseholdNotFoundException(userId);
    assertThat(ex.userId()).isEqualTo(userId);
    // And the message echoes the id (defence-in-depth — existing handler test relies on this).
    assertThat(ex.getMessage()).contains(userId.toString());
  }

  /** kills UserAlreadyInHouseholdException.java:20 NullReturnVals on the userId() getter. */
  @Test
  void userAlreadyInHouseholdException_userId_returnsConstructorArg() {
    UUID userId = UUID.randomUUID();
    UserAlreadyInHouseholdException ex = new UserAlreadyInHouseholdException(userId);
    assertThat(ex.userId()).isEqualTo(userId);
    assertThat(ex.getMessage()).contains(userId.toString());
  }

  /** kills EmptyHouseholdMergeException.java:20 NullReturnVals on the householdId() getter. */
  @Test
  void emptyHouseholdMergeException_householdId_returnsConstructorArg() {
    UUID householdId = UUID.randomUUID();
    EmptyHouseholdMergeException ex = new EmptyHouseholdMergeException(householdId);
    assertThat(ex.householdId()).isEqualTo(householdId);
    assertThat(ex.getMessage()).contains(householdId.toString());
  }
}
