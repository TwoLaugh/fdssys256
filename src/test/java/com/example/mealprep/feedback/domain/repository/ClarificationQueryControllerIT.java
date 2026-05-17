package com.example.mealprep.feedback.domain.repository;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.feedback.api.dto.AnswerClarificationRequest;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * HTTP-flow IT for {@code ClarificationQueryController}. Lives in {@code domain.repository} so the
 * package-private repositories can be autowired to seed entries + clarifications directly (no POST
 * → no async runner racing the seed — wave-3 retro). Verifies the three endpoints, OpenAPI
 * contract, the re-fire {@code FeedbackSubmittedEvent}, and the 404/410/422 negative paths.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  ClarificationQueryControllerIT.EventCaptureConfig.class
})
@ActiveProfiles("test")
class ClarificationQueryControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private ClarificationQueryRepository clarificationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private SubmittedEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie(authProperties.cookieName());
    String userId =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userId), cookie);
  }

  private ClarificationQuery seedPendingClarification(UUID userId) {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "did you mean the recipe?");
    entry.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entryRepository.saveAndFlush(entry);
    ClarificationQuery query = FeedbackTestData.clarificationQuery(entry);
    return clarificationRepository.saveAndFlush(query);
  }

  // ---------------- GET list ----------------

  @Test
  void list_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/feedback/clarifications")).andExpect(status().isUnauthorized());
  }

  @Test
  void list_returnsOnlyCallersQueries_withStatusFilter() throws Exception {
    AuthedUser alice = registerUser();
    AuthedUser bob = registerUser();
    seedPendingClarification(alice.userId());
    seedPendingClarification(alice.userId());
    seedPendingClarification(bob.userId());

    mvc.perform(get("/api/v1/feedback/clarifications").cookie(alice.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(openApi().isValid(openApiValidator));

    mvc.perform(
            get("/api/v1/feedback/clarifications").param("status", "PENDING").cookie(bob.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].status").value("PENDING"))
        .andExpect(openApi().isValid(openApiValidator));

    mvc.perform(
            get("/api/v1/feedback/clarifications")
                .param("status", "ANSWERED")
                .cookie(alice.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  // ---------------- GET by id ----------------

  @Test
  void getById_returns200_forOwner_and404_forOther() throws Exception {
    AuthedUser alice = registerUser();
    AuthedUser bob = registerUser();
    ClarificationQuery q = seedPendingClarification(alice.userId());

    mvc.perform(get("/api/v1/feedback/clarifications/" + q.getId()).cookie(alice.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(q.getId().toString()))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.options").isArray())
        .andExpect(openApi().isValid(openApiValidator));

    mvc.perform(get("/api/v1/feedback/clarifications/" + q.getId()).cookie(bob.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/clarification-query-not-found"));

    mvc.perform(get("/api/v1/feedback/clarifications/" + UUID.randomUUID()).cookie(alice.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- POST answer ----------------

  @Test
  void answer_happyPath_withDestination_marksAnswered_entryReceived_publishesEvent()
      throws Exception {
    AuthedUser user = registerUser();
    ClarificationQuery q = seedPendingClarification(user.userId());
    FeedbackEntry entry = q.getFeedbackEntry();
    AnswerClarificationRequest req = new AnswerClarificationRequest(Destination.PREFERENCE, null);

    mvc.perform(
            post("/api/v1/feedback/clarifications/" + q.getId() + "/answer")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submissionStatus").value("RECEIVED"))
        .andExpect(jsonPath("$.routes.length()").value(0))
        .andExpect(jsonPath("$.pendingClarificationQueryId").doesNotExist())
        .andExpect(jsonPath("$.traceId").value(entry.getTraceId().toString()))
        .andExpect(openApi().isValid(openApiValidator));

    ClarificationQuery reloaded = clarificationRepository.findById(q.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(ClarificationStatus.ANSWERED);
    assertThat(reloaded.getSelectedDestination()).isEqualTo(Destination.PREFERENCE);
    assertThat(reloaded.getAnsweredAt()).isNotNull();

    assertThat(eventCapture.events()).isNotEmpty();
    FeedbackSubmittedEvent ev = eventCapture.events().get(0);
    assertThat(ev.feedbackId()).isEqualTo(entry.getId());
    assertThat(ev.traceId()).isEqualTo(entry.getTraceId());
  }

  @Test
  void answer_withOnlyText_isAccepted() throws Exception {
    AuthedUser user = registerUser();
    ClarificationQuery q = seedPendingClarification(user.userId());

    mvc.perform(
            post("/api/v1/feedback/clarifications/" + q.getId() + "/answer")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new AnswerClarificationRequest(null, "I meant the standing preference"))))
        .andExpect(status().isOk());

    ClarificationQuery reloaded = clarificationRepository.findById(q.getId()).orElseThrow();
    assertThat(reloaded.getSelectedDestination()).isNull();
    assertThat(reloaded.getUserClarificationText()).isEqualTo("I meant the standing preference");
  }

  @Test
  void answer_withNeitherField_returns400() throws Exception {
    AuthedUser user = registerUser();
    ClarificationQuery q = seedPendingClarification(user.userId());

    mvc.perform(
            post("/api/v1/feedback/clarifications/" + q.getId() + "/answer")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new AnswerClarificationRequest(null, "  "))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void answer_onExpiredQuery_returns410_withFeedbackEntryId() throws Exception {
    AuthedUser user = registerUser();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(user.userId(), "stale");
    entry.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entryRepository.saveAndFlush(entry);
    ClarificationQuery q = FeedbackTestData.clarificationQuery(entry);
    q.setStatus(ClarificationStatus.EXPIRED);
    clarificationRepository.saveAndFlush(q);

    mvc.perform(
            post("/api/v1/feedback/clarifications/" + q.getId() + "/answer")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new AnswerClarificationRequest(Destination.RECIPE, null))))
        .andExpect(status().isGone())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/clarification-query-expired"))
        .andExpect(jsonPath("$.feedbackEntryId").value(entry.getId().toString()));
  }

  @Test
  void answer_onAnsweredQuery_returns422() throws Exception {
    AuthedUser user = registerUser();
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(user.userId(), "done");
    entry.setSubmissionStatus(SubmissionStatus.RECEIVED);
    entryRepository.saveAndFlush(entry);
    ClarificationQuery q =
        FeedbackTestData.answeredClarificationQuery(entry, Destination.RECIPE, "prior");
    clarificationRepository.saveAndFlush(q);

    mvc.perform(
            post("/api/v1/feedback/clarifications/" + q.getId() + "/answer")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new AnswerClarificationRequest(Destination.PREFERENCE, null))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://mealprep.example.com/problems/clarification-query-already-answered"));
  }

  @Test
  void answer_onAnotherUsersQuery_returns404() throws Exception {
    AuthedUser alice = registerUser();
    AuthedUser bob = registerUser();
    ClarificationQuery q = seedPendingClarification(alice.userId());

    mvc.perform(
            post("/api/v1/feedback/clarifications/" + q.getId() + "/answer")
                .cookie(bob.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new AnswerClarificationRequest(Destination.RECIPE, null))))
        .andExpect(status().isNotFound());
  }

  // ---------------- AFTER_COMMIT capture ----------------

  @TestConfiguration
  static class EventCaptureConfig {
    @Bean
    SubmittedEventCapture submittedEventCapture() {
      return new SubmittedEventCapture();
    }
  }

  static class SubmittedEventCapture {
    private final List<FeedbackSubmittedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(FeedbackSubmittedEvent event) {
      events.add(event);
    }

    List<FeedbackSubmittedEvent> events() {
      return events;
    }

    void clear() {
      events.clear();
    }
  }
}
