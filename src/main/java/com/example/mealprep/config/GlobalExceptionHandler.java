package com.example.mealprep.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
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
