package com.example.mealprep.feedback;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.in;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackRequest;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
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
 * Full HTTP-flow IT over {@code FeedbackController}. Verifies the three endpoints, the OpenAPI
 * contract, the AFTER_COMMIT event publication, and the negative paths (auth, validation, 404
 * cross-user).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  FeedbackControllerIT.FeedbackEventCaptureConfig.class
})
@ActiveProfiles("test")
class FeedbackControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private FeedbackEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM feedback_misclassification_corrections");
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

  // ---------------- helpers ----------------

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
    String userIdJson =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText();
    return new AuthedUser(UUID.fromString(userIdJson), cookie);
  }

  private SubmitFeedbackRequest recipeDetailRequest(String text) {
    return new SubmitFeedbackRequest(
        text, new UiContextDto(Screen.RECIPE_DETAIL, UUID.randomUUID(), 1, null, null, null));
  }

  // ---------------- POST /api/v1/feedback ----------------

  @Test
  void post_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            post("/api/v1/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(recipeDetailRequest("salt was too much"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void post_returns202_persistsEntry_andPublishesEventAfterCommit() throws Exception {
    AuthedUser user = registerUser();
    SubmitFeedbackRequest req = recipeDetailRequest("the salt was too much");

    MvcResult result =
        mvc.perform(
                post("/api/v1/feedback")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.submissionStatus").value("RECEIVED"))
            .andExpect(jsonPath("$.feedbackId").isString())
            .andExpect(jsonPath("$.traceId").isString())
            .andExpect(jsonPath("$.routes").isArray())
            .andExpect(jsonPath("$.routes.length()").value(0))
            .andExpect(jsonPath("$.pendingClarificationQueryId").doesNotExist())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    String feedbackId =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("feedbackId").asText();

    String locationHeader = result.getResponse().getHeader("Location");
    assertThat(locationHeader).endsWith("/api/v1/feedback/" + feedbackId);

    Long entryCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM feedback_entries WHERE id = ?::uuid", Long.class, feedbackId);
    assertThat(entryCount).isEqualTo(1L);

    Long routingCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM feedback_routing_log WHERE feedback_entry_id = ?::uuid",
            Long.class,
            feedbackId);
    assertThat(routingCount).isZero();

    assertThat(eventCapture.events()).hasSize(1);
    FeedbackSubmittedEvent event = eventCapture.events().get(0);
    assertThat(event.feedbackId().toString()).isEqualTo(feedbackId);
    assertThat(event.userId()).isEqualTo(user.userId());
    assertThat(event.screen()).isEqualTo(Screen.RECIPE_DETAIL);
  }

  @Test
  void post_returns400_whenTextBlank() throws Exception {
    AuthedUser user = registerUser();
    SubmitFeedbackRequest blank = recipeDetailRequest("");
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(blank)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenTextTooLong() throws Exception {
    AuthedUser user = registerUser();
    String huge = "a".repeat(4001);
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(recipeDetailRequest(huge))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenContextMissing() throws Exception {
    AuthedUser user = registerUser();
    String body = "{\"text\":\"hello\"}";
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenRecipeDetailMissesRecipeId() throws Exception {
    AuthedUser user = registerUser();
    SubmitFeedbackRequest req =
        new SubmitFeedbackRequest(
            "x", new UiContextDto(Screen.RECIPE_DETAIL, null, null, null, null, null));
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenPlanMealDetailMissesPlanId() throws Exception {
    AuthedUser user = registerUser();
    SubmitFeedbackRequest req =
        new SubmitFeedbackRequest(
            "x",
            new UiContextDto(Screen.PLAN_MEAL_DETAIL, null, null, UUID.randomUUID(), null, null));
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns400_whenRecipeVersionWithoutRecipeId() throws Exception {
    AuthedUser user = registerUser();
    SubmitFeedbackRequest req =
        new SubmitFeedbackRequest("x", new UiContextDto(Screen.GENERAL, null, 3, null, null, null));
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void post_returns202_whenGeneralScreen_allContextFieldsNull() throws Exception {
    AuthedUser user = registerUser();
    SubmitFeedbackRequest req =
        new SubmitFeedbackRequest(
            "hi", new UiContextDto(Screen.GENERAL, null, null, null, null, null));
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isAccepted());
  }

  // ---------------- GET /api/v1/feedback ----------------

  @Test
  void list_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/feedback")).andExpect(status().isUnauthorized());
  }

  @Test
  void list_returnsOnlyCallersEntries() throws Exception {
    AuthedUser alice = registerUser();
    AuthedUser bob = registerUser();

    // Alice submits two; Bob submits one.
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(alice.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(recipeDetailRequest("a1"))))
        .andExpect(status().isAccepted());
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(alice.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(recipeDetailRequest("a2"))))
        .andExpect(status().isAccepted());
    mvc.perform(
            post("/api/v1/feedback")
                .cookie(bob.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(recipeDetailRequest("b1"))))
        .andExpect(status().isAccepted());

    mvc.perform(get("/api/v1/feedback").cookie(alice.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(openApi().isValid(openApiValidator));

    mvc.perform(get("/api/v1/feedback").cookie(bob.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1));
  }

  // ---------------- GET /api/v1/feedback/{feedbackId} ----------------

  @Test
  void getById_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/feedback/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
  }

  @Test
  void getById_returns404_whenIdMissing() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/feedback/" + UUID.randomUUID()).cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/feedback-entry-not-found"));
  }

  @Test
  void getById_returns404_whenOtherUsersEntry() throws Exception {
    AuthedUser alice = registerUser();
    AuthedUser bob = registerUser();

    MvcResult posted =
        mvc.perform(
                post("/api/v1/feedback")
                    .cookie(alice.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(recipeDetailRequest("a"))))
            .andExpect(status().isAccepted())
            .andReturn();
    String feedbackId =
        objectMapper.readTree(posted.getResponse().getContentAsString()).get("feedbackId").asText();

    mvc.perform(get("/api/v1/feedback/" + feedbackId).cookie(bob.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void getById_returns200_andHydratedDto_forOwner() throws Exception {
    AuthedUser user = registerUser();

    MvcResult posted =
        mvc.perform(
                post("/api/v1/feedback")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(recipeDetailRequest("hello"))))
            .andExpect(status().isAccepted())
            .andReturn();
    String feedbackId =
        objectMapper.readTree(posted.getResponse().getContentAsString()).get("feedbackId").asText();

    mvc.perform(get("/api/v1/feedback/" + feedbackId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(feedbackId))
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.text").value("hello"))
        // The 01c FeedbackClassificationListener fires AFTER_COMMIT on a background thread and
        // may have flipped status before this GET races to read. Either side of the listener is
        // a valid post-commit observation; assert any reachable state from the submission flow.
        // (Round-8 retro: async-listener test-assertion pattern.)
        .andExpect(
            jsonPath("$.submissionStatus")
                .value(
                    in(
                        java.util.List.of(
                            "RECEIVED",
                            "CLASSIFYING",
                            "CLASSIFIED",
                            "CLARIFICATION_PENDING",
                            "ROUTED",
                            "PARTIALLY_FAILED",
                            "FAILED"))))
        .andExpect(jsonPath("$.routes").isArray())
        .andExpect(jsonPath("$.routes.length()").value(0))
        .andExpect(jsonPath("$.context.screen").value("RECIPE_DETAIL"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class FeedbackEventCaptureConfig {
    @Bean
    FeedbackEventCapture feedbackEventCapture() {
      return new FeedbackEventCapture();
    }
  }

  static class FeedbackEventCapture {
    private final List<FeedbackSubmittedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(FeedbackSubmittedEvent event) {
      events.add(event);
    }

    public List<FeedbackSubmittedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
