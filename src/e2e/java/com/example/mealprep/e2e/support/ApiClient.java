package com.example.mealprep.e2e.support;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.cookie.CookieFilter;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Thin, cookie-session-aware REST-assured wrapper — the ONLY layer that knows HTTP/JSON (decision
 * D2). One instance per scenario (held in {@link ScenarioContext}).
 *
 * <p>Auth in this system is a cookie session ({@code AUTH_SESSION}, set via {@code Set-Cookie} on
 * register/login — see {@code AuthController}). A single {@link CookieFilter} instance is attached
 * to every request from this client, so the cookie the server issues on login is automatically
 * captured and replayed on later requests — exactly how a browser frontend behaves. A fresh client
 * (new CookieFilter) therefore models a fresh, unauthenticated browser.
 */
public final class ApiClient {

  private final RequestSpecification spec;

  public ApiClient() {
    // One cookie jar shared across this client's requests => session persists like a browser.
    CookieFilter cookieFilter = new CookieFilter();
    this.spec =
        new RequestSpecBuilder()
            .setBaseUri(E2eConfig.baseUrl())
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .addFilter(cookieFilter)
            .build();
  }

  /** A request bound to this client's session (cookie jar + base URI + JSON content type). */
  public RequestSpecification request() {
    return RestAssured.given().spec(spec);
  }

  public Response post(String path, Object body) {
    return request().body(body).when().post(path);
  }

  public Response get(String path) {
    return request().when().get(path);
  }
}
