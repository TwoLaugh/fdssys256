package com.example.mealprep.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

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

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
    // Never leak stack traces or internal messages. Caller-visible message is generic.
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    pd.setType(URI.create(PROBLEM_BASE + "internal-error"));
    pd.setTitle("Internal error");
    pd.setInstance(URI.create(req.getRequestURI()));
    // The full exception is left for the logging stack to pick up via SLF4J at the
    // controller layer; this advice deliberately does not log here to avoid double-logging.
    return pd;
  }
}
