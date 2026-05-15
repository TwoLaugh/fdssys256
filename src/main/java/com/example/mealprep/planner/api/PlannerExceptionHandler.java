package com.example.mealprep.planner.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.planner.exception.ConcurrentGenerationInProgressException;
import com.example.mealprep.planner.exception.InvalidPlanStateTransitionException;
import com.example.mealprep.planner.exception.InvalidSlotStateTransitionException;
import com.example.mealprep.planner.exception.MealSlotNotFoundException;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.ReoptSuggestionNotFoundException;
import com.example.mealprep.planner.exception.RevertTargetNotInHistoryException;
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
 * Planner-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} catch-all (which would otherwise swallow these into
 * 500s) — per the locked multi-advice ordering trap (gotchas #2).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlannerExceptionHandler {

  @ExceptionHandler(PlanNotFoundException.class)
  public ResponseEntity<ProblemDetail> handlePlanNotFound(
      PlanNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "plan-not-found",
            "Plan not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(MealSlotNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleMealSlotNotFound(
      MealSlotNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "meal-slot-not-found",
            "Meal slot not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(ReoptSuggestionNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleReoptSuggestionNotFound(
      ReoptSuggestionNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "reopt-suggestion-not-found",
            "Re-opt suggestion not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidPlanStateTransitionException.class)
  public ResponseEntity<ProblemDetail> handleInvalidPlanStateTransition(
      InvalidPlanStateTransitionException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "invalid-plan-state-transition",
            "Invalid plan state transition",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidSlotStateTransitionException.class)
  public ResponseEntity<ProblemDetail> handleInvalidSlotStateTransition(
      InvalidSlotStateTransitionException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "invalid-slot-state-transition",
            "Invalid slot state transition",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(ConcurrentGenerationInProgressException.class)
  public ResponseEntity<ProblemDetail> handleConcurrentGeneration(
      ConcurrentGenerationInProgressException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "concurrent-generation",
            "Concurrent plan generation in progress",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RevertTargetNotInHistoryException.class)
  public ResponseEntity<ProblemDetail> handleRevertTargetNotInHistory(
      RevertTargetNotInHistoryException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "revert-target-invalid",
            "Revert target is not in caller's history",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
