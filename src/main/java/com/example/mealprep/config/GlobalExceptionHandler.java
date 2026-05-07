package com.example.mealprep.config;

import com.example.mealprep.auth.exception.InvalidCredentialsException;
import com.example.mealprep.auth.exception.UsernameAlreadyExistsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Top-level exception → {@link ProblemDetail} mapper for the project. RFC 9457 shape per {@code
 * lld/style-guide.md §Error responses}.
 *
 * <p>Module-specific exceptions get their own {@code @ExceptionHandler} methods on this class as
 * they're introduced. Unmatched exceptions become a generic 500 with no stack-trace leak.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final String PROBLEM_BASE = "https://mealprep.example.com/problems/";

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    pd.setType(URI.create(PROBLEM_BASE + "validation-error"));
    pd.setTitle("Validation failed");
    pd.setInstance(URI.create(req.getRequestURI()));
    return pd;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    pd.setType(URI.create(PROBLEM_BASE + "validation-error"));
    pd.setTitle("Validation failed");
    pd.setInstance(URI.create(req.getRequestURI()));
    return pd;
  }

  /**
   * {@link MethodArgumentNotValidException} — bean-validation failures on {@code @Valid} request
   * bodies. Returns 400 with the per-field errors so the client can localise the message.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
    pd.setType(URI.create(PROBLEM_BASE + "validation-error"));
    pd.setTitle("Validation failed");
    pd.setInstance(URI.create(req.getRequestURI()));
    List<FieldError> fieldErrors = new ArrayList<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(fe -> fieldErrors.add(new FieldError(fe.getField(), fe.getDefaultMessage())));
    pd.setProperty("errors", fieldErrors);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /** 409 — duplicate username on registration. */
  @ExceptionHandler(UsernameAlreadyExistsException.class)
  public ResponseEntity<ProblemDetail> handleUsernameAlreadyExists(
      UsernameAlreadyExistsException ex, HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    pd.setType(URI.create(PROBLEM_BASE + "username-taken"));
    pd.setTitle("Username already taken");
    pd.setInstance(URI.create(req.getRequestURI()));
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * 401 — invalid credentials, generic by design. Caller cannot distinguish unknown-user from
   * wrong-password; that is the point.
   */
  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<ProblemDetail> handleInvalidCredentials(
      InvalidCredentialsException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    pd.setType(URI.create(PROBLEM_BASE + "invalid-credentials"));
    pd.setTitle("Invalid credentials");
    pd.setInstance(URI.create(req.getRequestURI()));
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Pass {@link ResponseStatusException} through with its declared status + reason. Without this
   * handler, the generic {@link #handleUnexpected} catch-all below would mask all controller-thrown
   * 404s/409s/etc into a 500.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest req) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getReason());
    pd.setTitle(status.getReasonPhrase());
    pd.setInstance(URI.create(req.getRequestURI()));
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
  }

  /**
   * 404 — Spring's static-resource resolver throws this when no handler matches a path. Without a
   * specific handler, the {@link #handleUnexpected} catch-all below would mask it as 500.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoResourceFound(
      NoResourceFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND, "No handler for " + req.getRequestURI());
    pd.setType(URI.create(PROBLEM_BASE + "not-found"));
    pd.setTitle("Not found");
    pd.setInstance(URI.create(req.getRequestURI()));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
    // Never leak stack traces or internal messages. Caller-visible message is generic.
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    pd.setType(URI.create(PROBLEM_BASE + "internal-error"));
    pd.setTitle("Internal error");
    pd.setInstance(URI.create(req.getRequestURI()));
    return pd;
  }

  /** Per-field error shape attached as {@code errors[]} on validation ProblemDetails. */
  public record FieldError(String field, String message) {}
}
