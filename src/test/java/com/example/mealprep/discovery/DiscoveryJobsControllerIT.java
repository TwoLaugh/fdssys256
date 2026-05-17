package com.example.mealprep.discovery;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.event.DiscoveryJobStartedEvent;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Full HTTP flow over the discovery-job controller. Verifies 202/400/401/404/422 surfaces, the
 * {@code DiscoveryJobStartedEvent} AFTER_COMMIT publication, and OpenAPI contract correctness.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  DiscoveryJobsControllerIT.JobEventCaptureConfig.class
})
@ActiveProfiles("test")
class DiscoveryJobsControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private DiscoveryJobRepository jobRepository;
  @Autowired private DiscoverySourceRepository sourceRepository;
  @Autowired private JobEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM discovery_scrape_log");
    jdbcTemplate.update("DELETE FROM discovery_jobs");
    jdbcTemplate.update("DELETE FROM discovery_sources");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "disc-" + AuthTestData.shortId();
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

  private DiscoverySource seedSource(String key, boolean enabled) {
    DiscoverySource src = DiscoveryTestData.sampleSource(key);
    src.setEnabled(enabled);
    return sourceRepository.saveAndFlush(src);
  }

  // ---------------- start ----------------

  @Test
  void start_returns202_persistsQueuedJob_publishesEvent() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);
    seedSource("src_b", true);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    MvcResult result =
        mvc.perform(
                post("/api/v1/discovery/jobs")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.requestedCount").value(5))
            .andExpect(header().exists("Location"))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    UUID jobId = UUID.fromString(body.get("id").asText());
    assertThat(jobRepository.findById(jobId)).isPresent();
    assertThat(eventCapture.events()).hasSize(1);
    assertThat(eventCapture.events().get(0).jobId()).isEqualTo(jobId);
  }

  @Test
  void start_withSpecificSourceKeys_persistsSubset() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);
    seedSource("src_b", true);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            List.of("src_a"),
            null);

    mvc.perform(
            post("/api/v1/discovery/jobs")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.sourcesRequested[0]").value("src_a"))
        .andExpect(jsonPath("$.sourcesRequested.length()").value(1));
  }

  @Test
  void start_unknownSourceKey_returns422() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            List.of("src_unknown"),
            null);

    mvc.perform(
            post("/api/v1/discovery/jobs")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/discovery-constraint-invalid"));
  }

  @Test
  void start_requestedCountTooLow_returns400() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 0, DiscoveryTestData.sampleConstraints(), null, null);

    mvc.perform(
            post("/api/v1/discovery/jobs")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void start_unsupportedSchemaVersion_returns400() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);

    DiscoveryConstraints bad =
        new DiscoveryConstraints(99, null, List.of("dinner"), null, null, null, null, null);
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(DiscoveryJobTrigger.COLD_START, 5, bad, null, null);

    mvc.perform(
            post("/api/v1/discovery/jobs")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void start_perSourceQuotaExceedsTotal_returns400() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);

    DiscoveryConstraints bad =
        new DiscoveryConstraints(1, null, List.of("dinner"), null, null, null, null, 100);
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(DiscoveryJobTrigger.COLD_START, 5, bad, null, null);

    mvc.perform(
            post("/api/v1/discovery/jobs")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void start_anonymous_returns401() throws Exception {
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    mvc.perform(
            post("/api/v1/discovery/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- list / get / scrape-log ----------------

  @Test
  void list_returnsOwnJobs_pageShapeFlat() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);
    seedAndPostJob(user);

    mvc.perform(get("/api/v1/discovery/jobs").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.totalElements").exists())
        .andExpect(jsonPath("$.size").exists())
        .andExpect(jsonPath("$.number").exists())
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void list_sizeTooLarge_returns400() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/discovery/jobs?size=101").cookie(user.cookie()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getById_otherUsersJob_returns404() throws Exception {
    AuthedUser alice = registerUser();
    AuthedUser bob = registerUser();
    seedSource("src_a", true);
    UUID aliceJobId = seedAndPostJob(alice);

    mvc.perform(get("/api/v1/discovery/jobs/" + aliceJobId).cookie(bob.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void getById_unknown_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/discovery/jobs/" + UUID.randomUUID()).cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void scrapeLog_unknownJob_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/discovery/jobs/" + UUID.randomUUID() + "/scrape-log")
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void scrapeLog_emptyForNewJob_returns200() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);
    UUID jobId = seedAndPostJob(user);

    mvc.perform(get("/api/v1/discovery/jobs/" + jobId + "/scrape-log").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  // ---------------- cancel ----------------

  @Test
  void cancel_queuedJob_flipsToFailed_returns200() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);
    // Seed directly as QUEUED — POSTing would trigger the async runner which moves it to RUNNING
    // before cancel reads it (then cancel takes the RUNNING branch and the job stays RUNNING).
    UUID jobId = seedJobRow(user, DiscoveryJobStatus.QUEUED);

    mvc.perform(post("/api/v1/discovery/jobs/" + jobId + "/cancel").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.errorSummary").value("cancelled by user"));
  }

  @Test
  void cancel_unknownJob_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            post("/api/v1/discovery/jobs/" + UUID.randomUUID() + "/cancel").cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void cancel_terminalJob_returns422() throws Exception {
    AuthedUser user = registerUser();
    seedSource("src_a", true);
    // Seed directly as terminal — POSTing would trigger the async runner which races the
    // jdbcTemplate UPDATE (runner can overwrite SUCCEEDED back to RUNNING before cancel reads).
    UUID jobId = seedJobRow(user, DiscoveryJobStatus.SUCCEEDED);

    mvc.perform(post("/api/v1/discovery/jobs/" + jobId + "/cancel").cookie(user.cookie()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/discovery-job-already-terminal"));
  }

  @Test
  void cancel_runningJob_returns200_setsCancellationFlag() throws Exception {
    // 01d: cancel of a RUNNING job sets the in-memory flag and returns the current DTO (still
    // RUNNING). The runner sees the flag at its next iteration and finalises FAILED.
    AuthedUser user = registerUser();
    seedSource("src_a", true);
    // Seed directly as RUNNING — seedAndPostJob would POST and spawn the live async
    // DiscoveryJobRunner on a background thread that outlives this test, then write
    // discovery_scrape_log rows / saveAndFlush the DiscoveryJob AFTER @AfterEach deletes them
    // -> FK violation + StaleObjectStateException reddening main. This test only asserts the
    // cancel HTTP contract (200 + still-RUNNING DTO); it does not exercise the runner, so a
    // direct RUNNING seed covers it deterministically (same pattern as the other cancel tests).
    UUID jobId = seedJobRow(user, DiscoveryJobStatus.RUNNING);

    mvc.perform(post("/api/v1/discovery/jobs/" + jobId + "/cancel").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"));
  }

  // ---------------- helpers ----------------

  /**
   * Inserts a DiscoveryJob row directly in the given status WITHOUT going through POST /jobs. The
   * POST path publishes an event the async DiscoveryJobRunner consumes, which would race the seeded
   * state (a QUEUED job becomes RUNNING before the test's cancel call). A directly-saved row is
   * never picked up — the runner only processes POST-triggered jobs, and the @Scheduled orphan
   * sweep only touches long-RUNNING jobs after a 5-minute initial delay. This keeps the
   * cancel-contract tests deterministic.
   */
  private UUID seedJobRow(AuthedUser user, DiscoveryJobStatus status) {
    DiscoveryJob job = DiscoveryTestData.sampleJob(user.userId());
    job.setStatus(status);
    return jobRepository.save(job).getId();
  }

  private UUID seedAndPostJob(AuthedUser user) throws Exception {
    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);
    MvcResult result =
        mvc.perform(
                post("/api/v1/discovery/jobs")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andReturn();
    return UUID.fromString(
        objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
  }

  // Confirms the event publishes (AFTER_COMMIT) so 01d's runner subscribes correctly.
  static class JobEventCapture {
    private final List<DiscoveryJobStartedEvent> captured = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    public void onStarted(DiscoveryJobStartedEvent event) {
      captured.add(event);
    }

    public List<DiscoveryJobStartedEvent> events() {
      return List.copyOf(captured);
    }

    public void clear() {
      captured.clear();
    }

    @SuppressWarnings("unused")
    public DiscoveryJobStatus unused() {
      return DiscoveryJobStatus.QUEUED;
    }
  }

  @TestConfiguration
  static class JobEventCaptureConfig {
    @Bean
    JobEventCapture jobEventCapture() {
      return new JobEventCapture();
    }
  }
}
