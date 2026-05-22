package com.example.mealprep.notification.api;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.notification.exception.NotificationNotFoundException;
import com.example.mealprep.notification.exception.NotificationPreferenceNotFoundException;
import com.example.mealprep.notification.exception.NotificationStateTransitionException;
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
 * Notification-module-specific exception → {@link ProblemDetail} mapper. Annotated {@link
 * Order#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} catch-all. Generic / framework exceptions
 * (optimistic-lock 409, validation 400) are mapped by the global handler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationExceptionHandler {

  @ExceptionHandler(NotificationNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotificationNotFound(
      NotificationNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "notification-not-found",
            "Notification not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(NotificationPreferenceNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotificationPreferenceNotFound(
      NotificationPreferenceNotFoundException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.NOT_FOUND,
            ex.getMessage(),
            "notification-preference-not-found",
            "Notification preference not found",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(NotificationStateTransitionException.class)
  public ResponseEntity<ProblemDetail> handleNotificationStateTransition(
      NotificationStateTransitionException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.CONFLICT,
            ex.getMessage(),
            "notification-illegal-state",
            "Illegal notification state transition",
            req.getRequestURI());
    pd.setProperty("from", ex.getFrom() == null ? null : ex.getFrom().name());
    pd.setProperty("to", ex.getTo() == null ? null : ex.getTo().name());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
