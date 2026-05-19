package com.example.mealprep.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.api.AuthExceptionHandler;
import com.example.mealprep.auth.exception.AccountLockedException;
import com.example.mealprep.auth.exception.InvalidCredentialsException;
import com.example.mealprep.auth.exception.LoginThrottledException;
import com.example.mealprep.auth.exception.UsernameAlreadyExistsException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for {@link AuthExceptionHandler}. Invokes each handler directly with its exception + a
 * mock request, pinning status / content-type / ProblemDetail fields. The 423 and 429 cases also
 * assert the {@code Retry-After} header computed against a fixed {@link Clock} (substituted via
 * {@link AuthExceptionHandler#withClock}) — this kills the retry-after-seconds arithmetic and the
 * header-value/name mutants. Pure — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthExceptionHandlerTest {

  private static final String URI = "/api/v1/auth/login";
  private static final Instant NOW = Instant.parse("2026-05-19T12:00:00Z");

  @Mock private HttpServletRequest request;
  private final AuthExceptionHandler handler =
      new AuthExceptionHandler().withClock(Clock.fixed(NOW, ZoneOffset.UTC));

  private void stubUri() {
    when(request.getRequestURI()).thenReturn(URI);
  }

  private void assertProblem(
      ResponseEntity<ProblemDetail> resp,
      HttpStatus status,
      String typeSlug,
      String title,
      String detail) {
    assertThat(resp.getStatusCode()).isEqualTo(status);
    assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail pd = resp.getBody();
    assertThat(pd).isNotNull();
    assertThat(pd.getStatus()).isEqualTo(status.value());
    assertThat(pd.getTitle()).isEqualTo(title);
    assertThat(pd.getType().toString())
        .isEqualTo("https://mealprep.example.com/problems/" + typeSlug);
    assertThat(pd.getInstance().toString()).isEqualTo(URI);
    assertThat(pd.getDetail()).isEqualTo(detail);
  }

  @Test
  void usernameAlreadyExists_maps_to_409_with_exception_message() {
    stubUri();
    var resp =
        handler.handleUsernameAlreadyExists(
            new UsernameAlreadyExistsException("username 'bob' is taken"), request);
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "username-taken",
        "Username already taken",
        "username 'bob' is taken");
  }

  @Test
  void invalidCredentials_maps_to_401_with_generic_detail_not_leaking_message() {
    stubUri();
    var resp =
        handler.handleInvalidCredentials(
            new InvalidCredentialsException("user 42 wrong password"), request);
    // Detail must be the fixed generic string, NOT the exception message (info-leak guard).
    assertProblem(
        resp,
        HttpStatus.UNAUTHORIZED,
        "invalid-credentials",
        "Invalid credentials",
        "Invalid credentials");
  }

  @Test
  void accountLocked_maps_to_423_with_retry_after_ceiling_from_clock() {
    stubUri();
    // 90.4s into the future -> ceiling to 91 whole seconds.
    Instant lockedUntil = NOW.plusMillis(90_400);
    var resp = handler.handleAccountLocked(new AccountLockedException(lockedUntil), request);
    assertProblem(resp, HttpStatus.LOCKED, "account-locked", "Account locked", "Account locked");
    assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("91");
  }

  @Test
  void accountLocked_in_the_past_floors_retry_after_to_one_second() {
    stubUri();
    Instant lockedUntil = NOW.minusSeconds(30);
    var resp = handler.handleAccountLocked(new AccountLockedException(lockedUntil), request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  @Test
  void loginThrottled_maps_to_429_with_retry_after_ceiling() {
    stubUri();
    var resp =
        handler.handleLoginThrottled(new LoginThrottledException(Duration.ofMillis(1500)), request);
    assertProblem(
        resp,
        HttpStatus.TOO_MANY_REQUESTS,
        "login-throttled",
        "Login throttled",
        "Login throttled");
    assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
  }

  @Test
  void loginThrottled_with_zero_duration_floors_to_one_second() {
    stubUri();
    var resp = handler.handleLoginThrottled(new LoginThrottledException(Duration.ZERO), request);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }

  @Test
  void withClock_returns_same_handler_instance_for_fluent_chaining() {
    AuthExceptionHandler h = new AuthExceptionHandler();
    assertThat(h.withClock(Clock.systemUTC())).isSameAs(h);
  }
}
