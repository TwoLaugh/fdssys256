package com.example.mealprep.planner;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument.ProposedSlotChange;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
 * Complements {@code PlansControllerIT}/{@code PlansControllerReadIT}: the 4xx + idempotency edges
 * those leave uncovered — {@code authPlan}'s 404 (plan missing before the 403 auth check), {@code
 * GET /{planId}} 200/404, revert's 400 problem+json body, the re-opt-suggestion not-found and
 * cross-plan-mismatch 404s, the already-ACCEPTED re-accept idempotent replay, the already-REJECTED
 * re-reject idempotent replay, and accept-suggestion on a non-reoptable (SUPERSEDED) plan → 400.
 * Plans are seeded directly through {@link PlanRepository} (no composer / async runner racing the
 * assertion), exactly as in {@code PlansControllerIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class PlansControllerEdgeCasesIT {

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

  // HouseholdServiceImpl implements HouseholdQueryService + HouseholdUpdateService +
  // HouseholdMergeService; @MockBean on one evicts the single shared impl (wave-3 retro:
  // multi-interface @Service @MockBean eviction). Mock the siblings too so the context loads.
  @MockBean
  private com.example.mealprep.household.domain.service.HouseholdUpdateService
      householdUpdateService;

  @MockBean
  private com.example.mealprep.household.domain.service.HouseholdMergeService householdMergeService;

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
    jdbcTemplate.update("DELETE FROM decision_log");
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

  private static LocalDate mondayWeek() {
    return LocalDate.now().plusYears(33).with(java.time.DayOfWeek.MONDAY);
  }

  private Plan seed(UUID householdId, PlanStatus status) {
    Plan plan = PlanTestData.newPlanGraph(householdId, mondayWeek(), 1, status, 1, 2);
    tx().executeWithoutResult(t -> planRepository.save(plan));
    return plan;
  }

  private MealPrepPlanReoptSuggestion seedSuggestion(
      UUID planId, UUID changedSlotId, UUID newRecipeId, ReoptSuggestionStatus status) {
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
                            newRecipeId,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            3,
                            "better score"))))
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(86_400))
            .swept(false)
            .build();
    tx().executeWithoutResult(t -> suggestionRepository.save(s));
    return s;
  }

  // ---- authPlan: 404 before the 403 (plan missing) -------------------------------------------

  @Test
  void accept_returns404_whenPlanMissing() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(post("/api/v1/plans/{id}/accept", UUID.randomUUID()).cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/plan-not-found"));
  }

  // ---- GET /{planId} --------------------------------------------------------------------------

  @Test
  void getPlanById_returns200_withHydratedAggregate() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    Plan plan = seed(household, PlanStatus.ACTIVE);

    mvc.perform(get("/api/v1/plans/{id}", plan.getId()).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(plan.getId().toString()))
        .andExpect(jsonPath("$.days.length()").value(1))
        .andExpect(jsonPath("$.days[0].slots.length()").value(2))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getPlanById_returns404_whenMissing() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(get("/api/v1/plans/{id}", UUID.randomUUID()).cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/plan-not-found"));
  }

  // ---- revert 400 problem body ----------------------------------------------------------------

  @Test
  void revert_returns400ProblemJson_whenNotActive() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.GENERATED);

    mvc.perform(post("/api/v1/plans/{id}/revert", plan.getId()).cookie(user.cookie()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/plan-not-reoptable"));
  }

  // ---- slot-state 404 (unknown slot) ----------------------------------------------------------

  @Test
  void slotState_returns404_whenSlotUnknown() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    String body = objectMapper.writeValueAsString(java.util.Map.of("newState", "COOKING"));

    mvc.perform(
            patch("/api/v1/plans/{id}/slots/{slotId}/state", plan.getId(), UUID.randomUUID())
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/meal-slot-not-found"));
  }

  // ---- re-opt suggestion not-found + cross-plan mismatch --------------------------------------

  @Test
  void reoptSuggestionAccept_returns404_whenSuggestionMissing() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);

    mvc.perform(
            post(
                    "/api/v1/plans/{id}/reopt-suggestions/{sid}/accept",
                    plan.getId(),
                    UUID.randomUUID())
                .cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/reopt-suggestion-not-found"));
  }

  @Test
  void reoptSuggestionReject_returns404_whenSuggestionBelongsToAnotherPlan() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    // Suggestion is keyed to a DIFFERENT (but real, FK-satisfying) plan.
    Plan otherPlan = seed(UUID.randomUUID(), PlanStatus.ACTIVE);
    MealPrepPlanReoptSuggestion s =
        seedSuggestion(
            otherPlan.getId(),
            otherPlan.getDays().get(0).getSlots().get(0).getId(),
            UUID.randomUUID(),
            ReoptSuggestionStatus.PENDING);

    mvc.perform(
            post("/api/v1/plans/{id}/reopt-suggestions/{sid}/reject", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  // ---- idempotent re-accept / re-reject -------------------------------------------------------

  @Test
  void reoptSuggestionAccept_alreadyAccepted_isIdempotent_noSecondSupersede() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    MealPrepPlanReoptSuggestion s =
        seedSuggestion(plan.getId(), slotId, UUID.randomUUID(), ReoptSuggestionStatus.ACCEPTED);

    mvc.perform(
            post("/api/v1/plans/{id}/reopt-suggestions/{sid}/accept", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    // Plan was NOT superseded by the idempotent replay (it stayed ACTIVE).
    assertThat(planRepository.findById(plan.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.ACTIVE);
  }

  @Test
  void reoptSuggestionReject_alreadyRejected_isIdempotent() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    MealPrepPlanReoptSuggestion s =
        seedSuggestion(plan.getId(), slotId, UUID.randomUUID(), ReoptSuggestionStatus.REJECTED);

    mvc.perform(
            post("/api/v1/plans/{id}/reopt-suggestions/{sid}/reject", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"));
  }

  // ---- accept-suggestion onto a non-reoptable (SUPERSEDED) plan -> 400 ------------------------

  @Test
  void reoptSuggestionAccept_returns400_whenPlanNotReoptable() throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.SUPERSEDED);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    MealPrepPlanReoptSuggestion s =
        seedSuggestion(plan.getId(), slotId, UUID.randomUUID(), ReoptSuggestionStatus.PENDING);

    mvc.perform(
            post("/api/v1/plans/{id}/reopt-suggestions/{sid}/accept", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/plan-not-reoptable"));
  }

  // ---- accept-suggestion success: real ScheduledRecipe mutation onto a fresh generation ------

  @Test
  void reoptSuggestionAccept_appliesChangeOntoNewGeneration_andSupersedesOriginal()
      throws Exception {
    AuthedUser user = registerUser();
    UUID household = UUID.randomUUID();
    grantMembership(household, user.userId());
    Plan plan = seed(household, PlanStatus.ACTIVE);
    UUID slotId = plan.getDays().get(0).getSlots().get(0).getId();
    UUID newRecipeId = UUID.randomUUID();
    MealPrepPlanReoptSuggestion s =
        seedSuggestion(plan.getId(), slotId, newRecipeId, ReoptSuggestionStatus.PENDING);

    mvc.perform(
            post("/api/v1/plans/{id}/reopt-suggestions/{sid}/accept", plan.getId(), s.getId())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));

    assertThat(suggestionRepository.findById(s.getId()).orElseThrow().getStatus())
        .isEqualTo(ReoptSuggestionStatus.ACCEPTED);
    assertThat(planRepository.findById(plan.getId()).orElseThrow().getStatus())
        .isEqualTo(PlanStatus.SUPERSEDED);
    // A fresh GENERATED generation copy exists carrying the proposed recipe change.
    tx().executeWithoutResult(
            t -> {
              Plan copy =
                  planRepository
                      .findByHouseholdIdAndStatusIn(household, List.of(PlanStatus.GENERATED))
                      .stream()
                      .findFirst()
                      .orElseThrow();
              boolean applied =
                  copy.getDays().stream()
                      .flatMap(d -> d.getSlots().stream())
                      .anyMatch(
                          sl ->
                              sl.getScheduledRecipe() != null
                                  && newRecipeId.equals(sl.getScheduledRecipe().getRecipeId()));
              assertThat(applied).isTrue();
            });
  }
}
