package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.AdaptationExceptionHandler;
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
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.config.ProblemDetailSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * Pure-unit test for {@link AdaptationExceptionHandler}. The advice has no collaborators, so it is
 * instantiated directly and each handler is exercised with a real exception plus a {@link
 * MockHttpServletRequest}. Every assertion pins one return-value field (status, content-type, type
 * slug, title, detail, instance) so PIT's "replaced return value" mutants on each handler die.
 */
class AdaptationExceptionHandlerTest {

  private final AdaptationExceptionHandler handler = new AdaptationExceptionHandler();

  private MockHttpServletRequest req(String uri) {
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setRequestURI(uri);
    return r;
  }

  private void assertProblem(
      ResponseEntity<ProblemDetail> resp,
      HttpStatus expectedStatus,
      String expectedSlug,
      String expectedTitle,
      String expectedDetail,
      String expectedUri) {
    assertThat(resp.getStatusCode()).isEqualTo(expectedStatus);
    assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail pd = resp.getBody();
    assertThat(pd).isNotNull();
    assertThat(pd.getStatus()).isEqualTo(expectedStatus.value());
    assertThat(pd.getType()).hasToString(ProblemDetailSupport.PROBLEM_BASE + expectedSlug);
    assertThat(pd.getTitle()).isEqualTo(expectedTitle);
    assertThat(pd.getDetail()).isEqualTo(expectedDetail);
    assertThat(pd.getInstance()).hasToString(expectedUri);
  }

  // ---------------- 404 ----------------

  @Test
  void jobNotFound_maps_to_404() {
    var resp =
        handler.handleJobNotFound(
            new AdaptationJobNotFoundException("job 7 gone"), req("/api/adaptation/jobs/7"));
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "adaptation-job-not-found",
        "Adaptation job not found",
        "job 7 gone",
        "/api/adaptation/jobs/7");
  }

  @Test
  void pendingNotFound_maps_to_404() {
    var resp =
        handler.handlePendingNotFound(
            new PendingChangeNotFoundException("pc 9 gone"), req("/api/pc/9"));
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "pending-change-not-found",
        "Pending change not found",
        "pc 9 gone",
        "/api/pc/9");
  }

  @Test
  void traceNotFound_maps_to_404() {
    var resp =
        handler.handleTraceNotFound(
            new AdaptationTraceNotFoundException("trace gone"), req("/api/trace/1"));
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "adaptation-trace-not-found",
        "Adaptation trace not found",
        "trace gone",
        "/api/trace/1");
  }

  // ---------------- 422 ----------------

  @Test
  void pendingNotPending_maps_to_422() {
    var resp =
        handler.handlePendingNotPending(
            new PendingChangeNotPendingException("already accepted"), req("/api/pc/2/accept"));
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pending-change-not-pending",
        "Pending change is not pending",
        "already accepted",
        "/api/pc/2/accept");
  }

  @Test
  void pendingExpired_maps_to_422() {
    var resp =
        handler.handlePendingExpired(
            new PendingChangeExpiredException("ttl elapsed"), req("/api/pc/3/accept"));
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pending-change-expired",
        "Pending change expired",
        "ttl elapsed",
        "/api/pc/3/accept");
  }

  @Test
  void lowConfidence_maps_to_422() {
    var resp =
        handler.handleLowConfidence(
            new AdaptationLowConfidenceException("0.12 < floor"), req("/api/adapt"));
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "adaptation-low-confidence",
        "Adaptation low confidence",
        "0.12 < floor",
        "/api/adapt");
  }

  @Test
  void characterBreak_maps_to_422() {
    var resp =
        handler.handleCharacterBreak(
            new AdaptationCharacterBreakException("dish no longer a curry"), req("/api/adapt"));
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "adaptation-character-break",
        "Adaptation character break",
        "dish no longer a curry",
        "/api/adapt");
  }

  @Test
  void hardConstraintViolation_maps_to_422() {
    var resp =
        handler.handleHardConstraintViolation(
            new AdaptationHardConstraintViolationException("allergen reintroduced"),
            req("/api/adapt"));
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "adaptation-hard-constraint-violation",
        "Adaptation hard-constraint violation",
        "allergen reintroduced",
        "/api/adapt");
  }

  // ---------------- 409 ----------------

  @Test
  void superseded_maps_to_409() {
    var resp =
        handler.handleSuperseded(
            new PendingChangeSupersededException("newer proposal"), req("/api/pc/4/accept"));
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "pending-change-superseded",
        "Pending change superseded",
        "newer proposal",
        "/api/pc/4/accept");
  }

  @Test
  void lockTimeout_maps_to_409() {
    var resp =
        handler.handleLockTimeout(
            new LockTimeoutException("advisory lock busy"), req("/api/adapt"));
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "adaptation-lock-timeout",
        "Adaptation lock timeout",
        "advisory lock busy",
        "/api/adapt");
  }

  @Test
  void rebaseExhausted_maps_to_409() {
    var resp =
        handler.handleRebaseExhausted(
            new RebaseExhaustedException("3 attempts failed"), req("/api/pc/5/accept"));
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "adaptation-rebase-exhausted",
        "Adaptation rebase exhausted",
        "3 attempts failed",
        "/api/pc/5/accept");
  }

  @Test
  void jobNotRetryable_maps_to_409() {
    var resp =
        handler.handleJobNotRetryable(
            new AdaptationJobNotRetryableException("terminal SUCCEEDED"), req("/api/jobs/6/retry"));
    assertProblem(
        resp,
        HttpStatus.CONFLICT,
        "adaptation-job-not-retryable",
        "Adaptation job not retryable",
        "terminal SUCCEEDED",
        "/api/jobs/6/retry");
  }

  // ---------------- 400 ----------------

  @Test
  void missingParam_maps_to_400() {
    var ex = new MissingServletRequestParameterException("recipeId", "UUID");
    var resp = handler.handleMissingParam(ex, req("/api/adapt"));
    assertProblem(
        resp,
        HttpStatus.BAD_REQUEST,
        "missing-request-parameter",
        "Missing request parameter",
        ex.getMessage(),
        "/api/adapt");
  }

  // ---------------- 503 ----------------

  @Test
  void aiUnavailable_maps_to_503() {
    var resp =
        handler.handleAiUnavailable(
            new AdaptationAiUnavailableException(
                "monthly cap hit", new AiUnavailableException("provider 503")),
            req("/api/adapt"));
    assertProblem(
        resp,
        HttpStatus.SERVICE_UNAVAILABLE,
        "adaptation-ai-unavailable",
        "AI features paused",
        "monthly cap hit",
        "/api/adapt");
  }

  @Test
  void distinct_handlers_produce_distinct_status_and_slug() {
    // Cross-check: a 404 handler and a 409 handler must not converge (kills mutants that
    // swap the helper call / status constant between buckets).
    var nf = handler.handleJobNotFound(new AdaptationJobNotFoundException("a"), req("/x"));
    var cf = handler.handleLockTimeout(new LockTimeoutException("b"), req("/x"));
    assertThat(nf.getStatusCode()).isNotEqualTo(cf.getStatusCode());
    assertThat(nf.getBody().getType()).isNotEqualTo(cf.getBody().getType());
  }
}
