package com.example.mealprep.planner.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.planner.exception.MealSlotNotFoundException;
import com.example.mealprep.planner.exception.PlanNotFoundException;
import com.example.mealprep.planner.exception.ReoptSuggestionNotFoundException;
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
}
