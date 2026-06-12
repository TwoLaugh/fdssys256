package com.example.mealprep.planner;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * revert / slot-state / reopt-suggestion accept+reject), the re-opt suggestion detail GET
 * (frontend-gaps/planner-reopt-suggestion-detail: pre-accept diff preview, side-effect-free), plus
 * the auth surface (401 anon, 403 cross-household). Plans are seeded directly through {@link
 * PlanRepository} for the lifecycle paths (no composer, no async runner racing assertions); the
 * generate path drives the real controller with the deterministic composition stages
 * {@code @MockBean}ed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
// Generate path mocks the context builder (empty pool) → disable the recipe-pool Tier-2 cold-start
// gate so it does not fire on the empty pool and invoke the discovery runner (out of scope for the
// controller-surface tests; the gate has its own coverage in ColdStartGateTest /
// PlannerColdStartIT).
@org.springframework.test.context.TestPropertySource(
    properties = "mealprep.planner.cold-start.enabled=false")
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

  // HouseholdServiceImpl implements HouseholdQueryService, HouseholdUpdateService AND
  // HouseholdMergeService; @MockBean on one evicts the single shared impl, so the other two
  // interfaces lose their bean and HouseholdModule fails to wire (wave-3 retro: multi-interface
  // @Service @MockBean eviction). Mock the siblings too so the full context loads.
  @MockBean
  private com.example.mealprep.household.domain.service.HouseholdUpdateService
      householdUpdateService;

  @MockBean
  private com.example.mealprep.household.domain.service.HouseholdMergeService householdMergeService;

  @MockBean private PlanCompositionContextBuilder contextBuilder;
  @MockBean private BeamSearchEngine beamSearchEngine;
  @MockBean private RollupBuilder rollupBuilder;
  @MockBean private StageCInvoker stageCInvoker;
  @MockBean private Phase2Augmenter phase2Augmenter;

  @MockBean
  private com.example.mealprep.planner.domain.service.internal.composer.ConstraintFeasibilityCheck
      feasibilityCheck;

  @MockBean
  private com.example.mealprep.adaptation.domain.service.AdaptationService adaptationService;

  // AdaptationServiceImpl implements BOTH AdaptationService and AdaptationQueryService; @MockBean
  // on
  // one interface evicts the single shared impl bean, leaving AdaptationAdminController unable to
  // wire AdaptationQueryService → context-load failure (wave-3 retro: multi-interface @Service
  // @MockBean eviction). Mock the sibling interface too so the full context still loads.
  @MockBean
  private com.example.mealprep.adaptation.domain.service.AdaptationQueryService
      adaptationQueryService;

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
    // planner-01l: lifecycle transitions now write decision_log rows. Single DELETE (self-FK).
    jdbcTemplate.update("DELETE FROM decision_log");
    jdbcTemplate.update("DELETE FROM core_lock_leases");
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
    return seedGen(householdId, status, 1);
  }

  /** Seed a plan at a specific generation for the shared {@link #mondayWeek()} (1 day, 2 slots). */
  private Plan seedGen(UUID householdId, PlanStatus status, int generation) {
    Plan plan = PlanTestData.newPlanGraph(householdId, mondayWeek(), generation, status, 1, 2);
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
  void revertToHistorical_copiesTargetContent_supersedesActive_createsNewGeneration()
      throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    // Historical target (an older generation the user picks) + the current active plan.
    Plan target = seedGen(household, PlanStatus.SUPERSEDED, 1);
    Plan active = seedGen(household, PlanStatus.ACTIVE, 2);

    String body =
        objectMapper.writeValueAsString(
            java.util.Map.of("targetHistoricalPlanId", target.getId().toString()));

    mvc.perform(
            post("/api/v1/plans/revert")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("GENERATED"))
        // generation = 1 + count(target gen1, active gen2) = 3
        .andExpect(jsonPath("$.generation").value(3))
        // content copied from the TARGET (1 day, 2 slots — see seedGen)
        .andExpect(jsonPath("$.days.length()").value(1))
        .andExpect(jsonPath("$.days[0].slots.length()").value(2))
        .andExpect(openApi().isValid(openApiValidator));

    // The prior active plan is superseded; the target itself is untouched (immutability).
    assertThat(planRepository.findById(active.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.SUPERSEDED);
    assertThat(planRepository.findById(target.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.SUPERSEDED);
  }

  @Test
  void revertToHistorical_returns422_whenTargetNotInCallerHousehold() throws Exception {
    AuthedUser user = registerUser();
    UUID ownHousehold = UUID.randomUUID();
    grantMembership(ownHousehold, user.userId());
    // Target lives in a household the caller is NOT a member of → 422 (previously-dead exception).
    UUID foreignHousehold = UUID.randomUUID();
    Plan foreignTarget = seed(foreignHousehold, PlanStatus.SUPERSEDED);

    String body =
        objectMapper.writeValueAsString(
            java.util.Map.of("targetHistoricalPlanId", foreignTarget.getId().toString()));

    mvc.perform(
            post("/api/v1/plans/revert")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/revert-target-invalid"));
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
    return seedSuggestion(planId, changedSlotId, ReoptSuggestionStatus.PENDING);
  }

  private MealPrepPlanReoptSuggestion seedSuggestion(
      UUID planId, UUID changedSlotId, ReoptSuggestionStatus status) {
    MealPrepPlanReoptSuggestion s =
        MealPrepPlanReoptSuggestion.builder()
            .id(UUID.randomUUID())
            .planId(planId)
            .triggerKind(ReoptTriggerKind.USER)
            .triggerEventId(UUID.randomUUID())
            .traceId(UUID.randomUUID())
            .summary("1 change")
            .status(status)
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
  // Re-opt suggestion detail GET (frontend-gaps/planner-reopt-suggestion-detail)
  // ============================================================================================

  private long decisionLogCount() {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM decision_log", Long.class);
  }

  @Test
  void reoptSuggestion_getDetail_returnsStoredDiff_sideEffectFree_andAcceptAppliesIt()
      throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    var originalSlot = plan.getDays().get(0).getSlots().get(0);
    MealPrepPlanReoptSuggestion s = seedSuggestion(plan.getId(), originalSlot.getId());
    long decisionRowsBefore = decisionLogCount();
    long versionBefore = suggestionRepository.findById(s.getId()).orElseThrow().getVersion();

    // Pre-accept diff preview: the stored proposedAssignments are exposed on the GET.
    MvcResult detail =
        mvc.perform(
                get("/api/v1/plans/{id}/reopt-suggestions/{sid}", plan.getId(), s.getId())
                    .cookie(user.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(s.getId().toString()))
            .andExpect(jsonPath("$.planId").value(plan.getId().toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.summary").value("1 change"))
            .andExpect(jsonPath("$.proposedAssignments.schemaVersion").value(1))
            .andExpect(jsonPath("$.proposedAssignments.changes.length()").value(1))
            .andExpect(
                jsonPath("$.proposedAssignments.changes[0].slotId")
                    .value(originalSlot.getId().toString()))
            .andExpect(jsonPath("$.proposedAssignments.changes[0].newRecipeId").isNotEmpty())
            .andExpect(jsonPath("$.proposedAssignments.changes[0].newServings").value(2))
            .andExpect(jsonPath("$.proposedAssignments.changes[0].reason").value("better score"))
            .andExpect(openApi().isValid(openApiValidator))
            .andReturn();

    // Side-effect-free: no decision-log row, no status flip, no version bump.
    assertThat(decisionLogCount()).isEqualTo(decisionRowsBefore);
    MealPrepPlanReoptSuggestion afterGet = suggestionRepository.findById(s.getId()).orElseThrow();
    assertThat(afterGet.getStatus()).isEqualTo(ReoptSuggestionStatus.PENDING);
    assertThat(afterGet.getVersion()).isEqualTo(versionBefore);

    // The previewed diff is exactly what accept then applies (no double-write of the proposal).
    UUID previewedNewRecipeId =
        UUID.fromString(
            objectMapper
                .readTree(detail.getResponse().getContentAsString())
                .at("/proposedAssignments/changes/0/newRecipeId")
                .asText());
    mvc.perform(
            post("/api/v1/plans/{id}/reopt-suggestions/{sid}/accept", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    int changedSlotIndex = originalSlot.getSlotIndex();
    UUID appliedRecipeId =
        tx().execute(
                t -> {
                  Plan newGeneration =
                      planRepository
                          .findByHouseholdIdAndStatusIn(household, List.of(PlanStatus.GENERATED))
                          .get(0);
                  return newGeneration.getDays().get(0).getSlots().stream()
                      .filter(slot -> slot.getSlotIndex() == changedSlotIndex)
                      .findFirst()
                      .orElseThrow()
                      .getScheduledRecipe()
                      .getRecipeId();
                });
    assertThat(appliedRecipeId).isEqualTo(previewedNewRecipeId);
  }

  @Test
  void reoptSuggestion_getDetail_returns200_forDecidedSuggestion() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    MealPrepPlanReoptSuggestion s =
        seedSuggestion(plan.getId(), slotId, ReoptSuggestionStatus.EXPIRED);

    // Terminal statuses stay readable (history / back navigation), diff included.
    mvc.perform(
            get("/api/v1/plans/{id}/reopt-suggestions/{sid}", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"))
        .andExpect(jsonPath("$.proposedAssignments.changes.length()").value(1))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void reoptSuggestion_getDetail_returns404_whenSuggestionBelongsToAnotherPlan() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan planA = seedGen(household, PlanStatus.ACTIVE, 1);
    Plan planB = seedGen(household, PlanStatus.GENERATED, 2);
    UUID slotId = planA.getDays().get(0).getSlots().get(0).getId();
    MealPrepPlanReoptSuggestion s = seedSuggestion(planA.getId(), slotId);

    mvc.perform(
            get("/api/v1/plans/{id}/reopt-suggestions/{sid}", planB.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/reopt-suggestion-not-found"));
  }

  @Test
  void reoptSuggestion_getDetail_returns404_whenSuggestionUnknown() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);

    mvc.perform(
            get("/api/v1/plans/{id}/reopt-suggestions/{sid}", plan.getId(), UUID.randomUUID())
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void reoptSuggestion_getDetail_returns403_whenCrossHousehold() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    Plan plan = seed(household, PlanStatus.ACTIVE);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    MealPrepPlanReoptSuggestion s = seedSuggestion(plan.getId(), slotId);
    // Caller is NOT a member of the plan's household.
    when(householdQueryService.getById(any()))
        .thenReturn(
            Optional.of(
                new HouseholdDto(household, "h", UUID.randomUUID(), List.of(), Instant.now(), 0L)));

    mvc.perform(
            get("/api/v1/plans/{id}/reopt-suggestions/{sid}", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isForbidden());
  }

  @Test
  void reoptSuggestion_getDetail_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            get("/api/v1/plans/{id}/reopt-suggestions/{sid}", UUID.randomUUID(), UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
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

  // ============================================================================================
  // Feasibility (planner-6) — contract test against the OpenAPI validator
  // ============================================================================================

  @Test
  void feasibility_returns200_infeasibleWithConflictsAndResolutions_validatesAgainstOpenApi()
      throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    UUID slotId = UUID.randomUUID();
    when(feasibilityCheck.check(any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.FeasibilityCheckResultDto(
                false,
                List.of(
                    new com.example.mealprep.planner.api.dto.ConstraintConflictDto(
                        com.example.mealprep.planner.api.dto.ConflictType
                            .OVER_SPECIFIED_PREFERENCES,
                        List.of(slotId),
                        "1 slot has a candidate pool below the planning minimum.")),
                List.of(
                    new com.example.mealprep.planner.api.dto.ResolutionOptionDto(
                        "widen_preferences",
                        "Widen soft preferences so more recipes qualify.",
                        1,
                        new BigDecimal("0.15")))));

    mvc.perform(
            get("/api/v1/plans/feasibility")
                .cookie(user.cookie())
                .param("householdId", household.toString())
                .param("weekStartDate", mondayWeek().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.feasible").value(false))
        .andExpect(jsonPath("$.conflicts[0].type").value("OVER_SPECIFIED_PREFERENCES"))
        .andExpect(jsonPath("$.resolutions[0].key").value("widen_preferences"))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void feasibility_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            get("/api/v1/plans/feasibility")
                .param("householdId", UUID.randomUUID().toString())
                .param("weekStartDate", mondayWeek().toString()))
        .andExpect(status().isUnauthorized());
  }
}
