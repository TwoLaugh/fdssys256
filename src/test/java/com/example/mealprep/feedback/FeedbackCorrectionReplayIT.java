package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.feedback.api.dto.CorrectionRequest;
import com.example.mealprep.feedback.domain.entity.CorrectionReplayStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
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
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
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
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full-stack IT for the misclassification-correction <em>replay</em> branches that {@code
 * MisclassificationCorrectionIT} (happy path + 422 same-dest + 404 + 401) leaves uncovered:
 *
 * <ul>
 *   <li>RECIPE-target precondition guard — no recipe attached → 422 {@code
 *       invalid-correction-target} (the {@code validatePreconditions} RECIPE branch).
 *   <li>Already-{@code CORRECTED_AWAY} original → 422 (correction-chain guard).
 *   <li>Unknown {@code routingId} on an owned entry → 404 {@code routing-decision-not-found}.
 *   <li>Replay dispatch rejected by a destination <em>business</em> exception → original still
 *       {@code CORRECTED_AWAY}, replay row {@code FAILED}, correction {@code replayStatus =
 *       DESTINATION_REJECTED}, entry recomputed to {@code FAILED} (the all-active-failed branch of
 *       {@code recomputeSubmissionStatus}), and {@code FeedbackRouterImpl.routeOneForReplay}'s
 *       business-exception classification + {@code CorrectionReplayer.mapReplayStatus} rejection
 *       arm.
 *   <li>Reverter that throws — {@code bestEffortRevert} WARN-and-proceed arm (correction still
 *       succeeds).
 * </ul>
 *
 * <p>State is seeded directly via the repos (no POST → no async classifier racing the seed — wave-3
 * retro). The recording preference reverter and all four bridges carry {@code @Primary} to override
 * the {@code @ConditionalOnMissingBean} Noop SPIs (wave-3 SPI-stand-in trap).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, FeedbackCorrectionReplayIT.ReplayItConfig.class})
