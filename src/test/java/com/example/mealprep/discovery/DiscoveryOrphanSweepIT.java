package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.event.DiscoveryJobCompletedEvent;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Testcontainers IT for the real orphan-sweep mechanics behind {@code POST
 * /api/v1/discovery/admin/run-orphan-sweep}. The existing {@code DiscoveryAdminControllerIT} only
 * asserts the empty-DB {@code resumedCount=0} surface; the populated path — {@code
 * DiscoveryJobRunner.doSweepOrphans} → real {@code @Transactional} {@code
 * DiscoveryJobTransitions.finaliseTo} (the {@code jobRepository.findOrphanRunning} JPQL + the
 * saveAndFlush terminal write) → {@code DiscoveryJobCompletedEvent} publish — only runs against a
 * live Postgres and is mocked out in {@code DiscoveryOrphanSweepTest}, so JaCoCo never credits
 * those lines without this IT.
 *
 * <p>Stale RUNNING jobs are seeded DIRECTLY via the repository (never POSTed), so no {@code
 * DiscoveryJobStartedEvent} fires and the live async runner never touches them — the only thing
 * that transitions them is the sweep we explicitly invoke (wave-3 direct-seed rule for
 * state-contract tests). The {@code startedAt} is anchored to {@code Instant.now()} minus a wide
 * margin so the fixture is not a time-bomb.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, DiscoveryOrphanSweepIT.CompletedEventCaptureConfig.class})
@ActiveProfiles("test")
class DiscoveryOrphanSweepIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private DiscoveryJobRepository jobRepository;
  @Autowired private CompletedEventCapture eventCapture;

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
    String username = "sweep-" + AuthTestData.shortId();
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

  /** Inserts a RUNNING job directly (no POST → no event → live runner never claims it). */
  private UUID seedRunningJob(UUID userId, Instant startedAt) {
    DiscoveryJob job = DiscoveryTestData.sampleJob(userId);
    job.setStatus(DiscoveryJobStatus.RUNNING);
    job.setStartedAt(startedAt);
    return jobRepository.save(job).getId();
  }

  @Test
  void runOrphanSweep_staleRunningJob_finalisedFailed_resumedCountOne_completedEventPublished()
      throws Exception {
    AuthedUser user = registerUser();
    // Heartbeat default is 10 minutes; anchor well past it relative to now (not a hardcoded date).
    UUID staleId = seedRunningJob(user.userId(), Instant.now().minus(Duration.ofMinutes(45)));

    mvc.perform(post("/api/v1/discovery/admin/run-orphan-sweep").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resumedCount").value(1));

    DiscoveryJob swept = jobRepository.findById(staleId).orElseThrow();
    assertThat(swept.getStatus()).isEqualTo(DiscoveryJobStatus.FAILED);
    assertThat(swept.getErrorSummary()).isEqualTo("runner crashed; resumed by sweep");
    assertThat(swept.getCompletedAt()).isNotNull();

    assertThat(eventCapture.events())
        .anySatisfy(
            e -> {
              assertThat(e.jobId()).isEqualTo(staleId);
              assertThat(e.terminalStatus()).isEqualTo(DiscoveryJobStatus.FAILED);
            });
  }

  @Test
  void runOrphanSweep_freshRunningJob_leftUntouched_resumedCountZero() throws Exception {
    AuthedUser user = registerUser();
    // Started just now — inside the heartbeat window, must NOT be swept.
    UUID freshId = seedRunningJob(user.userId(), Instant.now());

    mvc.perform(post("/api/v1/discovery/admin/run-orphan-sweep").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resumedCount").value(0));

    assertThat(jobRepository.findById(freshId).orElseThrow().getStatus())
        .isEqualTo(DiscoveryJobStatus.RUNNING);
    assertThat(eventCapture.events()).isEmpty();
  }

  @Test
  void runOrphanSweep_mixedJobs_onlyStaleResumed() throws Exception {
    AuthedUser user = registerUser();
    UUID stale1 = seedRunningJob(user.userId(), Instant.now().minus(Duration.ofMinutes(30)));
    UUID stale2 = seedRunningJob(user.userId(), Instant.now().minus(Duration.ofHours(2)));
    UUID fresh = seedRunningJob(user.userId(), Instant.now());

    mvc.perform(post("/api/v1/discovery/admin/run-orphan-sweep").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resumedCount").value(2));

    assertThat(jobRepository.findById(stale1).orElseThrow().getStatus())
        .isEqualTo(DiscoveryJobStatus.FAILED);
    assertThat(jobRepository.findById(stale2).orElseThrow().getStatus())
        .isEqualTo(DiscoveryJobStatus.FAILED);
    assertThat(jobRepository.findById(fresh).orElseThrow().getStatus())
        .isEqualTo(DiscoveryJobStatus.RUNNING);
  }

  /** Captures the AFTER_COMMIT completed event the sweep publishes per resumed job. */
  static class CompletedEventCapture {
    private final List<DiscoveryJobCompletedEvent> captured = new CopyOnWriteArrayList<>();

    // Plain @EventListener (not @TransactionalEventListener): the sweep's publishJobCompleted
    // fires outside any active transaction (sweepOrphansNow has no @Transactional at the runner
    // level — each finaliseTo is its own short tx that has already committed by publish time), so
    // a tx-bound listener would silently drop the event.
    @EventListener
    public void onCompleted(DiscoveryJobCompletedEvent event) {
      captured.add(event);
    }

    public List<DiscoveryJobCompletedEvent> events() {
      return List.copyOf(captured);
    }

    public void clear() {
      captured.clear();
    }
  }

  @TestConfiguration
  static class CompletedEventCaptureConfig {
    @Bean
    CompletedEventCapture completedEventCapture() {
      return new CompletedEventCapture();
    }
  }
}
