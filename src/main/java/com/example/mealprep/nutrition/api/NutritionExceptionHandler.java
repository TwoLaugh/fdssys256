package com.example.mealprep.nutrition.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.nutrition.exception.IngredientMappingNotFoundException;
import com.example.mealprep.nutrition.exception.IngredientMappingPipelineException;
import com.example.mealprep.nutrition.exception.IntakeDayNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSlotNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSnackNotFoundException;
import com.example.mealprep.nutrition.exception.InvalidIntakeRangeException;
import com.example.mealprep.nutrition.exception.JournalEntryNotFoundException;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Nutrition-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} catch-all (which would otherwise swallow these into
 * 500s).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NutritionExceptionHandler {

  @ExceptionHandler(NutritionTargetsNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleTargetsNotFound(
      NutritionTargetsNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "nutrition-targets-not-found",
            "Nutrition targets not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IntakeDayNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleIntakeDayNotFound(
      IntakeDayNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "intake-day-not-found",
            "Intake day not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IntakeSlotNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleIntakeSlotNotFound(
      IntakeSlotNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "intake-slot-not-found",
            "Intake slot not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IntakeSnackNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleIntakeSnackNotFound(
      IntakeSnackNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "intake-snack-not-found",
            "Intake snack not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidIntakeRangeException.class)
  public ResponseEntity<ProblemDetail> handleInvalidIntakeRange(
      InvalidIntakeRangeException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "invalid-intake-range",
            "Invalid intake range",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(JournalEntryNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleJournalEntryNotFound(
      JournalEntryNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "journal-entry-not-found",
            "Journal entry not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * Map the unique-constraint collision on {@code (user_id, on_date, meal_slot)} (slot-tied
   * journal-entry POST against an existing row) to HTTP 409. Lives here rather than in {@code
   * GlobalExceptionHandler} because the global advice is intentionally module-agnostic; this
   * mapping is generic-enough that promoting it later is a separate refactor.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
      DataIntegrityViolationException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            "A conflicting row already exists for this (user, date, mealSlot).",
            "journal-entry-conflict",
            "Conflict",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IngredientMappingNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleIngredientMappingNotFound(
      IngredientMappingNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "ingredient-mapping-not-found",
            "Ingredient mapping not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(IngredientMappingPipelineException.class)
  public ResponseEntity<ProblemDetail> handleIngredientMappingPipeline(
      IngredientMappingPipelineException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "ingredient-mapping-pipeline",
            "Ingredient mapping pipeline failure",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
