package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.household.api.HouseholdExceptionHandler;
import com.example.mealprep.household.exception.EmptyHouseholdMergeException;
import com.example.mealprep.household.exception.HouseholdInviteAlreadyAcceptedException;
import com.example.mealprep.household.exception.HouseholdInviteExpiredException;
import com.example.mealprep.household.exception.HouseholdInviteNotFoundException;
import com.example.mealprep.household.exception.HouseholdInviteRevokedException;
import com.example.mealprep.household.exception.HouseholdMemberNotFoundException;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.HouseholdSettingsNotFoundException;
import com.example.mealprep.household.exception.InsufficientHouseholdRoleException;
import com.example.mealprep.household.exception.LastPrimaryRemovalException;
import com.example.mealprep.household.exception.UserAlreadyInHouseholdException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link HouseholdExceptionHandler}. Each handler is invoked directly with its
 * exception + a mock request; assertions pin the HTTP status, the {@code application/problem+json}
 * content type, and the ProblemDetail detail / title / type-slug / instance. Kills the per-handler
 * status-constant, string-literal type-slug/title and detail mutants (all previously uncovered —
 * the advice was ~1% covered). Pure — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdExceptionHandlerTest {

  private static final String URI = "/api/v1/households/whatever";

  @Mock private HttpServletRequest request;
  private final HouseholdExceptionHandler handler = new HouseholdExceptionHandler();

  @BeforeEach
  void stubUri() {
    when(request.getRequestURI()).thenReturn(URI);
  }

  private void assertProblem(
      ResponseEntity<ProblemDetail> resp,
      HttpStatus status,
      String typeSlug,
      String title,
      String detailContains) {
    assertThat(resp.getStatusCode()).isEqualTo(status);
    assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail pd = resp.getBody();
    assertThat(pd).isNotNull();
    assertThat(pd.getStatus()).isEqualTo(status.value());
    assertThat(pd.getTitle()).isEqualTo(title);
    assertThat(pd.getType().toString())
        .isEqualTo("https://mealprep.example.com/problems/" + typeSlug);
    assertThat(pd.getInstance().toString()).isEqualTo(URI);
    assertThat(pd.getDetail()).contains(detailContains);
  }

  @Test
  void householdNotFound_maps_to_404() {
    UUID userId = UUID.randomUUID();
    var resp = handler.handleHouseholdNotFound(new HouseholdNotFoundException(userId), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "household-not-found",
        "Household not found",
        userId.toString());
  }

  @Test
  void userAlreadyInHousehold_maps_to_409() {
    UUID userId = UUID.randomUUID();
    var resp =
        handler.handleUserAlreadyInHousehold(new UserAlreadyInHouseholdException(userId), request);
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "user-already-in-household",
        "User already in household",
        userId.toString());
  }

  @Test
  void householdSettingsNotFound_maps_to_404() {
    UUID householdId = UUID.randomUUID();
    var resp =
        handler.handleHouseholdSettingsNotFound(
            new HouseholdSettingsNotFoundException(householdId), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "household-settings-not-found",
        "Household settings not found",
        householdId.toString());
  }

  @Test
  void insufficientHouseholdRole_maps_to_403() {
    var resp =
        handler.handleInsufficientHouseholdRole(
            new InsufficientHouseholdRoleException("requires PRIMARY"), request);
    assertProblem(
        resp,
        HttpStatus.FORBIDDEN,
        "insufficient-household-role",
        "Insufficient household role",
        "requires PRIMARY");
  }

  @Test
  void householdInviteNotFound_maps_to_404() {
    var resp =
        handler.handleHouseholdInviteNotFound(
            new HouseholdInviteNotFoundException("no invite ABC123"), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "household-invite-not-found",
        "Household invite not found",
        "no invite ABC123");
  }

  @Test
  void householdInviteExpired_maps_to_410() {
    var resp =
        handler.handleHouseholdInviteExpired(
            new HouseholdInviteExpiredException("invite expired 2026-01-01"), request);
    assertProblem(
        resp,
        HttpStatus.GONE,
        "household-invite-expired",
        "Household invite expired",
        "invite expired 2026-01-01");
  }

  @Test
  void householdInviteRevoked_maps_to_410() {
    var resp =
        handler.handleHouseholdInviteRevoked(
            new HouseholdInviteRevokedException("invite revoked"), request);
    assertProblem(
        resp,
        HttpStatus.GONE,
        "household-invite-revoked",
        "Household invite revoked",
        "invite revoked");
  }

  @Test
  void householdInviteAlreadyAccepted_maps_to_409() {
    var resp =
        handler.handleHouseholdInviteAlreadyAccepted(
            new HouseholdInviteAlreadyAcceptedException("already accepted"), request);
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "household-invite-already-accepted",
        "Household invite already accepted or revoked",
        "already accepted");
  }

  @Test
  void householdMemberNotFound_maps_to_404() {
    UUID memberId = UUID.randomUUID();
    var resp =
        handler.handleHouseholdMemberNotFound(
            new HouseholdMemberNotFoundException(memberId), request);
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "household-member-not-found",
        "Household member not found",
        memberId.toString());
  }

  @Test
  void lastPrimaryRemoval_maps_to_409() {
    var resp =
        handler.handleLastPrimaryRemoval(
            new LastPrimaryRemovalException("cannot demote last primary"), request);
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "last-primary-removal",
        "Cannot remove or demote the last primary while other members remain",
        "cannot demote last primary");
  }

  @Test
  void emptyHouseholdMerge_maps_to_422() {
    UUID householdId = UUID.randomUUID();
    var resp =
        handler.handleEmptyHouseholdMerge(new EmptyHouseholdMergeException(householdId), request);
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "empty-household-merge",
        "Empty household merge",
        householdId.toString());
  }

  @Test
  void dataIntegrityViolation_maps_to_409_with_fixed_detail() {
    var resp =
        handler.handleDataIntegrityViolation(
            new DataIntegrityViolationException("duplicate key idx_household_member_one_primary"),
            request);
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "household-integrity-violation",
        "Household integrity violation",
        "Household integrity constraint violated.");
  }
}