@ActiveProfiles("test")
class FeedbackCorrectionReplayIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private RoutingLogRepository routingLogRepository;
  @Autowired private MisclassificationCorrectionRepository correctionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private CorrectedEventCapture eventCapture;
  @Autowired private ThrowingPreferenceReverter throwingReverter;
  @Autowired private FailingProvisionsBridge failingProvisions;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM feedback_misclassification_corrections");
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
    throwingReverter.throwOnNext = false;
    failingProvisions.failOnNext = false;
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

  /** Entry whose UI-context carries NO recipe, plus a single APPLIED PREFERENCE routing row. */
  private UUID seedPreferenceRoute_noRecipeContext(UUID userId) {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "no more cream sauces");
    entry.setUiContext(
        new com.example.mealprep.feedback.domain.document.UiContextDocument(
            com.example.mealprep.feedback.api.dto.Screen.GENERAL, null, null, null, null, null));
    entry.setSubmissionStatus(SubmissionStatus.ROUTED);
    entry.setClassificationAttempts(1);
    entry.getRoutingLog().clear();
    RoutingLogEntry row =
        FeedbackTestData.routingLogEntry(entry, Destination.PREFERENCE, RoutingStatus.APPLIED);
    row.setStructuredPayload(objectMapper.createObjectNode()); // no recipeId in payload either
    entry.getRoutingLog().add(row);
    entryRepository.save(entry);
    return entry.getId();
  }

  private UUID seedPreferenceRoute(UUID userId, RoutingStatus status) {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "no more cream sauces");
    entry.setSubmissionStatus(SubmissionStatus.ROUTED);
    entry.setClassificationAttempts(1);
    entry.getRoutingLog().clear();
    RoutingLogEntry row = FeedbackTestData.routingLogEntry(entry, Destination.PREFERENCE, status);
    entry.getRoutingLog().add(row);
    entryRepository.save(entry);
    return entry.getId();
  }

  private UUID originalRoutingId(UUID feedbackId) {
    return routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId).get(0).getId();
  }

  @Test
  void correctToRecipe_whenNoRecipeAttached_returns422() throws Exception {
    AuthedUser user = registerUser();
    UUID feedbackId = seedPreferenceRoute_noRecipeContext(user.userId());
    UUID routingId = originalRoutingId(feedbackId);

    mvc.perform(
            post("/api/v1/feedback/{f}/routes/{r}/correct", feedbackId, routingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.RECIPE, "should fail"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/invalid-correction-target"));

    // No correction recorded, original routing untouched.
    assertThat(correctionRepository.findAll()).isEmpty();
    RoutingLogEntry original =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId).get(0);
    assertThat(original.getStatus()).isEqualTo(RoutingStatus.APPLIED);
  }

  @Test
  void correctAlreadyCorrectedAwayRouting_returns422() throws Exception {
    AuthedUser user = registerUser();
    UUID feedbackId = seedPreferenceRoute(user.userId(), RoutingStatus.CORRECTED_AWAY);
    UUID routingId = originalRoutingId(feedbackId);

    mvc.perform(
            post("/api/v1/feedback/{f}/routes/{r}/correct", feedbackId, routingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.PROVISIONS, null))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/invalid-correction-target"));
    assertThat(correctionRepository.findAll()).isEmpty();
  }

  @Test
  void correctUnknownRoutingId_onOwnedEntry_returns404() throws Exception {
    AuthedUser user = registerUser();
    UUID feedbackId = seedPreferenceRoute(user.userId(), RoutingStatus.APPLIED);

    mvc.perform(
            post("/api/v1/feedback/{f}/routes/{r}/correct", feedbackId, UUID.randomUUID())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.PROVISIONS, null))))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/routing-decision-not-found"));
  }

  @Test
  void replayRejectedByDestinationBusinessException_recordsDestinationRejected_andFailedEntry()
      throws Exception {
    AuthedUser user = registerUser();
    UUID feedbackId = seedPreferenceRoute(user.userId(), RoutingStatus.APPLIED);
    UUID originalRoutingId = originalRoutingId(feedbackId);
    failingProvisions.failOnNext = true;

    mvc.perform(
            post("/api/v1/feedback/{f}/routes/{r}/correct", feedbackId, originalRoutingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.PROVISIONS, "stocking concern"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.feedbackId").value(feedbackId.toString()))
        // All active rows failed → recomputeSubmissionStatus → FAILED.
        .andExpect(jsonPath("$.submissionStatus").value("FAILED"));

    List<RoutingLogEntry> rows =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    assertThat(rows).hasSize(2);
    RoutingLogEntry original =
        rows.stream().filter(r -> r.getId().equals(originalRoutingId)).findFirst().orElseThrow();
    RoutingLogEntry replay =
        rows.stream().filter(r -> !r.getId().equals(originalRoutingId)).findFirst().orElseThrow();
    assertThat(original.getStatus()).isEqualTo(RoutingStatus.CORRECTED_AWAY);
    assertThat(original.getSupersededById()).isEqualTo(replay.getId());
    assertThat(replay.getDestination()).isEqualTo(Destination.PROVISIONS);
    assertThat(replay.getStatus()).isEqualTo(RoutingStatus.FAILED);
    assertThat(replay.getFailureKind())
        .isEqualTo(
            com.example.mealprep.feedback.domain.entity.RoutingFailureKind.DESTINATION_BUSINESS);

    var corrections =
        correctionRepository.findByFeedbackEntryUserIdOrderByOccurredAtDesc(
            user.userId(), PageRequest.of(0, 10));
    assertThat(corrections.getContent()).hasSize(1);
    var corr = corrections.getContent().get(0);
    assertThat(corr.getReplayRoutingId()).isEqualTo(replay.getId());
    assertThat(corr.getReplayStatus()).isEqualTo(CorrectionReplayStatus.DESTINATION_REJECTED);

    FeedbackEntry reloaded = entryRepository.findById(feedbackId).orElseThrow();
    assertThat(reloaded.getSubmissionStatus()).isEqualTo(SubmissionStatus.FAILED);

    // Correction event still published even though the replay was rejected.
    assertThat(eventCapture.events).hasSize(1);
    FeedbackMisclassificationCorrectedEvent ev = eventCapture.events.get(0);
    assertThat(ev.feedbackId()).isEqualTo(feedbackId);
    assertThat(ev.correctedDestination()).isEqualTo(Destination.PROVISIONS);

    mvc.perform(get("/api/v1/feedback/corrections").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].replayStatus").value("DESTINATION_REJECTED"));
  }

  @Test
  void reverterThrows_correctionStillSucceeds_bestEffortRevertWarnArm() throws Exception {
    AuthedUser user = registerUser();
    UUID feedbackId = seedPreferenceRoute(user.userId(), RoutingStatus.APPLIED);
    UUID originalRoutingId = originalRoutingId(feedbackId);
    throwingReverter.throwOnNext = true;

    mvc.perform(
            post("/api/v1/feedback/{f}/routes/{r}/correct", feedbackId, originalRoutingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.PROVISIONS, "moved"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submissionStatus").value("CORRECTED"));

    assertThat(throwingReverter.invoked).isTrue();
    List<RoutingLogEntry> rows =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    RoutingLogEntry replay =
        rows.stream().filter(r -> !r.getId().equals(originalRoutingId)).findFirst().orElseThrow();
    assertThat(replay.getStatus()).isEqualTo(RoutingStatus.APPLIED);
    var corr =
        correctionRepository
            .findByFeedbackEntryUserIdOrderByOccurredAtDesc(user.userId(), PageRequest.of(0, 10))
            .getContent()
            .get(0);
    assertThat(corr.getReplayStatus()).isEqualTo(CorrectionReplayStatus.APPLIED);
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

  /** Preference reverter that throws on demand to exercise the WARN-and-proceed arm. */
  static class ThrowingPreferenceReverter implements PreferenceFeedbackReverter {
    volatile boolean throwOnNext = false;
    volatile boolean invoked = false;

    @Override
    public void revert(com.example.mealprep.feedback.spi.RevertContext ctx) {
      invoked = true;
      if (throwOnNext) {
        throw new IllegalStateException("simulated reverter failure");
      }
    }
  }

  /** Provisions bridge that throws a destination-business exception on demand. */
  static class FailingProvisionsBridge implements ProvisionsFeedbackBridge {
    volatile boolean failOnNext = false;

    @Override
    public Result applyFeedback(Input input) {
      if (failOnNext) {
        throw new com.example.mealprep.provisions.exception.InventoryItemNotFoundException(
            UUID.randomUUID());
      }
      return new Result("provisions-ok", Map.of());
    }
  }

  @TestConfiguration
  static class ReplayItConfig {

    @Bean
    CorrectedEventCapture correctedEventCapture() {
      return new CorrectedEventCapture();
    }

    @Bean
    @Primary
    PreferenceFeedbackReverter throwingPreferenceReverter() {
      return new ThrowingPreferenceReverter();
    }

    @Bean
    ThrowingPreferenceReverter throwingPreferenceReverterHandle(PreferenceFeedbackReverter b) {
      return (ThrowingPreferenceReverter) b;
    }

    @Bean
    @Primary
    ProvisionsFeedbackBridge failingProvisionsBridge() {
      return new FailingProvisionsBridge();
    }

    @Bean
    FailingProvisionsBridge failingProvisionsBridgeHandle(ProvisionsFeedbackBridge b) {
      return (FailingProvisionsBridge) b;
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
