package com.example.mealprep.preference.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.preference.exception.HardConstraintsNotFoundException;
import com.example.mealprep.preference.exception.InvalidDirectivePreferenceRouteException;
import com.example.mealprep.preference.exception.InvalidNoveltyToleranceException;
import com.example.mealprep.preference.exception.InvalidTasteProfileDeltaException;
import com.example.mealprep.preference.exception.LifestyleConfigNotFoundException;
import com.example.mealprep.preference.exception.PreferenceArchiveEntryNotFoundException;
import com.example.mealprep.preference.exception.TasteProfileBudgetExceededException;
import com.example.mealprep.preference.exception.TasteProfileNotFoundException;
import com.example.mealprep.preference.exception.TasteProfileVersionNotFoundException;
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
    return notFound(
        ex.getMessage(), "hard-constraints-not-found", "Hard constraints not found", req);
  }

  @ExceptionHandler(TasteProfileNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleTasteProfileNotFound(
      TasteProfileNotFoundException ex, HttpServletRequest req) {
    return notFound(ex.getMessage(), "taste-profile-not-found", "Taste profile not found", req);
  }

  @ExceptionHandler(TasteProfileVersionNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleTasteProfileVersionNotFound(
      TasteProfileVersionNotFoundException ex, HttpServletRequest req) {
    return notFound(
        ex.getMessage(), "taste-profile-version-not-found", "Taste profile version not found", req);
  }

  @ExceptionHandler(PreferenceArchiveEntryNotFoundException.class)
  public ResponseEntity<ProblemDetail> handlePreferenceArchiveEntryNotFound(
      PreferenceArchiveEntryNotFoundException ex, HttpServletRequest req) {
    return notFound(
        ex.getMessage(),
        "preference-archive-entry-not-found",
        "Preference archive entry not found",
        req);
  }

  @ExceptionHandler(InvalidTasteProfileDeltaException.class)
  public ResponseEntity<ProblemDetail> handleInvalidDelta(
      InvalidTasteProfileDeltaException ex, HttpServletRequest req) {
    return unprocessable(
        ex.getMessage(), "invalid-taste-profile-delta", "Invalid taste profile delta", req);
  }

  @ExceptionHandler(TasteProfileBudgetExceededException.class)
  public ResponseEntity<ProblemDetail> handleBudgetExceeded(
      TasteProfileBudgetExceededException ex, HttpServletRequest req) {
    return unprocessable(
        ex.getMessage(), "taste-profile-budget-exceeded", "Taste profile budget exceeded", req);
  }

  /**
   * A {@code preference_model} health directive whose {@code action} doesn't map to a
   * hard-constraint mutation. The throw originates in-process from {@code
   * PreferenceDirectiveApplyTarget} (invoked by the nutrition {@code DirectiveApplier} inside the
   * accept tx); this advice is global, so it maps the exception to a clean 422 no matter which
   * controller surfaced it.
   */
  @ExceptionHandler(InvalidDirectivePreferenceRouteException.class)
  public ResponseEntity<ProblemDetail> handleInvalidDirectivePreferenceRoute(
      InvalidDirectivePreferenceRouteException ex, HttpServletRequest req) {
    return unprocessable(
        ex.getMessage(),
        "invalid-directive-preference-route",
        "Invalid directive preference route",
        req);
  }

  private static ResponseEntity<ProblemDetail> notFound(
      String message, String type, String title, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(HttpStatus.NOT_FOUND, message, type, title, req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(LifestyleConfigNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleLifestyleConfigNotFound(
      LifestyleConfigNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "lifestyle-config-not-found",
            "Lifestyle config not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidNoveltyToleranceException.class)
  public ResponseEntity<ProblemDetail> handleInvalidNoveltyTolerance(
      InvalidNoveltyToleranceException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "invalid-novelty-tolerance",
            "Invalid novelty tolerance",
            req.getRequestURI());
    pd.setProperty("offendingMode", ex.offendingMode());
    pd.setProperty("offendingField", ex.offendingField());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  private static ResponseEntity<ProblemDetail> unprocessable(
      String message, String type, String title, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY, message, type, title, req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
