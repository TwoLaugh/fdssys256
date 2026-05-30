package com.example.mealprep.auth.api;

import com.example.mealprep.auth.domain.service.internal.PasswordStrengthValidator.Reason;
import com.example.mealprep.auth.exception.AccountLockedException;
import com.example.mealprep.auth.exception.InvalidCredentialsException;
import com.example.mealprep.auth.exception.LoginThrottledException;
import com.example.mealprep.auth.exception.UsernameAlreadyExistsException;
import com.example.mealprep.auth.exception.WeakPasswordException;
import com.example.mealprep.config.ProblemDetailSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Auth-specific exception → {@link ProblemDetail} mapper. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

  /**
   * Used for {@code Retry-After} computation on 423 responses. Defaults to system UTC; tests that
   * need a deterministic clock substitute via {@link #withClock(Clock)}.
   */
  private Clock clock = Clock.systemUTC();

  public AuthExceptionHandler withClock(Clock clock) {
    this.clock = clock;
    return this;
  }

  @ExceptionHandler(UsernameAlreadyExistsException.class)
  public ResponseEntity<ProblemDetail> handleUsernameAlreadyExists(
      UsernameAlreadyExistsException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "username-taken",
            "Username already taken",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ProblemDetail> handleInvalidCredentials(
      InvalidCredentialsException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNAUTHORIZED,
            "Invalid credentials",
            "invalid-credentials",
            "Invalid credentials",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(AccountLockedException.class)
  public ResponseEntity<ProblemDetail> handleAccountLocked(
      AccountLockedException ex, HttpServletRequest req) {
    long retryAfterSeconds = ProblemDetailSupport.retryAfterSeconds(clock, ex.lockedUntil());
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.LOCKED,
            "Account locked",
            "account-locked",
            "Account locked",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.LOCKED)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Service-side weak-password rejection. Maps to {@code 400} with a machine-readable {@code
   * reasons[]} extension (e.g. {@code MATCHES_USERNAME}, {@code BREACHED}) and a fixed generic
   * detail — the block-list contents are never echoed back.
   */
  @ExceptionHandler(WeakPasswordException.class)
  public ResponseEntity<ProblemDetail> handleWeakPassword(
      WeakPasswordException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "weak-password",
            "Weak password",
            req.getRequestURI());
    List<String> reasonCodes = ex.reasons().stream().map(Reason::name).toList();
    pd.setProperty("reasons", reasonCodes);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(LoginThrottledException.class)
  public ResponseEntity<ProblemDetail> handleLoginThrottled(
      LoginThrottledException ex, HttpServletRequest req) {
    long retryAfterSeconds = ProblemDetailSupport.clampToWholeSeconds(ex.retryAfter());
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.TOO_MANY_REQUESTS,
            "Login throttled",
            "login-throttled",
            "Login throttled",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
