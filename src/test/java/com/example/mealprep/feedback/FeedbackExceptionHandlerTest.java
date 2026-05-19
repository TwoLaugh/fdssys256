package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.config.ProblemDetailSupport;
import com.example.mealprep.feedback.api.FeedbackExceptionHandler;
import com.example.mealprep.feedback.exception.ClarificationQueryAlreadyAnsweredException;
import com.example.mealprep.feedback.exception.ClarificationQueryExpiredException;
import com.example.mealprep.feedback.exception.ClarificationQueryNotFoundException;
import com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException;
import com.example.mealprep.feedback.exception.InvalidCorrectionTargetException;
import com.example.mealprep.feedback.exception.RoutingDecisionNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pure-unit test for {@link FeedbackExceptionHandler}. The advice has no collaborators, so it is
 * instantiated directly and each handler is exercised with a real exception plus a {@link
 * MockHttpServletRequest}. Every assertion pins one return-value field (status, content-type, type
 * slug, title, detail, instance) so PIT's "replaced return value" / changed-constant mutants on
 * each handler die.
 */
class FeedbackExceptionHandlerTest {

  private final FeedbackExceptionHandler handler = new FeedbackExceptionHandler();

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
  void feedbackEntryNotFound_maps_to_404() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    var resp =
        handler.handleFeedbackEntryNotFound(
            new FeedbackEntryNotFoundException(id), req("/api/feedback/" + id));
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "feedback-entry-not-found",
        "Feedback entry not found",
        "Feedback entry not found: " + id,
        "/api/feedback/" + id);
  }

  @Test
  void routingDecisionNotFound_maps_to_404() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");
    var resp =
        handler.handleRoutingDecisionNotFound(
            new RoutingDecisionNotFoundException(id), req("/api/feedback/routing/" + id));
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "routing-decision-not-found",
        "Routing decision not found",
        "Routing decision not found: " + id,
        "/api/feedback/routing/" + id);
  }

  @Test
  void clarificationQueryNotFound_maps_to_404() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000003");
    var resp =
        handler.handleClarificationQueryNotFound(
            new ClarificationQueryNotFoundException(id), req("/api/clarifications/" + id));
    assertProblem(
        resp,
        HttpStatus.NOT_FOUND,
        "clarification-query-not-found",
        "Clarification query not found",
        "Clarification query not found: " + id,
        "/api/clarifications/" + id);
  }

  // ---------------- 410 ----------------

  @Test
  void clarificationQueryExpired_maps_to_410_andExposesFeedbackEntryId() {
    UUID queryId = UUID.fromString("00000000-0000-0000-0000-000000000004");
    UUID entryId = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    var resp =
        handler.handleClarificationQueryExpired(
            new ClarificationQueryExpiredException(queryId, entryId),
            req("/api/clarifications/" + queryId + "/answer"));
    assertProblem(
        resp,
        HttpStatus.GONE,
        "clarification-query-expired",
        "Clarification query expired",
        "Clarification query expired: " + queryId,
        "/api/clarifications/" + queryId + "/answer");
    // The expired handler additionally exposes the parent entry id as an extension property so the
    // client can offer a re-submit. Pin it so the setProperty call cannot be mutated away.
    assertThat(resp.getBody().getProperties()).containsEntry("feedbackEntryId", entryId);
  }

  // ---------------- 422 ----------------

  @Test
  void clarificationQueryAlreadyAnswered_maps_to_422() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000005");
    var resp =
        handler.handleClarificationQueryAlreadyAnswered(
            new ClarificationQueryAlreadyAnsweredException(id),
            req("/api/clarifications/" + id + "/answer"));
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "clarification-query-already-answered",
        "Clarification query already answered",
        "Clarification query already answered: " + id,
        "/api/clarifications/" + id + "/answer");
  }

  @Test
  void invalidCorrectionTarget_maps_to_422() {
    var resp =
        handler.handleInvalidCorrectionTarget(
            new InvalidCorrectionTargetException("new destination equals original"),
            req("/api/feedback/corrections"));
    assertProblem(
        resp,
        HttpStatus.UNPROCESSABLE_ENTITY,
        "invalid-correction-target",
        "Invalid correction target",
        "new destination equals original",
        "/api/feedback/corrections");
  }

  // ---------------- cross-checks ----------------

  @Test
  void distinct_handlers_produce_distinct_status_and_slug() {
    UUID id = UUID.randomUUID();
    var notFound =
        handler.handleFeedbackEntryNotFound(new FeedbackEntryNotFoundException(id), req("/x"));
    var expired =
        handler.handleClarificationQueryExpired(
            new ClarificationQueryExpiredException(id, id), req("/x"));
    var unprocessable =
        handler.handleInvalidCorrectionTarget(
            new InvalidCorrectionTargetException("bad"), req("/x"));
    assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.GONE);
    assertThat(unprocessable.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(notFound.getBody().getType()).isNotEqualTo(expired.getBody().getType());
    assertThat(expired.getBody().getType()).isNotEqualTo(unprocessable.getBody().getType());
  }

  @Test
  void requestUri_isThreadedFromTheActualRequest_notHardcoded() {
    UUID id = UUID.randomUUID();
    var a =
        handler.handleFeedbackEntryNotFound(
            new FeedbackEntryNotFoundException(id), req("/api/path/A"));
    var b =
        handler.handleFeedbackEntryNotFound(
            new FeedbackEntryNotFoundException(id), req("/api/path/B"));
    assertThat(a.getBody().getInstance()).hasToString("/api/path/A");
    assertThat(b.getBody().getInstance()).hasToString("/api/path/B");
  }
}
