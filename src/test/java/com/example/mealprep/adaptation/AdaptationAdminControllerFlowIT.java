package com.example.mealprep.adaptation;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.AdaptationTrace;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full HTTP cycle over {@code AdaptationAdminController} (7 endpoints) and {@code
 * AdapterRunHistoryController} (2 endpoints), driven through the real Spring stack with the
 * deny-by-default auth chain.
 *
 * <p>Auth-scope note (mirrors {@code AdminPlannerDecisionsControllerIT} / the controllers' own
 * javadoc): the project does not enable Spring method-security, so
 * {@code @PreAuthorize("hasRole('ROLE_ADMIN')")} is currently inert — anonymous &rarr; 401, but
 * every authenticated user passes the (no-op) role gate. A genuine non-admin&rarr;403 is not
 * assertable today; we pin the current behaviour (401 anon, 200 authed).
 *
 * <p>Jobs and traces are seeded directly through the repositories (no async pipeline race, wave-3
 * 0012); the {@code adaptation_traces.job_id} unique FK rides a real parent job (round-6). Time
 * windows anchor to {@code Instant.now()} (no hard-coded dates). Children deleted before parents.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class AdaptationAdminControllerFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private AuthProperties authProperties;
  @Autowired private AdaptationJobRepository jobRepository;
  @Autowired private AdaptationTraceRepository traceRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM adaptation_traces");
    jdbcTemplate.update("DELETE FROM adaptation_jobs");
    jdbcTemplate.update("DELETE FROM auth_sessions");
    jdbcTemplate.update("DELETE FROM auth_login_attempts");
    jdbcTemplate.update("DELETE FROM auth_users");
  }

  // ---------------- auth ----------------

  @Test
  void getJob_anonymous_returns401() throws Exception {
    mvc.perform(get("/api/v1/adaptation/jobs/{jobId}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void runHistory_anonymous_returns401() throws Exception {
    mvc.perform(
            get("/api/v1/adaptation/run-history")
                .param("source", "FEEDBACK")
                .param("from", Instant.now().minus(1, ChronoUnit.DAYS).toString())
                .param("to", Instant.now().toString()))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- AdaptationAdminController read surface ----------------

  @Test
  void getJob_existing_returns200_andContract() throws Exception {
    Cookie cookie = registerAndLogin();
    AdaptationJob job = seedJob(JobStatus.DONE, JobSource.FEEDBACK, JobPriority.SYNC);

    mvc.perform(get("/api/v1/adaptation/jobs/{jobId}", job.getId()).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(job.getId().toString())))
        .andExpect(jsonPath("$.status", is("DONE")))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getJob_missing_returns404_problemJson() throws Exception {
    Cookie cookie = registerAndLogin();
    mvc.perform(get("/api/v1/adaptation/jobs/{jobId}", UUID.randomUUID()).cookie(cookie))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void getJobTrace_existing_returns200() throws Exception {
    Cookie cookie = registerAndLogin();
    AdaptationJob job = seedJob(JobStatus.DONE, JobSource.FEEDBACK, JobPriority.SYNC);
    seedTrace(job, OutcomeKind.PENDING_CREATED);

    mvc.perform(get("/api/v1/adaptation/jobs/{jobId}/trace", job.getId()).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId", is(job.getId().toString())))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getJobTrace_missing_returns404() throws Exception {
    Cookie cookie = registerAndLogin();
    AdaptationJob job = seedJob(JobStatus.RUNNING, JobSource.FEEDBACK, JobPriority.SYNC);

    mvc.perform(get("/api/v1/adaptation/jobs/{jobId}/trace", job.getId()).cookie(cookie))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void getRecipeJobs_returns200_paged() throws Exception {
    Cookie cookie = registerAndLogin();
    AdaptationJob job = seedJob(JobStatus.PENDING, JobSource.IMPORT, JobPriority.ASYNC);

    mvc.perform(
            get("/api/v1/adaptation/recipes/{recipeId}/jobs", job.getRecipeId())
                .cookie(cookie)
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getRecipeTraces_returns200_paged() throws Exception {
    Cookie cookie = registerAndLogin();
    AdaptationJob job = seedJob(JobStatus.DONE, JobSource.FEEDBACK, JobPriority.SYNC);
    seedTrace(job, OutcomeKind.NO_OP);

    mvc.perform(
            get("/api/v1/adaptation/recipes/{recipeId}/traces", job.getRecipeId()).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getRecipeJobs_invalidSize_returns400() throws Exception {
    Cookie cookie = registerAndLogin();
    mvc.perform(
            get("/api/v1/adaptation/recipes/{recipeId}/jobs", UUID.randomUUID())
                .cookie(cookie)
                .param("size", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sweepExpiredPending_returns200_withTouchedCount() throws Exception {
    Cookie cookie = registerAndLogin();
    mvc.perform(post("/api/v1/adaptation/admin/sweep-expired-pending").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.touched", is(0)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void retryFailedJob_failedJob_returns200() throws Exception {
    Cookie cookie = registerAndLogin();
    AdaptationJob failed = seedJob(JobStatus.FAILED, JobSource.FEEDBACK, JobPriority.SYNC);

    mvc.perform(
            post("/api/v1/adaptation/admin/retry-failed-job")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + failed.getId() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("PENDING")))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void retryFailedJob_nonFailedJob_returns409_problemJson() throws Exception {
    Cookie cookie = registerAndLogin();
    AdaptationJob done = seedJob(JobStatus.DONE, JobSource.FEEDBACK, JobPriority.SYNC);

    mvc.perform(
            post("/api/v1/adaptation/admin/retry-failed-job")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + done.getId() + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void retryFailedJob_missingJob_returns404() throws Exception {
    Cookie cookie = registerAndLogin();
    mvc.perform(
            post("/api/v1/adaptation/admin/retry-failed-job")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void retryFailedJob_blankBody_returns400() throws Exception {
    Cookie cookie = registerAndLogin();
    mvc.perform(
            post("/api/v1/adaptation/admin/retry-failed-job")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getPromptVersionTraces_returns200_paged() throws Exception {
    Cookie cookie = registerAndLogin();
    AdaptationJob job = seedJob(JobStatus.DONE, JobSource.FEEDBACK, JobPriority.SYNC);
    seedTrace(job, OutcomeKind.NO_OP);

    // {name} is a single path segment — a slash in the prompt-template name would be parsed as a
    // path separator and miss the route (so the seeded name is slash-free here).
    mvc.perform(
            get(
                    "/api/v1/adaptation/admin/prompt-versions/{name}/{version}/traces",
                    "recipe-adaptation",
                    "v1")
                .cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- AdapterRunHistoryController ----------------

  @Test
  void runHistory_filtersBySourceAndWindow_returns200() throws Exception {
    Cookie cookie = registerAndLogin();
    seedJob(JobStatus.DONE, JobSource.FEEDBACK, JobPriority.SYNC);

    mvc.perform(
            get("/api/v1/adaptation/run-history")
                .cookie(cookie)
                .param("source", "FEEDBACK")
                .param("from", Instant.now().minus(1, ChronoUnit.DAYS).toString())
                .param("to", Instant.now().plus(1, ChronoUnit.DAYS).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void runHistory_missingRequiredParam_returns400() throws Exception {
    Cookie cookie = registerAndLogin();
    mvc.perform(get("/api/v1/adaptation/run-history").cookie(cookie).param("source", "FEEDBACK"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void runHistory_byPromptVersion_returns200_paged() throws Exception {
    Cookie cookie = registerAndLogin();
    AdaptationJob job = seedJob(JobStatus.DONE, JobSource.PLAN_TIME, JobPriority.SYNC);
    seedTrace(job, OutcomeKind.NO_OP);

    mvc.perform(
            get("/api/v1/adaptation/run-history/by-prompt-version")
                .cookie(cookie)
                .param("name", "recipe-adaptation")
                .param("version", "v1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- helpers ----------------

  private Cookie registerAndLogin() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("admin-adp-" + AuthTestData.shortId());
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return result.getResponse().getCookie(authProperties.cookieName());
  }

  private AdaptationJob seedJob(JobStatus status, JobSource source, JobPriority priority) {
    return jobRepository.saveAndFlush(
        AdaptationJob.builder()
            .id(UUID.randomUUID())
            .recipeId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .catalogue(Catalogue.USER)
            .source(source)
            .priority(priority)
            .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
            .status(status)
            .inputs(JsonNodeFactory.instance.objectNode())
            .traceId(UUID.randomUUID())
            .enqueuedAt(Instant.now())
            .build());
  }

  private void seedTrace(AdaptationJob job, OutcomeKind outcome) {
    traceRepository.saveAndFlush(
        AdaptationTrace.builder()
            .id(UUID.randomUUID())
            .jobId(job.getId())
            .recipeId(job.getRecipeId())
            .traceId(job.getTraceId())
            .source(job.getSource())
            .promptTemplateName("recipe-adaptation")
            .promptTemplateVersion("v1")
            .inputsSnapshot(JsonNodeFactory.instance.objectNode())
            .candidates(JsonNodeFactory.instance.objectNode())
            .classificationDecision(AdaptationClassification.VERSION)
            .finalDiff(JsonNodeFactory.instance.objectNode())
            .confidence(new BigDecimal("0.800"))
            .characterPreservationScore(new BigDecimal("0.900"))
            .validationResult(ValidationResult.PASSED)
            .outcomeKind(outcome)
            .outcomeTargetId(UUID.randomUUID())
            .durationMs(120)
            .createdAt(Instant.now())
            .build());
  }
}
