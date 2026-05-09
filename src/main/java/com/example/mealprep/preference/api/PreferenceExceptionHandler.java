package com.example.mealprep.preference.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.preference.exception.HardConstraintsNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Preference-module-specific exception → {@link ProblemDetail} mapper. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PreferenceExceptionHandler {

  @ExceptionHandler(HardConstraintsNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleHardConstraintsNotFound(
      HardConstraintsNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "hard-constraints-not-found",
            "Hard constraints not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
