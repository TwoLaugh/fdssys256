package com.example.mealprep.feedback;

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
import com.example.mealprep.feedback.api.dto.CorrectionRequest;
import com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.MisclassificationCorrectionRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.event.FeedbackMisclassificationCorrectedEvent;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.example.mealprep.feedback.spi.PreferenceFeedbackReverter;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.example.mealprep.feedback.spi.RecipeFeedbackHandler;
import com.example.mealprep.feedback.spi.RevertContext;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP IT for the correction flow. Seeds the entry + an APPLIED PREFERENCE routing row
 * directly via the repos (no POST → no async classifier racing the seed — wave-3 retro), then
 * {@code POST /correct} to PROVISIONS and asserts: original CORRECTED_AWAY + supersededBy set; a
 * new APPLIED PROVISIONS row; the {@code MisclassificationCorrection} row carries ground truth with
 * {@code replayStatus=APPLIED}; entry {@code submissionStatus=CORRECTED}; the recording preference
 * reverter (overriding the Noop) was invoked; {@code FeedbackMisclassificationCorrectedEvent}
 * published once; and {@code GET /corrections} lists it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  MisclassificationCorrectionIT.CorrectionItConfig.class
})
@ActiveProfiles("test")
class MisclassificationCorrectionIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private RoutingLogRepository routingLogRepository;
  @Autowired private MisclassificationCorrectionRepository correctionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private CorrectedEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM feedback_misclassification_corrections");
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
    CorrectionItConfig.REVERT_CTX.set(null);
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("alice-" + AuthTestData.shortId());
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

  UUID seedEntryWithPreferenceRoute(UUID userId) {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "no more cream sauces");
    entry.setSubmissionStatus(SubmissionStatus.ROUTED);
    entry.setClassificationAttempts(1);
    entry.getRoutingLog().clear();
    var row =
        FeedbackTestData.routingLogEntry(entry, Destination.PREFERENCE, RoutingStatus.APPLIED);
    entry.getRoutingLog().add(row);
    entryRepository.save(entry);
    return entry.getId();
  }

  @Test
  void correctPreferenceToProvisions_happyPath() throws Exception {
    AuthedUser user = registerUser();
    UUID feedbackId = seedEntryWithPreferenceRoute(user.userId());
    UUID originalRoutingId =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId).get(0).getId();

    CorrectionRequest req =
        new CorrectionRequest(Destination.PROVISIONS, "this is a stocking concern");

    mvc.perform(
            post(
                    "/api/v1/feedback/{feedbackId}/routes/{routingId}/correct",
                    feedbackId,
                    originalRoutingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.feedbackId").value(feedbackId.toString()))
        .andExpect(jsonPath("$.submissionStatus").value("CORRECTED"))
        .andExpect(openApi().isValid(openApiValidator));

    var rows = routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    assertThat(rows).hasSize(2);
    var original =
        rows.stream().filter(r -> r.getId().equals(originalRoutingId)).findFirst().orElseThrow();
    var replay =
        rows.stream().filter(r -> !r.getId().equals(originalRoutingId)).findFirst().orElseThrow();
    assertThat(original.getStatus()).isEqualTo(RoutingStatus.CORRECTED_AWAY);
    assertThat(original.getSupersededById()).isEqualTo(replay.getId());
    assertThat(replay.getDestination()).isEqualTo(Destination.PROVISIONS);
    assertThat(replay.getStatus()).isEqualTo(RoutingStatus.APPLIED);

    var corrections =
        correctionRepository.findByFeedbackEntryUserIdOrderByOccurredAtDesc(
            user.userId(), org.springframework.data.domain.PageRequest.of(0, 10));
    assertThat(corrections.getContent()).hasSize(1);
    var corr = corrections.getContent().get(0);
    assertThat(corr.getOriginalDestination()).isEqualTo(Destination.PREFERENCE);
    assertThat(corr.getCorrectedDestination()).isEqualTo(Destination.PROVISIONS);
    assertThat(corr.getActorUserId()).isEqualTo(user.userId());
    assertThat(corr.getReplayRoutingId()).isEqualTo(replay.getId());
    assertThat(corr.getReplayStatus()).isEqualTo(CorrectionReplayStatus.APPLIED);

    // Recording preference reverter (overrides Noop) was invoked with the original routing.
    RevertContext captured = CorrectionItConfig.REVERT_CTX.get();
    assertThat(captured).isNotNull();
    assertThat(captured.originalRoutingId()).isEqualTo(originalRoutingId);

    assertThat(eventCapture.events).hasSize(1);
    FeedbackMisclassificationCorrectedEvent ev = eventCapture.events.get(0);
    assertThat(ev.feedbackId()).isEqualTo(feedbackId);
    assertThat(ev.originalDestination()).isEqualTo(Destination.PREFERENCE);
    assertThat(ev.correctedDestination()).isEqualTo(Destination.PROVISIONS);

    mvc.perform(get("/api/v1/feedback/corrections").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].correctedDestination").value("PROVISIONS"))
        .andExpect(jsonPath("$.content[0].replayStatus").value("APPLIED"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void correctToSameDestination_returns422() throws Exception {
    AuthedUser user = registerUser();
    UUID feedbackId = seedEntryWithPreferenceRoute(user.userId());
    UUID routingId =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId).get(0).getId();

    mvc.perform(
            post("/api/v1/feedback/{feedbackId}/routes/{routingId}/correct", feedbackId, routingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.PREFERENCE, null))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/invalid-correction-target"));
  }

  @Test
  void correctUnknownFeedback_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post(
                    "/api/v1/feedback/{feedbackId}/routes/{routingId}/correct",
                    UUID.randomUUID(),
                    UUID.randomUUID())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.PROVISIONS, null))))
        .andExpect(status().isNotFound());
  }

  @Test
  void correct_anonymous_returns401() throws Exception {
    mvc.perform(
            post(
                    "/api/v1/feedback/{feedbackId}/routes/{routingId}/correct",
                    UUID.randomUUID(),
                    UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.PROVISIONS, null))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void corrections_anonymous_returns401() throws Exception {
    mvc.perform(get("/api/v1/feedback/corrections")).andExpect(status().isUnauthorized());
  }

  // ---------------- wiring ----------------

  static class CorrectedEventCapture {
    final List<FeedbackMisclassificationCorrectedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(FeedbackMisclassificationCorrectedEvent ev) {
      events.add(ev);
    }

    void clear() {
      events.clear();
    }
  }

  @TestConfiguration
  static class CorrectionItConfig {

    static final AtomicReference<RevertContext> REVERT_CTX = new AtomicReference<>();

    @Bean
    CorrectedEventCapture correctedEventCapture() {
      return new CorrectedEventCapture();
    }

    /** Recording reverter overriding the Noop preference reverter. */
    @Bean
    PreferenceFeedbackReverter recordingPreferenceReverter() {
      return REVERT_CTX::set;
    }

    @Bean
    @Primary
    ProvisionsFeedbackBridge provisionsFeedbackBridge() {
      return input -> new ProvisionsFeedbackBridge.Result("provisions-ok", Map.of());
    }

    @Bean
    @Primary
    PreferenceFeedbackBridge preferenceFeedbackBridge() {
      return input -> new PreferenceFeedbackBridge.Result("preference-ok", Map.of());
    }

    @Bean
    @Primary
    NutritionFeedbackBridge nutritionFeedbackBridge() {
      return input -> new NutritionFeedbackBridge.Result("nutrition-ok", Map.of());
    }

    @Bean
    @Primary
    RecipeFeedbackHandler recipeFeedbackHandler() {
      return input ->
          new RecipeFeedbackHandler.Result(
              false, "recipe-ok", Map.of("recipeId", input.recipeId().toString()));
    }
  }
}
