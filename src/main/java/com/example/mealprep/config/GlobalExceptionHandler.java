package com.example.mealprep.config;

import com.example.mealprep.auth.security.ServiceTokenAuthenticationProvider.OriginNotPermittedByTokenException;
import com.example.mealprep.core.origin.exception.ConfidenceBelowThresholdException;
import com.example.mealprep.core.origin.exception.OriginDepthExceededException;
import com.example.mealprep.core.origin.exception.OriginNotPermittedOnEndpointException;
import com.example.mealprep.core.origin.exception.OriginRateLimitExceededException;
import com.example.mealprep.core.origin.exception.OriginValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Cross-cutting exception → {@link ProblemDetail} mapper. Module-specific exceptions live in their
 * own {@code <module>/api/<Module>ExceptionHandler.java} classes; only generic / framework-level
 * exceptions are mapped here.
 *
 * <p>Ordered {@link Ordered#LOWEST_PRECEDENCE} so the {@link Exception}-catch-all here only fires
 * after every module-specific advice class has had a chance to match.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
    return ProblemDetailSupport.build(
        HttpStatus.BAD_REQUEST,
        ex.getMessage(),
        "validation-error",
        "Validation failed",
        req.getRequestURI());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest req) {
    return ProblemDetailSupport.build(
        HttpStatus.BAD_REQUEST,
        ex.getMessage(),
        "validation-error",
        "Validation failed",
        req.getRequestURI());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            "Validation failed",
            "validation-error",
            "Validation failed",
            req.getRequestURI());
    List<FieldError> fieldErrors = new ArrayList<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(fe -> fieldErrors.add(new FieldError(fe.getField(), fe.getDefaultMessage())));
    pd.setProperty("errors", fieldErrors);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(
      OptimisticLockingFailureException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            "Resource was updated concurrently; please retry.",
            "concurrent-update",
            "Concurrent update",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest req) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getReason());
    pd.setTitle(status.getReasonPhrase());
    pd.setInstance(java.net.URI.create(req.getRequestURI()));
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoResourceFound(
      NoResourceFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            "No handler for " + req.getRequestURI(),
            "not-found",
            "Not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * A request hits a mapped path with an unsupported HTTP method (e.g. POST to a GET-only,
   * read-only endpoint like the preference archive). Without this handler the framework exception
   * would fall through to the {@link Exception} catch-all below and surface as a misleading 500;
   * here we emit the correct {@code 405 Method Not Allowed} with the {@code Allow} header
   * advertising the methods the endpoint does support.
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.METHOD_NOT_ALLOWED,
            ex.getMessage(),
            "method-not-allowed",
            "Method not allowed",
            req.getRequestURI());
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON);
    if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
      builder.allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]));
    }
    return builder.body(pd);
  }

  // ------------------------------------------------------------------------------------------
  // Origin-tracking (core-02b). The five exceptions below correspond to the policy gates the
  // OriginFilter applies on inbound non-USER requests; mapping them centrally here keeps the
  // filter implementation linear (it just throws) and the ProblemDetail shape consistent.
  // ------------------------------------------------------------------------------------------

  @ExceptionHandler(OriginValidationException.class)
  public ProblemDetail handleOriginValidation(
      OriginValidationException ex, HttpServletRequest req) {
    return ProblemDetailSupport.build(
        HttpStatus.BAD_REQUEST,
        ex.getMessage(),
        ex.getProblemSlug(),
        "Origin validation failed",
        req.getRequestURI());
  }

  @ExceptionHandler(OriginDepthExceededException.class)
  public ResponseEntity<ProblemDetail> handleOriginDepthExceeded(
      OriginDepthExceededException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "origin-depth-exceeded",
            "Origin depth exceeded",
            req.getRequestURI());
    pd.setProperty("depth", ex.getDepth());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(ConfidenceBelowThresholdException.class)
  public ResponseEntity<ProblemDetail> handleConfidenceBelowThreshold(
      ConfidenceBelowThresholdException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "confidence-below-threshold",
            "AI-origin confidence below threshold",
            req.getRequestURI());
    pd.setProperty("threshold", ex.getThreshold());
    pd.setProperty("actual", ex.getActual());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(OriginNotPermittedOnEndpointException.class)
  public ResponseEntity<ProblemDetail> handleOriginNotPermitted(
      OriginNotPermittedOnEndpointException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.FORBIDDEN,
            ex.getMessage(),
            "origin-not-permitted-on-endpoint",
            "Origin not permitted on endpoint",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(OriginNotPermittedByTokenException.class)
  public ResponseEntity<ProblemDetail> handleOriginNotPermittedByToken(
      OriginNotPermittedByTokenException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.FORBIDDEN,
            ex.getMessage(),
            "origin-not-permitted-by-token",
            "Origin not permitted by service token",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(OriginRateLimitExceededException.class)
  public ResponseEntity<ProblemDetail> handleOriginRateLimitExceeded(
      OriginRateLimitExceededException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.TOO_MANY_REQUESTS,
            ex.getMessage(),
            "origin-rate-limit-exceeded",
            "Origin rate limit exceeded",
            req.getRequestURI());
    pd.setProperty("origin", ex.getOrigin().name());
    long retrySeconds = ProblemDetailSupport.clampToWholeSeconds(ex.getRetryAfter());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retrySeconds))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Bearer-token authentication failure surfaced by {@link
   * com.example.mealprep.auth.security.ServiceTokenAuthenticationProvider}. The session-cookie path
   * doesn't throw this — it falls through to anonymous and the chain returns 401 — but a
   * service-token path with an invalid / revoked / disabled token throws directly.
   */
  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ProblemDetail> handleBadCredentials(
      BadCredentialsException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNAUTHORIZED,
            ex.getMessage(),
            "authentication-required",
            "Unauthorized",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
    return ProblemDetailSupport.build(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "An unexpected error occurred.",
        "internal-error",
        "Internal error",
        req.getRequestURI());
  }

  /** Per-field error shape attached as {@code errors[]} on validation ProblemDetails. */
  public record FieldError(String field, String message) {}
}
