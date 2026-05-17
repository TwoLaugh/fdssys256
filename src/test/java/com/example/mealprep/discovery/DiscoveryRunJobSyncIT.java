package com.example.mealprep.discovery;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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

/**
 * Testcontainers IT for {@code POST /api/v1/discovery/admin/jobs/sync}. Exercises: trigger
 * validation (422), a fast COLD_START job reaching a terminal state within the deadline (200), a
 * slow job exceeding the deadline non-strict (200 + RUNNING) and strict (408), an all-sources-down
 * job (502), and waiter-map hygiene.
 *
 * <p>The async {@code DiscoveryJobRunner} is LIVE here. Sync ITs MUST drive everything through the
 * endpoint (the whole point is the sync coordination); the wave-3 "seed directly via repo" rule
 * applies to <em>state-contract</em> tests, not the sync flow which depends on the runner running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  DiscoveryRunJobSyncIT.SyncSourcesConfig.class
})
@ActiveProfiles("test")
class DiscoveryRunJobSyncIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private DiscoverySourceRepository sourceRepository;
  @Autowired private DiscoveryJobRepository jobRepository;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM discovery_scrape_log");
    jdbcTemplate.update("DELETE FROM discovery_jobs");
    jdbcTemplate.update("DELETE FROM discovery_sources");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "sync-" + AuthTestData.shortId();
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

  private void seedSource(String key) {
    DiscoverySource src = DiscoveryTestData.sampleSource(key);
    src.setEnabled(true);
    sourceRepository.saveAndFlush(src);
  }

  private StartDiscoveryJobRequest coldStart(List<String> sourceKeys) {
    return new StartDiscoveryJobRequest(
        DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), sourceKeys, null);
  }

  // ---- 422: non-COLD_START trigger ----

  @Test
  void sync_nonColdStartTrigger_returns422() throws Exception {
    AuthedUser user = registerUser();
    seedSource("sync_fast");

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.USER_INITIATED,
            5,
            DiscoveryTestData.sampleConstraints(),
            List.of("sync_fast"),
            null);

    mvc.perform(
            post("/api/v1/discovery/admin/jobs/sync")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/discovery-constraint-invalid"));
  }

  // ---- 200: fast COLD_START reaches a terminal state ----

  @Test
  void sync_fastColdStart_returns200_terminalDto() throws Exception {
    AuthedUser user = registerUser();
    seedSource("sync_fast");

    MvcResult res =
        mvc.perform(
                post("/api/v1/discovery/admin/jobs/sync?timeoutSeconds=10")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(coldStart(List.of("sync_fast")))))
            .andExpect(status().isOk())
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
    // Skeleton-mode runner: a working source produces no ingest, so the terminal is FAILED with
    // no failed sources (search succeeded) — the controller maps that to 200 (valid terminal).
    String statusStr = body.get("status").asText();
    assertThat(statusStr)
        .isIn(
            DiscoveryJobStatus.FAILED.name(),
            DiscoveryJobStatus.PARTIAL.name(),
            DiscoveryJobStatus.SUCCEEDED.name());
  }

  // ---- 200 (non-strict timeout) + 408 (strict timeout) ----

  @Test
  void sync_slowJob_nonStrictTimeout_returns200_runningDto_jobContinues() throws Exception {
    AuthedUser user = registerUser();
    seedSource("sync_slow");

    MvcResult res =
        mvc.perform(
                post("/api/v1/discovery/admin/jobs/sync?timeoutSeconds=1")
                    .cookie(user.cookie())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(coldStart(List.of("sync_slow")))))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
    UUID jobId = UUID.fromString(body.get("id").asText());
    assertThat(body.get("status").asText()).isEqualTo(DiscoveryJobStatus.RUNNING.name());

    // The job continues in the background and eventually finalises.
    DiscoveryJobStatus eventual = pollUntilTerminal(jobId, Duration.ofSeconds(20));
    assertThat(eventual)
        .isIn(DiscoveryJobStatus.FAILED, DiscoveryJobStatus.PARTIAL, DiscoveryJobStatus.SUCCEEDED);
  }

  private DiscoveryJobStatus pollUntilTerminal(UUID jobId, Duration atMost)
      throws InterruptedException {
    long deadline = System.nanoTime() + atMost.toNanos();
    DiscoveryJobStatus status = DiscoveryJobStatus.RUNNING;
    while (System.nanoTime() < deadline) {
      status = jobRepository.findById(jobId).orElseThrow().getStatus();
      if (status == DiscoveryJobStatus.FAILED
          || status == DiscoveryJobStatus.PARTIAL
          || status == DiscoveryJobStatus.SUCCEEDED) {
        return status;
      }
      Thread.sleep(200);
    }
    return status;
  }

  @Test
  void sync_slowJob_strictTimeout_returns408() throws Exception {
    AuthedUser user = registerUser();
    seedSource("sync_slow");

    mvc.perform(
            post("/api/v1/discovery/admin/jobs/sync?timeoutSeconds=1&strictTimeout=true")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(coldStart(List.of("sync_slow")))))
        .andExpect(status().isRequestTimeout())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/discovery-job-timeout"));
  }

  // ---- 502: all sources unavailable ----

  @Test
  void sync_allSourcesDown_returns502() throws Exception {
    AuthedUser user = registerUser();
    seedSource("sync_down");

    mvc.perform(
            post("/api/v1/discovery/admin/jobs/sync?timeoutSeconds=10")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(coldStart(List.of("sync_down")))))
        .andExpect(status().isBadGateway())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/discovery-all-sources-unavailable"));
  }

  // ---- 400: timeoutSeconds above the @Max cap ----

  @Test
  void sync_timeoutAboveCap_returns400() throws Exception {
    AuthedUser user = registerUser();
    seedSource("sync_fast");

    mvc.perform(
            post("/api/v1/discovery/admin/jobs/sync?timeoutSeconds=500")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(coldStart(List.of("sync_fast")))))
        .andExpect(status().isBadRequest());
  }

  // ---- 401: anonymous ----

  @Test
  void sync_anonymous_returns401() throws Exception {
    mvc.perform(
            post("/api/v1/discovery/admin/jobs/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(coldStart(List.of("sync_fast")))))
        .andExpect(status().isUnauthorized());
  }

  // ---- waiter-map hygiene ----

  @Test
  void sync_consecutiveCalls_doNotLeakWaiters_bothSucceed() throws Exception {
    AuthedUser user = registerUser();
    seedSource("sync_fast");

    // Two consecutive sync calls must each complete cleanly. A leaked waiter from call 1 would not
    // break call 2 (different jobId), but a regression in the finally-block unregister would
    // surface
    // as a hang here — the 10s server cap bounds the blast radius. The unit test
    // (DiscoveryRunJobSyncTest) asserts the unregister call directly on every path.
    for (int i = 0; i < 2; i++) {
      mvc.perform(
              post("/api/v1/discovery/admin/jobs/sync?timeoutSeconds=10")
                  .cookie(user.cookie())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(coldStart(List.of("sync_fast")))))
          .andExpect(status().isOk());
    }
  }

  /**
   * Test source beans. Anonymous {@code DiscoverySource} impls in the test source set — excluded
   * from {@code DiscoveryBoundaryTest}'s source-package rule (it uses {@code DoNotIncludeTests}).
   */
  @TestConfiguration
  static class SyncSourcesConfig {

    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource syncFastSource() {
      return new com.example.mealprep.discovery.domain.service.DiscoverySource() {
        @Override
        public String key() {
          return "sync_fast";
        }

        @Override
        public DiscoverySourceKind kind() {
          return DiscoverySourceKind.SITEMAP;
        }

        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          return List.of(
              new DiscoveryCandidate(
                  "sync_fast", "https://example.test/r/1", "R1", "d", java.util.Map.of()));
        }

        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          return new ParsedRecipe(
              candidate.candidateUrl(),
              "Recipe 1",
              "desc",
              List.of(),
              List.of(),
              new ParsedRecipe.ParsedRecipeMetadata(
                  2, 5, 10, 15, List.of(), "East Asian", List.of("dinner")),
              "jsonld",
              new java.math.BigDecimal("0.90"));
        }
      };
    }

    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource syncSlowSource() {
      return new com.example.mealprep.discovery.domain.service.DiscoverySource() {
        @Override
        public String key() {
          return "sync_slow";
        }

        @Override
        public DiscoverySourceKind kind() {
          return DiscoverySourceKind.SITEMAP;
        }

        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          try {
            Thread.sleep(4_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return List.of(
              new DiscoveryCandidate(
                  "sync_slow", "https://example.test/r/2", "R2", "d", java.util.Map.of()));
        }

        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          return new ParsedRecipe(
              candidate.candidateUrl(),
              "Recipe 2",
              "desc",
              List.of(),
              List.of(),
              new ParsedRecipe.ParsedRecipeMetadata(
                  2, 5, 10, 15, List.of(), "East Asian", List.of("dinner")),
              "jsonld",
              new java.math.BigDecimal("0.90"));
        }
      };
    }

    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource syncDownSource() {
      return new com.example.mealprep.discovery.domain.service.DiscoverySource() {
        @Override
        public String key() {
          return "sync_down";
        }

        @Override
        public DiscoverySourceKind kind() {
          return DiscoverySourceKind.SITEMAP;
        }

        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          throw new DiscoverySourceUnavailableException("sync_down", "simulated outage", null);
        }

        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          throw new DiscoverySourceUnavailableException("sync_down", "simulated outage", null);
        }
      };
    }
  }
}
