package com.example.mealprep.adaptation;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.spi.RecipeSubstitutionRecorder;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.example.mealprep.recipe.spi.SaveAdaptedVersionCommand;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Full HTTP cycle over {@code PendingChangesController}'s five endpoints driven through the real
 * Spring stack: deny-by-default auth (401 anon), server-side {@code CurrentUserResolver} ownership
 * (cross-user 404), the accept / reject lifecycle (200 + persisted state + problem+json on the
 * conflict branches), and the paginated recipe history.
 *
 * <p>Rows are seeded directly through the repositories so no async worker pipeline races the
 * assertions (wave-3 retro 0012); the {@code job_id} FK is always satisfied with a real parent job
 * (round-6). The user is registered through the real {@code /api/v1/auth/register} so the
 * authenticated {@code userId} matches the seeded pending-change owner. All four {@code
 * RecipeServiceImpl} SPI interfaces are mocked together (round-6 multi-interface eviction).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class PendingChangesControllerFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private AuthProperties authProperties;
  @Autowired private AdaptationJobRepository jobRepository;
  @Autowired private PendingChangeRepository pendingChangeRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockBean private RecipeWriteApi recipeWriteApi;
  @MockBean private RecipeQueryService recipeQueryService;
  @MockBean private RecipeUpdateService recipeUpdateService;
  @MockBean private RecipeSubstitutionRecorder recipeSubstitutionRecorder;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM adaptation_pending_changes");
    jdbcTemplate.update("DELETE FROM adaptation_jobs");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  // ---------------- auth ----------------

  @Test
  void listForUser_anonymous_returns401() throws Exception {
    mvc.perform(get("/api/v1/adaptation/pending-changes")).andExpect(status().isUnauthorized());
  }

  @Test
  void accept_anonymous_returns401() throws Exception {
    mvc.perform(
            post("/api/v1/adaptation/pending-changes/{id}/accept", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedOptimisticVersion\":0}"))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- list + get ----------------

  @Test
  void listForUser_returnsOnlyCallersRankedPending() throws Exception {
    AuthedUser user = registerUser();
    PendingChange mine = seedPending(user.userId(), PendingChangeStatus.PENDING, plusDays(5));
    seedPending(UUID.randomUUID(), PendingChangeStatus.PENDING, plusDays(5));

    mvc.perform(get("/api/v1/adaptation/pending-changes").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()", is(1)))
        .andExpect(jsonPath("$[0].id", is(mine.getId().toString())))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getById_ownedRow_returns200_andContract() throws Exception {
    AuthedUser user = registerUser();
    PendingChange pc = seedPending(user.userId(), PendingChangeStatus.PENDING, plusDays(5));

    mvc.perform(get("/api/v1/adaptation/pending-changes/{id}", pc.getId()).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(pc.getId().toString())))
        .andExpect(jsonPath("$.status", is("PENDING")))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getById_otherUsersRow_returns404_problemJson() throws Exception {
    AuthedUser user = registerUser();
    PendingChange other = seedPending(UUID.randomUUID(), PendingChangeStatus.PENDING, plusDays(5));

    mvc.perform(get("/api/v1/adaptation/pending-changes/{id}", other.getId()).cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void getById_missingRow_returns404() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/adaptation/pending-changes/{id}", UUID.randomUUID()).cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---------------- accept ----------------

  @Test
  void accept_ownedPendingRow_returns200_persistsAccepted() throws Exception {
    AuthedUser user = registerUser();
    PendingChange pc = seedPending(user.userId(), PendingChangeStatus.PENDING, plusDays(5));
    when(recipeWriteApi.saveAdaptedVersion(any(SaveAdaptedVersionCommand.class)))
        .thenReturn(versionDto(pc.getBaseBranchId()));

    mvc.perform(
            post("/api/v1/adaptation/pending-changes/{id}/accept", pc.getId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedOptimisticVersion\":" + pc.getOptimisticVersion() + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("ACCEPTED")))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(pendingChangeRepository.findById(pc.getId()).orElseThrow().getStatus())
        .isEqualTo(PendingChangeStatus.ACCEPTED);
  }

  @Test
  void accept_nonPendingRow_returns422_problemJson() throws Exception {
    AuthedUser user = registerUser();
    PendingChange pc = seedPending(user.userId(), PendingChangeStatus.REJECTED, plusDays(5));

    mvc.perform(
            post("/api/v1/adaptation/pending-changes/{id}/accept", pc.getId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedOptimisticVersion\":" + pc.getOptimisticVersion() + "}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void accept_otherUsersRow_returns404() throws Exception {
    AuthedUser user = registerUser();
    PendingChange other = seedPending(UUID.randomUUID(), PendingChangeStatus.PENDING, plusDays(5));

    mvc.perform(
            post("/api/v1/adaptation/pending-changes/{id}/accept", other.getId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedOptimisticVersion\":0}"))
        .andExpect(status().isNotFound());
  }

  // ---------------- reject ----------------

  @Test
  void reject_ownedPendingRow_returns200_persistsRejected() throws Exception {
    AuthedUser user = registerUser();
    PendingChange pc = seedPending(user.userId(), PendingChangeStatus.PENDING, plusDays(5));

    mvc.perform(
            post("/api/v1/adaptation/pending-changes/{id}/reject", pc.getId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonNote\":\"too salty for me\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("REJECTED")))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(pendingChangeRepository.findById(pc.getId()).orElseThrow().getStatus())
        .isEqualTo(PendingChangeStatus.REJECTED);
  }

  @Test
  void reject_emptyBody_isAccepted_optionalReasonNote() throws Exception {
    AuthedUser user = registerUser();
    PendingChange pc = seedPending(user.userId(), PendingChangeStatus.PENDING, plusDays(5));

    mvc.perform(
            post("/api/v1/adaptation/pending-changes/{id}/reject", pc.getId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("REJECTED")));
  }

  // ---------------- pending history ----------------

  @Test
  void pendingHistory_returnsPagedListForRecipe() throws Exception {
    AuthedUser user = registerUser();
    PendingChange pc = seedPending(user.userId(), PendingChangeStatus.PENDING, plusDays(5));

    mvc.perform(
            get("/api/v1/adaptation/recipes/{recipeId}/pending-history", pc.getRecipeId())
                .cookie(user.cookie())
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void pendingHistory_anonymous_returns401() throws Exception {
    mvc.perform(get("/api/v1/adaptation/recipes/{recipeId}/pending-history", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  // ---------------- helpers ----------------

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    RegisterRequest body = AuthTestData.registerRequest("pc-" + AuthTestData.shortId());
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

  private static Instant plusDays(int days) {
    return Instant.now().plus(days, ChronoUnit.DAYS);
  }

  private PendingChange seedPending(UUID userId, PendingChangeStatus status, Instant expiresAt) {
    AdaptationJob job =
        jobRepository.saveAndFlush(
            AdaptationJob.builder()
                .id(UUID.randomUUID())
                .recipeId(UUID.randomUUID())
                .userId(userId)
                .catalogue(Catalogue.USER)
                .source(JobSource.FEEDBACK)
                .priority(JobPriority.SYNC)
                .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
                .status(JobStatus.RUNNING)
                .inputs(JsonNodeFactory.instance.objectNode())
                .traceId(UUID.randomUUID())
                .enqueuedAt(Instant.now())
                .build());
    return pendingChangeRepository.saveAndFlush(
        PendingChange.builder()
            .id(UUID.randomUUID())
            .recipeId(UUID.randomUUID())
            .userId(userId)
            .jobId(job.getId())
            .traceId(UUID.randomUUID())
            .changeDimension(ChangeDimension.SALT_LEVEL)
            .proposedDiff(JsonNodeFactory.instance.objectNode())
            .proposedClassification(AdaptationClassification.VERSION)
            .baseVersionId(UUID.randomUUID())
            .baseBranchId(UUID.randomUUID())
            .reasoning("seed reasoning")
            .confidence(new BigDecimal("0.750"))
            .impactScore(new BigDecimal("0.500"))
            .promptTemplateVersion("v1")
            .status(status)
            .createdAt(Instant.now().minus(1, ChronoUnit.HOURS))
            .expiresAt(expiresAt)
            .build());
  }

  private static RecipeVersionDto versionDto(UUID branchId) {
    return new RecipeVersionDto(
        UUID.randomUUID(),
        branchId,
        2,
        UUID.randomUUID(),
        VersionTrigger.ADAPTATION_PIPELINE,
        "adapted",
        "pending",
        Instant.now(),
        "system",
        UUID.randomUUID(),
        List.of(),
        List.of(),
        null,
        null,
        List.of());
  }
}
