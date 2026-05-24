package com.example.mealprep.e2e.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.e2e.support.ScenarioContext;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.restassured.response.Response;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-domain step definitions reused by every wave-1 feature: the canonical "fresh
 * registered-and-logged-in user" precondition (the self-contained-data spine from auth.md AUTH-22 /
 * cross-journeys.md that every other domain folds into its "Authenticated" precondition), and
 * generic outcome assertions (unauthenticated rejection, validation error) that are identical
 * across domains.
 *
 * <p>Self-contained data (D5): the {@code Given} mints its OWN random username + a strong password
 * and registers — which, per {@code AuthController}, also establishes the cookie session
 * (auto-login on register). The {@link com.example.mealprep.e2e.support.ApiClient}'s shared cookie
 * jar then replays the session on every subsequent request in the scenario.
 */
public class CommonSteps {

  /**
   * A strong, non-breached password (>= 12 chars, no leading/trailing whitespace, not on the
   * breached list, not equal to the username) — the {@code @ValidPassword} policy. Shared by the
   * common user-setup step.
   */
  static final String VALID_PASSWORD = "E2e-Comm0n-Pass-7531";

  private final ScenarioContext context;

  public CommonSteps(ScenarioContext context) {
    this.context = context;
  }

  /** Mint a collision-free random handle within {@code ^[a-zA-Z0-9_-]{3,32}$}. */
  static String randomUsername() {
    return "e2e-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  @Given("a fresh registered and logged-in user")
  public void aFreshRegisteredAndLoggedInUser() {
    String username = randomUsername();
    context.setUsername(username);
    context.setPassword(VALID_PASSWORD);

    Response response =
        context
            .api()
            .post(
                "/api/v1/auth/register", Map.of("username", username, "password", VALID_PASSWORD));
    context.setLastResponse(response);
    assertThat(response.statusCode())
        .as("fresh-user setup: register should return 201 Created")
        .isEqualTo(201);
    String userId = response.jsonPath().getString("userId");
    assertThat(userId).as("register must mint a canonical user id").isNotBlank();
    context.setUserId(userId);
    // Register auto-logs-in (AuthController sets the AUTH_SESSION cookie on register), so the
    // ApiClient's cookie jar now carries the session for every subsequent authenticated call.
    assertThat(response.getDetailedCookie("AUTH_SESSION"))
        .as("register must establish the session cookie (auto-login)")
        .isNotNull();
  }

  @Then("the request is rejected as unauthenticated")
  public void theRequestIsRejectedAsUnauthenticated() {
    assertThat(context.lastResponse().statusCode())
        .as("a protected action with no valid session must be 401 Unauthorized")
        .isEqualTo(401);
  }

  @Then("the recipe creation is rejected as a validation error")
  public void theRecipeCreationIsRejectedAsAValidationError() {
    assertValidationError();
  }

  @Then("the targets update is rejected as a validation error")
  public void theTargetsUpdateIsRejectedAsAValidationError() {
    assertValidationError();
  }

  /**
   * Bean-validation failures (missing/invalid fields) surface as 400 with a {@code
   * type=.../validation-error} ProblemDetail (see {@code GlobalExceptionHandler}). Asserting the
   * status is the self-scoped, domain-agnostic outcome; the precise field-level rule set is an HLD
   * gap for several domains (recipe G1, auth AG1, nutrition N2).
   */
  private void assertValidationError() {
    assertThat(context.lastResponse().statusCode())
        .as("an invalid request body must be rejected with 400 Bad Request")
        .isEqualTo(400);
  }
}
