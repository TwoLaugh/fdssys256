package com.example.mealprep.adaptation.api;

import com.example.mealprep.adaptation.exception.AdaptationAiResponseInvalidException;
import com.example.mealprep.adaptation.exception.AdaptationAiUnavailableException;
import com.example.mealprep.adaptation.exception.AdaptationCharacterBreakException;
import com.example.mealprep.adaptation.exception.AdaptationHardConstraintViolationException;
import com.example.mealprep.adaptation.exception.AdaptationJobNotFoundException;
import com.example.mealprep.adaptation.exception.AdaptationJobNotRetryableException;
import com.example.mealprep.adaptation.exception.AdaptationLowConfidenceException;
import com.example.mealprep.adaptation.exception.AdaptationTraceNotFoundException;
import com.example.mealprep.adaptation.exception.LockTimeoutException;
import com.example.mealprep.adaptation.exception.PendingChangeExpiredException;
import com.example.mealprep.adaptation.exception.PendingChangeNotFoundException;
import com.example.mealprep.adaptation.exception.PendingChangeNotPendingException;
import com.example.mealprep.adaptation.exception.PendingChangeSupersededException;
import com.example.mealprep.adaptation.exception.RebaseExhaustedException;
import com.example.mealprep.config.ProblemDetailSupport;
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
 * Adaptation-specific exception → {@link ProblemDetail} mapper. Ordered {@link
 * Ordered#HIGHEST_PRECEDENCE} so it fires before {@code GlobalExceptionHandler}'s
 * {@code @ExceptionHandler(Exception.class)} catch-all (which would otherwise swallow these into
 * 500s).
 *
 * <p>01a wires every new exception declared in {@code com.example.mealprep.adaptation.exception};
 * later sub-tickets that introduce more exception classes append handlers here. Mappings line up
 * with {@code lld/adaptation-pipeline.md} §Error responses (lines 622-632).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdaptationExceptionHandler {

  // ---------------- 404: not found ----------------

  @ExceptionHandler(AdaptationJobNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleJobNotFound(
      AdaptationJobNotFoundException ex, HttpServletRequest req) {
    return notFound(ex.getMessage(), "adaptation-job-not-found", "Adaptation job not found", req);
  }

  @ExceptionHandler(PendingChangeNotFoundException.class)
  public ResponseEntity<ProblemDetail> handlePendingNotFound(
      PendingChangeNotFoundException ex, HttpServletRequest req) {
    return notFound(ex.getMessage(), "pending-change-not-found", "Pending change not found", req);
  }

  @ExceptionHandler(AdaptationTraceNotFoundException.class)
  public ResponseEntity<ProblemDetail> handleTraceNotFound(
      AdaptationTraceNotFoundException ex, HttpServletRequest req) {
    return notFound(
        ex.getMessage(), "adaptation-trace-not-found", "Adaptation trace not found", req);
  }

  // ---------------- 422: semantic failure ----------------

  @ExceptionHandler(PendingChangeNotPendingException.class)
  public ResponseEntity<ProblemDetail> handlePendingNotPending(
      PendingChangeNotPendingException ex, HttpServletRequest req) {
    return unprocessable(
        ex.getMessage(), "pending-change-not-pending", "Pending change is not pending", req);
  }

  @ExceptionHandler(PendingChangeExpiredException.class)
  public ResponseEntity<ProblemDetail> handlePendingExpired(
      PendingChangeExpiredException ex, HttpServletRequest req) {
    return unprocessable(ex.getMessage(), "pending-change-expired", "Pending change expired", req);
  }

  @ExceptionHandler(AdaptationLowConfidenceException.class)
  public ResponseEntity<ProblemDetail> handleLowConfidence(
      AdaptationLowConfidenceException ex, HttpServletRequest req) {
    return unprocessable(
        ex.getMessage(), "adaptation-low-confidence", "Adaptation low confidence", req);
  }

  @ExceptionHandler(AdaptationCharacterBreakException.class)
  public ResponseEntity<ProblemDetail> handleCharacterBreak(
      AdaptationCharacterBreakException ex, HttpServletRequest req) {
    return unprocessable(
        ex.getMessage(), "adaptation-character-break", "Adaptation character break", req);
  }

  @ExceptionHandler(AdaptationHardConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleHardConstraintViolation(
      AdaptationHardConstraintViolationException ex, HttpServletRequest req) {
    return unprocessable(
        ex.getMessage(),
        "adaptation-hard-constraint-violation",
        "Adaptation hard-constraint violation",
        req);
  }

  // ---------------- 409: conflict ----------------

  @ExceptionHandler(PendingChangeSupersededException.class)
  public ResponseEntity<ProblemDetail> handleSuperseded(
      PendingChangeSupersededException ex, HttpServletRequest req) {
    return conflict(ex.getMessage(), "pending-change-superseded", "Pending change superseded", req);
  }

  @ExceptionHandler(LockTimeoutException.class)
  public ResponseEntity<ProblemDetail> handleLockTimeout(
      LockTimeoutException ex, HttpServletRequest req) {
    return conflict(ex.getMessage(), "adaptation-lock-timeout", "Adaptation lock timeout", req);
  }

  @ExceptionHandler(RebaseExhaustedException.class)
  public ResponseEntity<ProblemDetail> handleRebaseExhausted(
      RebaseExhaustedException ex, HttpServletRequest req) {
    return conflict(
        ex.getMessage(), "adaptation-rebase-exhausted", "Adaptation rebase exhausted", req);
  }

  @ExceptionHandler(AdaptationJobNotRetryableException.class)
  public ResponseEntity<ProblemDetail> handleJobNotRetryable(
      AdaptationJobNotRetryableException ex, HttpServletRequest req) {
    return conflict(
        ex.getMessage(), "adaptation-job-not-retryable", "Adaptation job not retryable", req);
  }

  // ---------------- 400: bad request ----------------

  /**
   * Spring's default 400 for a missing required query/path param is otherwise swallowed into a 500
   * by the project-wide {@code @ExceptionHandler(Exception.class)} catch-all because this advice is
   * {@code @Order(HIGHEST_PRECEDENCE)} (wave-3 retro 0012). Map it explicitly to 400.
   */
  @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
  public ResponseEntity<ProblemDetail> handleMissingParam(
      org.springframework.web.bind.MissingServletRequestParameterException ex,
      HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_REQUEST,
            ex.getMessage(),
            "missing-request-parameter",
            "Missing request parameter",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  // ---------------- 503: AI unavailable ----------------

  @ExceptionHandler(AdaptationAiUnavailableException.class)
  public ResponseEntity<ProblemDetail> handleAiUnavailable(
      AdaptationAiUnavailableException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.SERVICE_UNAVAILABLE,
            ex.getMessage(),
            "adaptation-ai-unavailable",
            "AI features paused",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  // ---------------- 502: invalid upstream AI response ----------------

  /**
   * A terminal Stage-C failure where the upstream produced output we cannot consume (malformed /
   * unparseable) or rejected the request as a 4xx caller-bug. Mapped to 502 Bad Gateway — the
   * upstream surfaced something invalid (mirrors {@code AiInvalidResponseException}'s own 502
   * mapping). Distinct from 503 ({@code AdaptationAiUnavailableException}, a deferrable degrade).
   */
  @ExceptionHandler(AdaptationAiResponseInvalidException.class)
  public ResponseEntity<ProblemDetail> handleAiResponseInvalid(
      AdaptationAiResponseInvalidException ex, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.BAD_GATEWAY,
            ex.getMessage(),
            "adaptation-ai-response-invalid",
            "AI returned an invalid response",
            req.getRequestURI());
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  // ---------------- helpers ----------------

  private ResponseEntity<ProblemDetail> notFound(
      String detail, String slug, String title, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(HttpStatus.NOT_FOUND, detail, slug, title, req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  private ResponseEntity<ProblemDetail> unprocessable(
      String detail, String slug, String title, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(
            HttpStatus.UNPROCESSABLE_ENTITY, detail, slug, title, req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }

  private ResponseEntity<ProblemDetail> conflict(
      String detail, String slug, String title, HttpServletRequest req) {
    ProblemDetail pd =
        ProblemDetailSupport.build(HttpStatus.CONFLICT, detail, slug, title, req.getRequestURI());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(pd);
  }
}
