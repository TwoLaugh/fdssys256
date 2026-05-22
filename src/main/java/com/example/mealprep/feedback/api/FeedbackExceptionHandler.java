package com.example.mealprep.feedback.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.feedback.exception.ClarificationQueryAlreadyAnsweredException;
import com.example.mealprep.feedback.exception.ClarificationQueryExpiredException;
import com.example.mealprep.feedback.exception.ClarificationQueryNotFoundException;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException;
import com.example.mealprep.feedback.exception.InvalidCorrectionTargetException;
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

  @ExceptionHandler(ClarificationQueryNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleClarificationQueryNotFound(
      ClarificationQueryNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "clarification-query-not-found",
            "Clarification query not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(ClarificationQueryExpiredException.class)
  public ResponseEntity<ProblemDetail> handleClarificationQueryExpired(
      ClarificationQueryExpiredException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.GONE,
            ex.getMessage(),
            "clarification-query-expired",
            "Clarification query expired",
            req.getRequestURI());
    pd.setProperty("feedbackEntryId", ex.feedbackEntryId());
    return ResponseEntity.status(HttpStatus.GONE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(ClarificationQueryAlreadyAnsweredException.class)
  public ResponseEntity<ProblemDetail> handleClarificationQueryAlreadyAnswered(
      ClarificationQueryAlreadyAnsweredException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "clarification-query-already-answered",
            "Clarification query already answered",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(InvalidCorrectionTargetException.class)
  public ResponseEntity<ProblemDetail> handleInvalidCorrectionTarget(
      InvalidCorrectionTargetException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.getMessage(),
            "invalid-correction-target",
            "Invalid correction target",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  /**
   * A destination bridge's downstream call failed (feedback-01g §22). This is normally caught by
   * the router and recorded as a routing-log FAILED row — it does not propagate out of the
   * AFTER-routing dispatch — so this handler is defensive: it maps the (rare) surfaced case to a
   * 500 with a stable slug rather than letting {@code GlobalExceptionHandler}'s catch-all dilute
   * it.
   */
  @ExceptionHandler(FeedbackBridgeDispatchFailedException.class)
  public ResponseEntity<ProblemDetail> handleBridgeDispatchFailed(
      FeedbackBridgeDispatchFailedException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ex.getMessage(),
            "feedback-bridge-dispatch-failed",
            "Feedback bridge dispatch failed",
            req.getRequestURI());
    pd.setProperty("destination", ex.destination().name());
    pd.setProperty("feedbackId", ex.feedbackId());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
