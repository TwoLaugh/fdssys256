package com.example.mealprep.feedback.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException;
import com.example.mealprep.feedback.exception.RoutingDecisionNotFoundException;
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
 * Feedback-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} per the agent-prompt-template gotcha — without it, {@code
 * GlobalExceptionHandler}'s {@code @ExceptionHandler(Exception.class)} catch-all would swallow
 * these into HTTP 500.
 *
 * <p>01b handles the two 404 cases; subsequent tickets (01c/01e/01f) append handlers for the
 * classification / clarification / correction exceptions they introduce.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FeedbackExceptionHandler {

  @ExceptionHandler(FeedbackEntryNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleFeedbackEntryNotFound(
      FeedbackEntryNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "feedback-entry-not-found",
            "Feedback entry not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(RoutingDecisionNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleRoutingDecisionNotFound(
      RoutingDecisionNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "routing-decision-not-found",
            "Routing decision not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
