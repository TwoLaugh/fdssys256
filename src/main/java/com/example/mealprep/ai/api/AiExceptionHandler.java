package com.example.mealprep.ai.api;

import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.config.ProblemDetailSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** AI-module-specific exception → {@link ProblemDetail} mapper. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiExceptionHandler {

  @ExceptionHandler(AiCostBudgetExceededException.class)
  public ResponseEntity<ProblemDetail> handleAiCostBudgetExceeded(
      AiCostBudgetExceededException ex, HttpServletRequest req) {
    long retryAfterSeconds = ProblemDetailSupport.clampToWholeSeconds(ex.retryAfter());
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.TOO_MANY_REQUESTS,
            "AI cost budget exceeded",
            "ai-budget-exceeded",
            "AI budget exceeded",
            req.getRequestURI());
    pd.setProperty("spentPence", ex.spentPence());
    pd.setProperty("limitPence", ex.limitPence());
    pd.setProperty("windowSeconds", ex.window().toSeconds());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(AiUnavailableException.class)
  public ResponseEntity<ProblemDetail> handleAiUnavailable(
      AiUnavailableException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AI service unavailable",
            "ai-unavailable",
            "AI unavailable",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(AiInvalidRequestException.class)
  public ResponseEntity<ProblemDetail> handleAiInvalidRequest(
      AiInvalidRequestException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            "AI request rejected",
            "ai-invalid-request",
            "AI request invalid",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  @ExceptionHandler(AiInvalidResponseException.class)
  public ResponseEntity<ProblemDetail> handleAiInvalidResponse(
      AiInvalidResponseException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_GATEWAY,
            "AI response invalid",
            "ai-invalid-response",
            "AI response invalid",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
