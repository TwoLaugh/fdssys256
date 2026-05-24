package com.example.mealprep.e2e.support;

import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-scenario state, shared between step definitions and hooks via Cucumber's PicoContainer DI.
 * Cucumber constructs one instance per scenario and injects the SAME instance into every glue class
 * that declares it as a constructor parameter.
 *
 * <p>Holds the cookie-session-aware {@link ApiClient} (so all steps in a scenario share one
 * session), the self-contained test data this scenario generated (random username + password — the
 * D5 self-contained-data rule), the identity the server minted, and the most recent response for
 * cross-step assertions.
 *
 * <p>The generic {@link #attributes} bag lets domain step-defs stash cross-step values (a created
 * recipe id, its branch id, the next optimistic version, a logged date) without bloating this class
 * with a typed accessor per domain. Keys are namespaced by domain in the step-def constants (e.g.
 * {@code recipe.id}) so two domains never collide.
 */
public class ScenarioContext {

  private final ApiClient apiClient = new ApiClient();
  private final Map<String, Object> attributes = new HashMap<>();

  private String username;
  private String password;
  private String userId;
  private Response lastResponse;

  public ApiClient api() {
    return apiClient;
  }

  /** Stash a cross-step value under a (domain-namespaced) key. */
  public void put(String key, Object value) {
    attributes.put(key, value);
  }

  /** Retrieve a previously stashed value, or {@code null} if absent. */
  @SuppressWarnings("unchecked")
  public <T> T get(String key) {
    return (T) attributes.get(key);
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
