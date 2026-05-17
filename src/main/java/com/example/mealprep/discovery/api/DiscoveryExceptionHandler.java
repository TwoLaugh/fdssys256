package com.example.mealprep.discovery.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.discovery.exception.DiscoveryAllSourcesUnavailableException;
import com.example.mealprep.discovery.exception.DiscoveryConstraintInvalidException;
import com.example.mealprep.discovery.exception.DiscoveryJobAlreadyTerminalException;
import com.example.mealprep.discovery.exception.DiscoveryJobNotFoundException;
import com.example.mealprep.discovery.exception.DiscoveryJobTimeoutException;
import com.example.mealprep.discovery.exception.DiscoverySourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Discovery-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s catch-all (which
 * would otherwise swallow these as 500s).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiscoveryExceptionHandler {

  @ExceptionHandler(DiscoveryJobNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleJobNotFound(
      DiscoveryJobNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "discovery-job-not-found",
            "Discovery job not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(DiscoverySourceNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleSourceNotFound(
      DiscoverySourceNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "discovery-source-not-found",
            "Discovery source not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(DiscoveryJobAlreadyTerminalException.class)
  public ResponseEntity<ProblemDetail> handleJobAlreadyTerminal(
      DiscoveryJobAlreadyTerminalException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "discovery-job-already-terminal",
            "Discovery job already in terminal state",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(DiscoveryConstraintInvalidException.class)
  public ResponseEntity<ProblemDetail> handleConstraintInvalid(
      DiscoveryConstraintInvalidException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "discovery-constraint-invalid",
            "Discovery constraint invalid",
            req.getRequestURI());
    if (!ex.errors().isEmpty()) {
      pd.setProperty("errors", ex.errors());
    }
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(DiscoveryAllSourcesUnavailableException.class)
  public ResponseEntity<ProblemDetail> handleAllSourcesDown(
      DiscoveryAllSourcesUnavailableException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_GATEWAY,
            ex.getMessage(),
            "discovery-all-sources-unavailable",
            "All discovery sources unavailable",
            req.getRequestURI());
    if (!ex.failedSources().isEmpty()) {
      pd.setProperty("failedSources", ex.failedSources());
    }
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(DiscoveryJobTimeoutException.class)
  public ResponseEntity<ProblemDetail> handleTimeout(
      DiscoveryJobTimeoutException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.REQUEST_TIMEOUT,
            ex.getMessage(),
            "discovery-job-timeout",
            "Discovery job timeout",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
