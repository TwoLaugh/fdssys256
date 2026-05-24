package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.NutritionFeedbackBridge;
import com.example.mealprep.feedback.spi.PreferenceFeedbackBridge;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.example.mealprep.feedback.spi.RecipeFeedbackHandler;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.TasteProfileDelta;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.domain.repository.TasteProfileAuditLogRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * Cross-module IT for the misclassification reverters (feedback-01h). Exercises the real {@link
 * com.example.mealprep.preference.spi.internal.PreferenceFeedbackReverterImpl} end-to-end against
 * real Postgres: a PREFERENCE feedback applies a taste-profile delta batch (v2, keyed by the {@code
 * feedback-<feedbackId>} origin trace), the user corrects it to PROVISIONS, and the preference
 * reverter rolls the taste profile back to the pre-apply document — committed ATOMICALLY with the
 * {@code CORRECTED_AWAY} flip + the {@code MisclassificationCorrection} row (decision-log 0010: the
 * revert joins the correction's REQUIRED tx, not REQUIRES_NEW).
 *
 * <p>Only the bridges are stubbed ({@code @Primary} fakes) so the synthetic PROVISIONS replay
 * succeeds without seeding provisions state; the PREFERENCE reverter is the REAL bean (NOT
 * overridden here) so the rollback actually runs. DB-state is asserted via repository reads on the
 * test thread — the taste-profile document is an eager JSONB column, so no lazy collection escapes
 * a closed session. Cleanup deletes FK-children before parents (per the 01h IT-pitfall checklist).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MisclassificationCorrectionRevertIT.RevertItConfig.class})
