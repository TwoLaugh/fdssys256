package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.api.AiExceptionHandler;
import com.example.mealprep.ai.exception.AiCircuitOpenException;
import com.example.mealprep.ai.exception.AiCostBudgetExceededException;
import com.example.mealprep.ai.exception.AiInvalidRequestException;
import com.example.mealprep.ai.exception.AiInvalidResponseException;
import com.example.mealprep.ai.exception.AiUnavailableException;
import com.example.mealprep.config.ProblemDetailSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pure-unit test for {@link AiExceptionHandler}. No collaborators — instantiated directly. Every
 * assertion pins one return-value field (status, content-type, type slug, title, detail, instance,
 * Retry-After header, ProblemDetail extension properties) so PIT's "replaced return value" /
 * changed-constant / removed-setProperty mutants on each handler die.
 */
class AiExceptionHandlerTest {

  private final AiExceptionHandler handler = new AiExceptionHandler();

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

  // ---------------- 429 (cost budget) ----------------

  @Test
  void costBudgetExceeded_maps_to_429_withRetryAfterHeaderAndExtensionProps() {
    UUID userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    var ex =
        new AiCostBudgetExceededException(
            userId,
            new BigDecimal("100.00"),
            new BigDecimal("50.00"),
            Duration.ofHours(24),
            Duration.ofMinutes(90).plusSeconds(1));
    var resp = handler.handleAiCostBudgetExceeded(ex, req("/api/ai/dispatch"));

    assertProblem(
        resp,
        HttpStatus.TOO_MANY_REQUESTS,
        "ai-budget-exceeded",
        "AI budget exceeded",
        "AI cost budget exceeded",
        "/api/ai/dispatch");
    // Retry-After must equal the clamped whole-seconds of the exception's retryAfter (5401s).
    assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5401");
    ProblemDetail pd = resp.getBody();
    assertThat(pd.getProperties())
        .containsEntry("spentPence", new BigDecimal("100.00"))
        .containsEntry("limitPence", new BigDecimal("50.00"))
        .containsEntry("windowSeconds", Duration.ofHours(24).toSeconds());
  }

  @Test
  void costBudgetExceeded_retryAfterFlooredToOne_whenDurationZeroOrNegative() {
    UUID userId = UUID.randomUUID();
    var ex =
        new AiCostBudgetExceededException(
            userId,
            new BigDecimal("10.00"),
            new BigDecimal("5.00"),
            Duration.ofHours(1),
            Duration.ZERO);
    var resp = handler.handleAiCostBudgetExceeded(ex, req("/api/ai/dispatch"));
    assertThat(resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    assertThat(resp.getBody().getProperties())
        .containsEntry("windowSeconds", Duration.ofHours(1).toSeconds());
  }

  // ---------------- 503 ----------------

  @Test
  void aiUnavailable_maps_to_503() {
    var resp =
        handler.handleAiUnavailable(
            new AiUnavailableException("provider 503"), req("/api/ai/admin/calls"));
    assertProblem(
        resp,
        HttpStatus.SERVICE_UNAVAILABLE,
        "ai-unavailable",
        "AI unavailable",
        "AI service unavailable",
        "/api/ai/admin/calls");
  }

  @Test
  void aiCircuitOpen_maps_to_503_withDistinctCircuitOpenSlug() {
    var resp =
        handler.handleAiCircuitOpen(
            new AiCircuitOpenException("circuit open for ai-FEEDBACK_CLASSIFICATION"),
            req("/api/v1/ai/dispatch"));
    assertProblem(
        resp,
        HttpStatus.SERVICE_UNAVAILABLE,
        "ai-circuit-open",
        "AI circuit open",
        "AI circuit open",
        "/api/v1/ai/dispatch");
  }

  @Test
  void circuitOpen_and_unavailable_shareStatus_butDistinctTypeSlug() {
    var circuitOpen = handler.handleAiCircuitOpen(new AiCircuitOpenException("open"), req("/x"));
    var unavailable = handler.handleAiUnavailable(new AiUnavailableException("down"), req("/x"));
    assertThat(circuitOpen.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(circuitOpen.getBody().getType()).isNotEqualTo(unavailable.getBody().getType());
  }

  // ---------------- 400 ----------------

  @Test
  void aiInvalidRequest_maps_to_400() {
    var resp =
        handler.handleAiInvalidRequest(
            new AiInvalidRequestException("bad prompt"), req("/api/ai/dispatch"));
    assertProblem(
        resp,
        HttpStatus.BAD_REQUEST,
        "ai-invalid-request",
        "AI request invalid",
        "AI request rejected",
        "/api/ai/dispatch");
  }

  // ---------------- 502 ----------------

  @Test
  void aiInvalidResponse_maps_to_502() {
    var resp =
        handler.handleAiInvalidResponse(
            new AiInvalidResponseException("unparseable tool_use"), req("/api/ai/dispatch"));
    assertProblem(
        resp,
        HttpStatus.BAD_GATEWAY,
        "ai-invalid-response",
        "AI response invalid",
        "AI response invalid",
        "/api/ai/dispatch");
  }

  // ---------------- cross-checks ----------------

  @Test
  void distinct_handlers_produce_distinct_status_and_slug() {
    var unavailable = handler.handleAiUnavailable(new AiUnavailableException("a"), req("/x"));
    var badRequest = handler.handleAiInvalidRequest(new AiInvalidRequestException("b"), req("/x"));
    var badGateway =
        handler.handleAiInvalidResponse(new AiInvalidResponseException("c"), req("/x"));
    assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(badGateway.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(unavailable.getBody().getType()).isNotEqualTo(badRequest.getBody().getType());
    assertThat(badRequest.getBody().getType()).isNotEqualTo(badGateway.getBody().getType());
  }
}
