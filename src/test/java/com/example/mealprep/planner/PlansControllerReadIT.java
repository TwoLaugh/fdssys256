package com.example.mealprep.planner;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestion;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.repository.ReoptSuggestionRepository;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * End-to-end HTTP flow over 01c's read endpoints (active / history / range / suggestions). Seeds
 * fixtures through the real {@link PlanRepository} and {@link ReoptSuggestionRepository}, then
 * asserts the HTTP/JSON surface: status codes, body shape, pagination metadata, sort order, and
 * OpenAPI contract validity.
 *
 * <p>The {@code MultipleBagFetchException} / {@code LazyInitializationException} traps from 01a's
 * deep aggregate are re-verified here for the new read paths — if the lazy-touch pattern in {@link
 * PlanQueryService} regresses, the hydrated-graph assertions blow up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class PlansControllerReadIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private AuthProperties authProperties;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlanRepository planRepository;
  @Autowired private ReoptSuggestionRepository reoptSuggestionRepository;
  @Autowired private PlanQueryService planQueryService;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate txTemplate() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
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

  private Plan persist(Plan plan) {
    txTemplate().executeWithoutResult(tx -> planRepository.save(plan));
    return plan;
  }

  private ReoptSuggestion persistSuggestion(
      UUID householdId, UUID planId, LocalDate week, ReoptStatus status) {
    ReoptSuggestion suggestion =
        ReoptSuggestion.builder()
            .id(UUID.randomUUID())
            .householdId(householdId)
            .weekStartDate(week)
            .planId(planId)
            .triggerKind(ReoptTriggerKind.USER)
            .affectedSlotIds(new ArrayList<>(List.of(UUID.randomUUID())))
            .summary("test-" + status)
            .status(status)
            .build();
    txTemplate().executeWithoutResult(tx -> reoptSuggestionRepository.save(suggestion));
    return suggestion;
  }

  // ============================================================================================
  // GET /api/v1/plans/active
  // ============================================================================================

  @Test
  void getActive_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            get("/api/v1/plans/active")
                .param("householdId", UUID.randomUUID().toString())
                .param("weekStartDate", "2026-05-11"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getActive_returns404_whenNoActivePlan() throws Exception {
    AuthedUser user = registerUser();
    UUID householdId = UUID.randomUUID();
    mvc.perform(
            get("/api/v1/plans/active")
                .param("householdId", householdId.toString())
                .param("weekStartDate", "2026-05-11")
                .cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/plan-not-found"));
  }

  @Test
  void getActive_returns404_whenOnlyGeneratedExists() throws Exception {
    AuthedUser user = registerUser();
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    persist(PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.GENERATED, 1, 1));

    mvc.perform(
            get("/api/v1/plans/active")
                .param("householdId", householdId.toString())
                .param("weekStartDate", week.toString())
                .cookie(user.cookie()))
        .andExpect(status().isNotFound());
  }

  @Test
  void getActive_returns200_withHydratedActivePlan() throws Exception {
    AuthedUser user = registerUser();
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    // Mix: gen 1 SUPERSEDED, gen 2 ACTIVE — getActive returns gen 2 only.
    persist(PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.SUPERSEDED, 2, 2));
    Plan active = persist(PlanTestData.newPlanGraph(householdId, week, 2, PlanStatus.ACTIVE, 3, 2));

    mvc.perform(
            get("/api/v1/plans/active")
                .param("householdId", householdId.toString())
                .param("weekStartDate", week.toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(active.getId().toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.generation").value(2))
        .andExpect(jsonPath("$.days.length()").value(3))
        .andExpect(jsonPath("$.days[0].slots.length()").value(2))
        .andExpect(jsonPath("$.days[0].slots[0].scheduledRecipe.servings").value(2))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getActive_returns400_whenWeekStartDateMissing() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/plans/active")
                .param("householdId", UUID.randomUUID().toString())
                .cookie(user.cookie()))
        .andExpect(status().isBadRequest());
  }

  // ============================================================================================
  // GET /api/v1/plans/history
  // ============================================================================================

  @Test
  void getHistory_returns200_emptyArray_whenNoPlans() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/plans/history")
                .param("householdId", UUID.randomUUID().toString())
                .param("weekStartDate", "2026-05-11")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getHistory_returnsAllGenerations_inDescendingOrder() throws Exception {
    AuthedUser user = registerUser();
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    persist(PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.SUPERSEDED, 1, 1));
    persist(PlanTestData.newPlanGraph(householdId, week, 2, PlanStatus.SUPERSEDED, 1, 1));
    persist(PlanTestData.newPlanGraph(householdId, week, 3, PlanStatus.ACTIVE, 1, 1));

    mvc.perform(
            get("/api/v1/plans/history")
                .param("householdId", householdId.toString())
                .param("weekStartDate", week.toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].generation").value(3))
        .andExpect(jsonPath("$[1].generation").value(2))
        .andExpect(jsonPath("$[2].generation").value(1));
  }

  // ============================================================================================
  // GET /api/v1/plans   (range)
  // ============================================================================================

  @Test
  void getRange_returns200_emptyPage_whenNoPlans() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/plans")
                .param("householdId", UUID.randomUUID().toString())
                .param("from", "2026-05-04")
                .param("to", "2026-05-18")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getRange_returnsPlans_sortedByWeekDesc_thenGenerationDesc() throws Exception {
    AuthedUser user = registerUser();
    UUID householdId = UUID.randomUUID();
    LocalDate w1 = LocalDate.of(2026, 5, 4);
    LocalDate w2 = LocalDate.of(2026, 5, 11);
    LocalDate w3 = LocalDate.of(2026, 5, 18);
    persist(PlanTestData.newPlanGraph(householdId, w1, 1, PlanStatus.SUPERSEDED, 1, 1));
    persist(PlanTestData.newPlanGraph(householdId, w2, 1, PlanStatus.SUPERSEDED, 1, 1));
    persist(PlanTestData.newPlanGraph(householdId, w2, 2, PlanStatus.ACTIVE, 1, 1));
    persist(PlanTestData.newPlanGraph(householdId, w3, 1, PlanStatus.GENERATED, 1, 1));

    mvc.perform(
            get("/api/v1/plans")
                .param("householdId", householdId.toString())
                .param("from", w1.toString())
                .param("to", w3.toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(4))
        .andExpect(jsonPath("$.content.length()").value(4))
        // Sort: weekStartDate DESC then generation DESC.
        .andExpect(jsonPath("$.content[0].weekStartDate").value(w3.toString()))
        .andExpect(jsonPath("$.content[1].weekStartDate").value(w2.toString()))
        .andExpect(jsonPath("$.content[1].generation").value(2))
        .andExpect(jsonPath("$.content[2].weekStartDate").value(w2.toString()))
        .andExpect(jsonPath("$.content[2].generation").value(1))
        .andExpect(jsonPath("$.content[3].weekStartDate").value(w1.toString()));
  }

  @Test
  void getRange_returns400_whenFromAfterTo() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/plans")
                .param("householdId", UUID.randomUUID().toString())
                .param("from", "2026-05-18")
                .param("to", "2026-05-04")
                .cookie(user.cookie()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void getRange_inclusive_whenFromEqualsTo() throws Exception {
    AuthedUser user = registerUser();
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    persist(PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.ACTIVE, 1, 1));

    mvc.perform(
            get("/api/v1/plans")
                .param("householdId", householdId.toString())
                .param("from", week.toString())
                .param("to", week.toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void getRange_returns400_whenSizeExceedsMax() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/plans")
                .param("householdId", UUID.randomUUID().toString())
                .param("from", "2026-05-04")
                .param("to", "2026-05-18")
                .param("size", "101")
                .cookie(user.cookie()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getRange_paginates() throws Exception {
    AuthedUser user = registerUser();
    UUID householdId = UUID.randomUUID();
    LocalDate base = LocalDate.of(2026, 5, 11);
    // 5 distinct weeks of single-generation plans
    for (int i = 0; i < 5; i++) {
      persist(
          PlanTestData.newPlanGraph(householdId, base.plusWeeks(i), 1, PlanStatus.ACTIVE, 1, 1));
    }

    mvc.perform(
            get("/api/v1/plans")
                .param("householdId", householdId.toString())
                .param("from", base.toString())
                .param("to", base.plusWeeks(4).toString())
                .param("page", "0")
                .param("size", "2")
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(5))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.number").value(0))
        .andExpect(jsonPath("$.content.length()").value(2));
  }

  // ============================================================================================
  // GET /api/v1/plans/suggestions
  // ============================================================================================

  @Test
  void getSuggestions_returns200_emptyPage_whenNoPending() throws Exception {
    AuthedUser user = registerUser();
    mvc.perform(
            get("/api/v1/plans/suggestions")
                .param("householdId", UUID.randomUUID().toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void getSuggestions_returnsOnlyPending_excludingResolvedStatuses() throws Exception {
    AuthedUser user = registerUser();
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    Plan plan = persist(PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.ACTIVE, 1, 1));
    persistSuggestion(householdId, plan.getId(), week, ReoptStatus.PENDING);
    persistSuggestion(householdId, plan.getId(), week, ReoptStatus.PENDING);
    persistSuggestion(householdId, plan.getId(), week, ReoptStatus.DISMISSED);
    persistSuggestion(householdId, plan.getId(), week, ReoptStatus.ACCEPTED);
    persistSuggestion(householdId, plan.getId(), week, ReoptStatus.EXPIRED);

    mvc.perform(
            get("/api/v1/plans/suggestions")
                .param("householdId", householdId.toString())
                .cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].status").value("PENDING"))
        .andExpect(jsonPath("$.content[1].status").value("PENDING"));
  }

  // ============================================================================================
  // In-process: getPlansByIds, getSuggestion — these have no REST surface, exercised via the bean
  // ============================================================================================

  @Test
  void getPlansByIds_returnsHydratedPlans_dropsUnknown() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    Plan p1 = persist(PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.ACTIVE, 1, 1));
    Plan p2 =
        persist(
            PlanTestData.newPlanGraph(householdId, week.plusWeeks(1), 1, PlanStatus.ACTIVE, 1, 1));
    UUID unknown = UUID.randomUUID();

    List<PlanDto> result = planQueryService.getPlansByIds(List.of(p1.getId(), unknown, p2.getId()));

    assertThat(result).hasSize(2);
    assertThat(result).extracting(PlanDto::id).containsExactlyInAnyOrder(p1.getId(), p2.getId());
  }

  @Test
  void getPlansByIds_emptyInput_returnsEmpty() {
    assertThat(planQueryService.getPlansByIds(List.of())).isEmpty();
  }

  @Test
  void getSuggestion_returnsOptionalEmpty_whenMissing() {
    assertThat(planQueryService.getSuggestion(UUID.randomUUID())).isEmpty();
  }

  @Test
  void getSuggestion_returnsHydratedDto_whenPresent() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    Plan plan = persist(PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.ACTIVE, 1, 1));
    ReoptSuggestion suggestion =
        persistSuggestion(householdId, plan.getId(), week, ReoptStatus.PENDING);

    assertThat(planQueryService.getSuggestion(suggestion.getId()))
        .hasValueSatisfying(
            dto -> {
              assertThat(dto.id()).isEqualTo(suggestion.getId());
              assertThat(dto.status()).isEqualTo(ReoptStatus.PENDING);
            });
  }
}
