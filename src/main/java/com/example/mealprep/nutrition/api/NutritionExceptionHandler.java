package com.example.mealprep.nutrition.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.nutrition.exception.IntakeDayNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSlotNotFoundException;
import com.example.mealprep.nutrition.exception.IntakeSnackNotFoundException;
import com.example.mealprep.nutrition.exception.InvalidIntakeRangeException;
import com.example.mealprep.nutrition.exception.NutritionTargetsNotFoundException;
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
}
