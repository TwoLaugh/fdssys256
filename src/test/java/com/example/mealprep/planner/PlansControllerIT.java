package com.example.mealprep.planner;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.api.dto.BeamSearchOutcome;
import com.example.mealprep.planner.api.dto.CandidatePlan;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument.ProposedSlotChange;
import com.example.mealprep.planner.api.dto.RecipePoolSnapshot;
import com.example.mealprep.planner.api.dto.ScoreResult;
import com.example.mealprep.planner.api.dto.SlotAssignment;
import com.example.mealprep.planner.api.dto.StageCResult;
import com.example.mealprep.planner.domain.entity.AugmentationSource;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.beamsearch.BeamSearchEngine;
import com.example.mealprep.planner.domain.service.internal.composer.PlanCompositionContextBuilder;
import com.example.mealprep.planner.domain.service.internal.rollup.RollupBuilder;
import com.example.mealprep.planner.domain.service.internal.stagec.Augmentation;
import com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter;
import com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Full HTTP cycle over planner-01j's 8 write endpoints (generate / accept / reject / abandon /
 * revert / slot-state / reopt-suggestion accept+reject) plus the auth surface (401 anon, 403
 * cross-household). Plans are seeded directly through {@link PlanRepository} for the lifecycle
 * paths (no composer, no async runner racing assertions); the generate path drives the real
 * controller with the deterministic composition stages {@code @MockBean}ed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class PlansControllerIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private AuthProperties authProperties;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlanRepository planRepository;
  @Autowired private MealPrepPlanReoptSuggestionRepository suggestionRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockBean private HouseholdQueryService householdQueryService;
  @MockBean private PlanCompositionContextBuilder contextBuilder;
  @MockBean private BeamSearchEngine beamSearchEngine;
  @MockBean private RollupBuilder rollupBuilder;
  @MockBean private StageCInvoker stageCInvoker;
  @MockBean private Phase2Augmenter phase2Augmenter;

  @MockBean
  private com.example.mealprep.adaptation.domain.service.AdaptationService adaptationService;

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM planner_plan_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_reopt_suggestions");
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM planner_plans");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
  }

  private record AuthedUser(UUID userId, Cookie cookie) {}

  private AuthedUser registerUser() throws Exception {
    String username = "planner-" + AuthTestData.shortId();
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

  /** Make {@code userId} a member of {@code householdId} for PlannerAuth. */
  private void grantMembership(UUID householdId, UUID userId) {
    HouseholdMemberDto member =
        new HouseholdMemberDto(
            UUID.randomUUID(),
            householdId,
            userId,
            com.example.mealprep.household.domain.entity.HouseholdRole.primary,
            "owner",
            0,
            Instant.now(),
            0L);
    when(householdQueryService.getById(eq(householdId)))
        .thenReturn(
            Optional.of(
                new HouseholdDto(householdId, "h", userId, List.of(member), Instant.now(), 0L)));
  }

  private Plan seed(UUID householdId, PlanStatus status) {
    Plan plan = PlanTestData.newPlanGraph(householdId, mondayWeek(), 1, status, 1, 2);
    tx().executeWithoutResult(t -> planRepository.save(plan));
    return plan;
  }

  private static LocalDate mondayWeek() {
    return LocalDate.now().plusYears(30).with(java.time.DayOfWeek.MONDAY);
  }

  // ============================================================================================
  // Auth
  // ============================================================================================

  @Test
  void accept_returns401_whenAnonymous() throws Exception {
    mvc.perform(post("/api/v1/plans/{id}/accept", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void accept_returns403_whenCrossHousehold() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    Plan plan = seed(household, PlanStatus.GENERATED);
    // user is NOT a member: empty household membership.
    when(householdQueryService.getById(any()))
        .thenReturn(
            Optional.of(
                new HouseholdDto(household, "h", UUID.randomUUID(), List.of(), Instant.now(), 0L)));

    mvc.perform(post("/api/v1/plans/{id}/accept", plan.getId()).cookie(user.cookie()))
        .andExpect(status().isForbidden());
  }

  // ============================================================================================
  // Lifecycle
  // ============================================================================================

  @Test
  void accept_transitionsGeneratedToActive() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.GENERATED);

    mvc.perform(post("/api/v1/plans/{id}/accept", plan.getId()).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(openApi().isValid(openApiValidator));

    assertThat(planRepository.findById(plan.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.ACTIVE);
  }

  @Test
  void accept_returns409_whenNotGenerated() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);

    mvc.perform(post("/api/v1/plans/{id}/accept", plan.getId()).cookie(user.cookie()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/invalid-plan-state-transition"));
  }

  @Test
  void reject_isIdempotent() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.GENERATED);
    String body =
        objectMapper.writeValueAsString(
            new java.util.HashMap<>(java.util.Map.of("reason", "no thanks")));

    mvc.perform(
            post("/api/v1/plans/{id}/reject", plan.getId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    // Re-reject: still 200 (idempotent), not 409.
    mvc.perform(
            post("/api/v1/plans/{id}/reject", plan.getId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  @Test
  void abandon_transitionsActiveToAbandoned() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    String body = objectMapper.writeValueAsString(java.util.Map.of("reason", "holiday"));

    mvc.perform(
            post("/api/v1/plans/{id}/abandon", plan.getId())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ABANDONED"));
  }

  @Test
  void revert_supersedesActive_andCreatesNewGeneration() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);

    mvc.perform(post("/api/v1/plans/{id}/revert", plan.getId()).cookie(user.cookie()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("GENERATED"))
        .andExpect(jsonPath("$.generation").value(2));

    assertThat(planRepository.findById(plan.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.SUPERSEDED);
  }

  @Test
  void revert_returns400_whenNotActive() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.GENERATED);

    mvc.perform(post("/api/v1/plans/{id}/revert", plan.getId()).cookie(user.cookie()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void slotState_transitionsPlannedToCooking() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    String body = objectMapper.writeValueAsString(java.util.Map.of("newState", "COOKING"));

    mvc.perform(
            patch("/api/v1/plans/{id}/slots/{slotId}/state", plan.getId(), slotId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void slotState_returns409_onIllegalTransition() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    String body = objectMapper.writeValueAsString(java.util.Map.of("newState", "EATEN"));

    mvc.perform(
            patch("/api/v1/plans/{id}/slots/{slotId}/state", plan.getId(), slotId)
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  // ============================================================================================
  // Re-opt suggestions
  // ============================================================================================

  private MealPrepPlanReoptSuggestion seedSuggestion(UUID planId, UUID changedSlotId) {
    MealPrepPlanReoptSuggestion s =
        MealPrepPlanReoptSuggestion.builder()
            .id(UUID.randomUUID())
            .planId(planId)
            .triggerKind(ReoptTriggerKind.USER)
            .triggerEventId(UUID.randomUUID())
            .traceId(UUID.randomUUID())
            .summary("1 change")
            .status(ReoptSuggestionStatus.PENDING)
            .proposedAssignments(
                ProposedReoptAssignmentsDocument.of(
                    List.of(
                        new ProposedSlotChange(
                            changedSlotId,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            2,
                            "better score"))))
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(86_400))
            .swept(false)
            .build();
    tx().executeWithoutResult(t -> suggestionRepository.save(s));
    return s;
  }

  @Test
  void reoptSuggestion_accept_marksAcceptedAndSupersedes() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    MealPrepPlanReoptSuggestion s = seedSuggestion(plan.getId(), slotId);

    mvc.perform(
            post("/api/v1/plans/{id}/reopt-suggestions/{sid}/accept", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    assertThat(suggestionRepository.findById(s.getId()).orElseThrow().getStatus())
        .isEqualTo(ReoptSuggestionStatus.ACCEPTED);
    assertThat(planRepository.findById(plan.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.SUPERSEDED);
  }

  @Test
  void reoptSuggestion_reject_marksRejected_noPlanChange() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    MealPrepPlanReoptSuggestion s = seedSuggestion(plan.getId(), slotId);

    mvc.perform(
            post("/api/v1/plans/{id}/reopt-suggestions/{sid}/reject", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    assertThat(planRepository.findById(plan.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.ACTIVE);
  }

  // ============================================================================================
  // Generate (deterministic stages mocked) + Idempotency-Key
  // ============================================================================================

  private void wireGenerate(UUID household) {
    UUID slotId = UUID.randomUUID();
    PlanCompositionContext context =
        new PlanCompositionContext(
            household,
            mondayWeek(),
            List.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            null,
            null,
            null,
            new RecipePoolSnapshot(List.of(), Instant.now()),
            List.of(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            java.util.Map.of());
    SlotAssignment a =
        new SlotAssignment(
            UUID.randomUUID(),
            slotId,
            0,
            mondayWeek(),
            com.example.mealprep.core.types.SlotKind.DINNER,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            false);
    CandidatePlan candidate =
        new CandidatePlan(
            UUID.randomUUID(),
            mondayWeek(),
            List.of(a),
            new ScoreResult(BigDecimal.ONE, PlanTestData.zeroScoreBreakdown()));
    when(contextBuilder.build(any(), any(), any(), any())).thenReturn(context);
    when(beamSearchEngine.search(any(), any()))
        .thenReturn(new BeamSearchOutcome(List.of(candidate), false));
    when(rollupBuilder.build(any(), any())).thenReturn(PlanTestData.emptyRollup());
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(new StageCResult(0, "picked", AugmentationSource.LLM, false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.<Augmentation>of(), List.<Augmentation>of(), List.of()));
  }

  @Test
  void generate_returns201_andIdempotencyKeyReplayReturns200() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    wireGenerate(household);
    String reqBody =
        objectMapper.writeValueAsString(
            java.util.Map.of(
                "householdId", household.toString(),
                "weekStartDate", mondayWeek().toString(),
                "forceRegenerateIfActive", false));
    String key = "idem-" + UUID.randomUUID();

    MvcResult created =
        mvc.perform(
                post("/api/v1/plans/generate")
                    .cookie(user.cookie())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(reqBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("GENERATED"))
            .andReturn();
    String firstId =
        objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

    // Replay with the same key -> 200 + same body, not a second 201.
    mvc.perform(
            post("/api/v1/plans/generate")
                .cookie(user.cookie())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reqBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(firstId));
  }

  @Test
  void generate_returns403_whenNotHouseholdMember() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    when(householdQueryService.getById(any()))
        .thenReturn(
            Optional.of(
                new HouseholdDto(household, "h", UUID.randomUUID(), List.of(), Instant.now(), 0L)));
    String reqBody =
        objectMapper.writeValueAsString(
            java.util.Map.of(
                "householdId", household.toString(),
                "weekStartDate", mondayWeek().toString()));

    mvc.perform(
            post("/api/v1/plans/generate")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(reqBody))
        .andExpect(status().isForbidden());
  }
}
