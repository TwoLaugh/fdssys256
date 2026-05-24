package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ApiClient;
import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.util.Map;

/**
 * Auth step definitions for the wave-1 error/lifecycle pathways NOT already covered by the smoke
 * slice ({@link AuthSmokeSteps}): logout (AUTH-10), duplicate-username conflict (AUTH-04),
 * wrong-password (AUTH-06) and unknown-username (AUTH-07) login rejection.
 *
 * <p>The shared "fresh anonymous visitor" / register / login / authenticated-read steps live in
 * {@link AuthSmokeSteps} and are REUSED verbatim by {@code auth.feature}; this class only adds the
 * steps that smoke did not need. Self-contained (each scenario mints its own handle) and
 * self-scoped (assert on THIS scenario's identity).
 */
public class AuthSteps {

  private final ScenarioContext context;

  public AuthSteps(ScenarioContext context) {
    this.context = context;
  }

  @When("they log out")
  public void theyLogOut() {
    Response response = context.api().request().when().post("/api/v1/auth/logout");
    context.setLastResponse(response);
  }

  @Then("the logout succeeds")
  public void theLogoutSucceeds() {
    assertThat(context.lastResponse().statusCode())
        .as("logout should return 204 No Content")
        .isEqualTo(204);
  }

  @When("a second visitor attempts to register with the same username")
  public void aSecondVisitorAttemptsToRegisterWithTheSameUsername() {
    // A FRESH client (new cookie jar) models a different, unauthenticated browser racing the same
    // handle; the duplicate must still be rejected regardless of who is calling.
    ApiClient secondVisitor = new ApiClient();
    Response response =
        secondVisitor.post(
            "/api/v1/auth/register",
            Map.of("username", context.username(), "password", CommonSteps.VALID_PASSWORD));
    context.setLastResponse(response);
  }

  @Then("the registration is rejected as a username conflict")
  public void theRegistrationIsRejectedAsAUsernameConflict() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("a duplicate username must be rejected with 409 Conflict")
        .isEqualTo(409);
  }

  @When("they attempt to log in with the wrong password")
  public void theyAttemptToLogInWithTheWrongPassword() {
    // A fresh client so a failed login cannot ride on any session the register step established.
    Response response =
        new ApiClient()
            .post(
                "/api/v1/auth/login",
                Map.of("username", context.username(), "password", "Wr0ng-Pass-Definitely-9999"));
    context.setLastResponse(response);
  }

  @When("they attempt to log in with an unknown username")
  public void theyAttemptToLogInWithAnUnknownUsername() {
    Response response =
        new ApiClient()
            .post(
                "/api/v1/auth/login",
                Map.of(
                    "username",
                    "e2e-nobody-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                    "password",
                    CommonSteps.VALID_PASSWORD));
    context.setLastResponse(response);
  }

  @Then("the login is rejected as invalid credentials")
  public void theLoginIsRejectedAsInvalidCredentials() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("a bad login must be rejected with 401 Unauthorized (no session issued)")
        .isEqualTo(401);
    assertThat(response.getDetailedCookie("AUTH_SESSION"))
        .as("a rejected login must NOT issue a session cookie")
        .isNull();
  }
}
