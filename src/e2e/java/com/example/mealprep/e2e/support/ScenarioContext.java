package com.example.mealprep.e2e.support;

import io.restassured.response.Response;

/**
 * Per-scenario state, shared between step definitions and hooks via Cucumber's PicoContainer DI.
 * Cucumber constructs one instance per scenario and injects the SAME instance into every glue class
 * that declares it as a constructor parameter.
 *
 * <p>Holds the cookie-session-aware {@link ApiClient} (so all steps in a scenario share one
 * session), the self-contained test data this scenario generated (random username + password — the
 * D5 self-contained-data rule), the identity the server minted, and the most recent response for
 * cross-step assertions.
 */
public class ScenarioContext {

  private final ApiClient apiClient = new ApiClient();

  private String username;
  private String password;
  private String userId;
  private Response lastResponse;

  public ApiClient api() {
    return apiClient;
  }

  public String username() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String password() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String userId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public Response lastResponse() {
    return lastResponse;
  }

  public void setLastResponse(Response lastResponse) {
    this.lastResponse = lastResponse;
  }
}