@ActiveProfiles("test")
class MisclassificationCorrectionRevertIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private RoutingLogRepository routingLogRepository;
  @Autowired private MisclassificationCorrectionRepository correctionRepository;
  @Autowired private TasteProfileUpdateService tasteProfileUpdateService;
  @Autowired private TasteProfileQueryService tasteProfileQueryService;
  @Autowired private TasteProfileRepository tasteProfileRepository;
  @Autowired private TasteProfileVersionRepository versionRepository;
  @Autowired private TasteProfileAuditLogRepository auditLogRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;

  @AfterEach
  void cleanup() {
    // FK-children before parents (feedback side).
    jdbcTemplate.update("DELETE FROM feedback_misclassification_corrections");
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
    // Preference side: audit + version snapshots FK→profile, delete before the profile.
    auditLogRepository.deleteAll();
    versionRepository.deleteAll();
    tasteProfileRepository.deleteAll();
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("bob-" + AuthTestData.shortId());
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

  /**
   * Apply one delta batch attributed to {@code feedbackId}, producing document version 2 whose
   * version snapshot is keyed by {@code feedback-<feedbackId>}. Returns the origin trace the
   * preference reverter resolves the snapshot by.
   */
  private String applyPreferenceDelta(UUID userId, UUID feedbackId) {
    tasteProfileUpdateService.initialise(userId); // v1
    String trace = "feedback-" + feedbackId;
    ObjectNode item = objectMapper.createObjectNode();
    item.put("item", "coriander");
    item.put("evidenceCount", 3);
    item.put("lastSignal", "2026-05-01");
    item.put("source", "FEEDBACK");
    tasteProfileUpdateService.applyDeltas(
        userId,
        new ApplyTasteProfileDeltasRequest(
            List.of(new TasteProfileDelta.Add("ingredientPreferences.disliked", item)),
            TasteProfileTrigger.BATCH,
            trace,
            trace,
            "cheap")); // v2
    return trace;
  }

  /** Seed a feedback entry with an APPLIED PREFERENCE routing carrying the origin trace handle. */
  private UUID seedPreferenceRouting(UUID userId, UUID feedbackId, String originTrace) {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, "no more coriander");
    entry.setId(feedbackId);
    entry.setSubmissionStatus(SubmissionStatus.ROUTED);
    entry.setClassificationAttempts(1);
    entry.getRoutingLog().clear();
    RoutingLogEntry row =
        FeedbackTestData.routingLogEntry(entry, Destination.PREFERENCE, RoutingStatus.APPLIED);
    ObjectNode result = objectMapper.createObjectNode();
    result.put("status", "DISPATCHED");
    result.put("originTrace", originTrace);
    row.setDestinationResultJson(result);
    entry.getRoutingLog().add(row);
    entryRepository.saveAndFlush(entry);
    // Return the ROUTING-LOG row id (the {routingId} path var the correct endpoint looks up),
    // NOT the feedback entry id — otherwise the route lookup 404s.
    return row.getId();
  }

  @Test
  void correctPreferenceToProvisions_rollsBackTasteProfile_atomicallyWithCorrection()
      throws Exception {
    AuthedUser user = registerUser();
    UUID feedbackId = UUID.randomUUID();
    String trace = applyPreferenceDelta(user.userId(), feedbackId);

    // Pre-condition: the delta bumped the document to v2 (coriander disliked).
    TasteProfileDto before = tasteProfileQueryService.getTasteProfile(user.userId()).orElseThrow();
    assertThat(before.documentVersion()).isEqualTo(2);
    assertThat(before.document().ingredientPreferences().disliked())
        .extracting(
            com.example.mealprep.preference.domain.document.TasteProfileDocument
                    .IngredientPreference
                ::item)
        .contains("coriander");

    UUID routingId = seedPreferenceRouting(user.userId(), feedbackId, trace);

    mvc.perform(
            post("/api/v1/feedback/{feedbackId}/routes/{routingId}/correct", feedbackId, routingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.PROVISIONS, "actually a stocking note"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submissionStatus").value("CORRECTED"));

    // The reverter rolled the taste profile back: the restored document no longer has coriander,
    // and the rollback created a NEW monotonic version (v3, ROLLED_BACK) over v2.
    TasteProfileDto after = tasteProfileQueryService.getTasteProfile(user.userId()).orElseThrow();
    assertThat(after.documentVersion()).isEqualTo(3);
    assertThat(after.document().ingredientPreferences().disliked())
        .extracting(
            com.example.mealprep.preference.domain.document.TasteProfileDocument
                    .IngredientPreference
                ::item)
        .doesNotContain("coriander");
    // v1 + v2 + v3(rollback) snapshots exist.
    assertThat(versionRepository.count()).isEqualTo(3L);

    // ...committed ATOMICALLY with the correction bookkeeping (all present after one tx).
    List<RoutingLogEntry> rows =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId);
    assertThat(rows).hasSize(2);
    RoutingLogEntry original =
        rows.stream().filter(r -> r.getId().equals(routingId)).findFirst().orElseThrow();
    RoutingLogEntry replay =
        rows.stream().filter(r -> !r.getId().equals(routingId)).findFirst().orElseThrow();
    assertThat(original.getStatus()).isEqualTo(RoutingStatus.CORRECTED_AWAY);
    assertThat(original.getSupersededById()).isEqualTo(replay.getId());
    assertThat(replay.getDestination()).isEqualTo(Destination.PROVISIONS);
    assertThat(replay.getStatus()).isEqualTo(RoutingStatus.APPLIED);

    var corrections =
        correctionRepository.findByFeedbackEntryUserIdOrderByOccurredAtDesc(
            user.userId(), org.springframework.data.domain.PageRequest.of(0, 10));
    assertThat(corrections.getContent()).hasSize(1);
    assertThat(corrections.getContent().get(0).getReplayStatus())
        .isEqualTo(CorrectionReplayStatus.APPLIED);
  }

  @Test
  void correctPreferenceToProvisions_unresolvableVersion_isLogOnly_correctionStillCommits()
      throws Exception {
    AuthedUser user = registerUser();
    UUID feedbackId = UUID.randomUUID();
    // Profile exists at v2, but the seeded routing carries an origin trace with NO matching
    // snapshot — the reverter degrades to log-only and the correction must still record.
    applyPreferenceDelta(user.userId(), feedbackId);
    UUID routingId =
        seedPreferenceRouting(user.userId(), feedbackId, "feedback-" + UUID.randomUUID());

    mvc.perform(
            post("/api/v1/feedback/{feedbackId}/routes/{routingId}/correct", feedbackId, routingId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CorrectionRequest(Destination.PROVISIONS, null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submissionStatus").value("CORRECTED"));

    // No rollback (still v2, coriander present); correction recorded regardless.
    TasteProfileDto after = tasteProfileQueryService.getTasteProfile(user.userId()).orElseThrow();
    assertThat(after.documentVersion()).isEqualTo(2);
    assertThat(versionRepository.count()).isEqualTo(2L);
    var corrections =
        correctionRepository.findByFeedbackEntryUserIdOrderByOccurredAtDesc(
            user.userId(), org.springframework.data.domain.PageRequest.of(0, 10));
    assertThat(corrections.getContent()).hasSize(1);
  }

  // ---------------- wiring ----------------

  @TestConfiguration
  static class RevertItConfig {

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
