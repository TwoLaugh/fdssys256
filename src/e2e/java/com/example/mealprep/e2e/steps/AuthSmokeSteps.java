package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import java.util.Map;
import java.util.UUID;

/**
 * Step definitions for {@code auth_smoke.feature} — the toolchain-proving smoke slice.
 *
 * <p>Covers AUTH-01 (register), AUTH-05 (login), and an authenticated read of own identity (AUTH-09
 * / AUTH-15) against the running prod-parity stack. The only layer that knows HTTP/JSON lives here
 * + in {@link com.example.mealprep.e2e.support.ApiClient} (decision D2).
 *
 * <p><b>Self-contained data</b> (D5): each scenario mints its OWN random username, so the suite is
 * safe in both clean and soak mode and never assumes an empty database. <b>Self-scoped
 * assertions</b> (D5): we assert on THIS scenario's identity (the username we sent, the user id the
 * server minted), never on global user counts.
 */
public class AuthSmokeSteps {

  /**
   * A strong, non-breached password that is not equal to any generated username. The auth policy
   * (see {@code PasswordStrengthValidator}) requires >= 12 chars, no leading/trailing whitespace,
   * not on the breached list, and not equal to the username.
   */
  private static final String VALID_PASSWORD = "E2e-Sm0ke-Pass-7531";

  private final ScenarioContext context;

  public AuthSmokeSteps(ScenarioContext context) {
    this.context = context;
  }

  @Given("a fresh anonymous visitor with a random username")
  public void aFreshAnonymousVisitorWithARandomUsername() {
    // Random handle within ^[a-zA-Z0-9_-]{3,32}$ (see ValidUsernameValidator). "e2e-" prefix +
    // 12 hex chars = 16 chars, well within bounds, and collision-free across parallel/soak runs.
    String username = "e2e-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    context.setUsername(username);
    context.setPassword(VALID_PASSWORD);
  }

  @When("they register with that username and a valid password")
  public void theyRegisterWithThatUsernameAndAValidPassword() {
    Response response =
        context
            .api()
            .post(
                "/api/v1/auth/register",
                Map.of("username", context.username(), "password", context.password()));
    context.setLastResponse(response);
  }

  @Then("registration succeeds and returns their new account identity")
  public void registrationSucceedsAndReturnsTheirNewAccountIdentity() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("register should return 201 Created").isEqualTo(201);
    // Self-scoped: the response describes THIS account, not a global state.
    assertThat(response.jsonPath().getString("username")).isEqualTo(context.username());
    String userId = response.jsonPath().getString("userId");
    assertThat(userId).as("register must mint a canonical user id").isNotBlank();
    context.setUserId(userId);
  }

  @When("they log in with the same credentials")
  public void theyLogInWithTheSameCredentials() {
    Response response =
        context
            .api()
            .post(
                "/api/v1/auth/login",
                Map.of("username", context.username(), "password", context.password()));
    context.setLastResponse(response);
  }

  @Then("login succeeds and a session is established")
  public void loginSucceedsAndASessionIsEstablished() {
    Response response = context.lastResponse();
    assertThat(response.statusCode()).as("login should return 200 OK").isEqualTo(200);
    assertThat(response.jsonPath().getString("username")).isEqualTo(context.username());
    // The session credential rides on Set-Cookie (AUTH_SESSION); the ApiClient's cookie jar
    // captures it for the subsequent authenticated read. We assert the cookie was issued rather
    // than its internal shape (per auth.md AUTH-05: "assert session established", not the shape).
    assertThat(response.getDetailedCookie("AUTH_SESSION"))
        .as("login must issue the AUTH_SESSION cookie")
        .isNotNull();
  }

  @When("they request their own account while authenticated")
  public void theyRequestTheirOwnAccountWhileAuthenticated() {
    // No explicit cookie handling: the ApiClient's shared CookieFilter replays the session cookie.
    Response response = context.api().get("/api/v1/auth/me");
    context.setLastResponse(response);
  }

  @Then("the account read succeeds and shows the same username and user id")
  public void theAccountReadSucceedsAndShowsTheSameUsernameAndUserId() {
    Response response = context.lastResponse();
    assertThat(response.statusCode())
        .as("authenticated GET /me should return 200 OK")
        .isEqualTo(200);
    assertThat(response.jsonPath().getString("username")).isEqualTo(context.username());
    assertThat(response.jsonPath().getString("userId")).isEqualTo(context.userId());
  }
}
