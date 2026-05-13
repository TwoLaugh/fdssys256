package com.example.mealprep.planner.domain.repository;

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
import com.example.mealprep.planner.domain.entity.Day;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.testdata.PlanTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.Comparator;
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
 * End-to-end HTTP flow over {@code GET /api/v1/plans/{planId}}. Seeds a plan aggregate via the
 * package-private {@link PlanRepository} (this IT lives in the same package), then asserts:
 *
 * <ul>
 *   <li>Anonymous request → 401;
 *   <li>Missing plan → 404 + ProblemDetail with {@code type=plan-not-found};
 *   <li>Existing plan → 200 with hydrated DTO; days ordered by date, slots by index, scheduled
 *       recipes present;
 *   <li>The lazy-load pattern doesn't trip {@code MultipleBagFetchException} or {@code
 *       LazyInitializationException} when serialising the response;
 *   <li>The JSONB carriers ({@code scoreBreakdown} / {@code rollupSummary}) round-trip through
 *       Hibernate + Jackson.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, OpenApiValidatorConfig.class})
@ActiveProfiles("test")
class PlannerFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private AuthProperties authProperties;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlanRepository planRepository;
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
    String username = "alice-" + AuthTestData.shortId();
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

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    UUID planId = UUID.randomUUID();
    mvc.perform(get("/api/v1/plans/{planId}", planId)).andExpect(status().isUnauthorized());
  }

  @Test
  void get_returns404_whenPlanMissing() throws Exception {
    AuthedUser user = registerUser();
    UUID missingId = UUID.randomUUID();
    mvc.perform(get("/api/v1/plans/{planId}", missingId).cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://mealprep.example.com/problems/plan-not-found"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void get_returns200_withHydratedAggregate_andOrderedChildren() throws Exception {
    AuthedUser user = registerUser();

    // Build an out-of-order graph: days inserted reverse-chronologically, slots reverse-indexed.
    // The mapper applies ordering — verify the response is sorted ascending.
    Plan plan = PlanTestData.newPlanGraph(LocalDate.of(2026, 5, 11), 3, 3);
    plan.getDays().sort(Comparator.comparing(Day::getOnDate).reversed());
    for (Day day : plan.getDays()) {
      day.getSlots().sort(Comparator.comparingInt(MealSlot::getSlotIndex).reversed());
    }

    UUID planId = plan.getId();
    txTemplate().executeWithoutResult(tx -> planRepository.save(plan));

    mvc.perform(get("/api/v1/plans/{planId}", planId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(planId.toString()))
        .andExpect(jsonPath("$.days.length()").value(3))
        // Days sorted ascending by date.
        .andExpect(jsonPath("$.days[0].date").value("2026-05-11"))
        .andExpect(jsonPath("$.days[1].date").value("2026-05-12"))
        .andExpect(jsonPath("$.days[2].date").value("2026-05-13"))
        // Slots sorted ascending by slotIndex.
        .andExpect(jsonPath("$.days[0].slots[0].slotIndex").value(0))
        .andExpect(jsonPath("$.days[0].slots[1].slotIndex").value(1))
        .andExpect(jsonPath("$.days[0].slots[2].slotIndex").value(2))
        // Each slot carries a scheduledRecipe.
        .andExpect(jsonPath("$.days[0].slots[0].scheduledRecipe.servings").value(2))
        // JSONB carriers were persisted and read back.
        .andExpect(jsonPath("$.scoreBreakdown.weightSchemeVersion").value("v1-uniform"))
        .andExpect(jsonPath("$.rollupSummary.weekly.kcalTotal").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  @Test
  void get_returns200_evenWithSlotMissingScheduledRecipe() throws Exception {
    AuthedUser user = registerUser();
    Plan plan = PlanTestData.newPlanGraph(LocalDate.of(2026, 5, 4), 1, 2);
    // Detach the scheduled recipe on the first slot — simulating an eating-out / fasting slot.
    plan.getDays().get(0).getSlots().get(0).setScheduledRecipe(null);

    UUID planId = plan.getId();
    txTemplate().executeWithoutResult(tx -> planRepository.save(plan));

    mvc.perform(get("/api/v1/plans/{planId}", planId).cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].slots[0].scheduledRecipe").doesNotExist())
        .andExpect(jsonPath("$.days[0].slots[1].scheduledRecipe.servings").value(2));
  }

  @Test
  void planRepository_findFirst_andCount_workForFixtureAsserts() {
    Plan plan = PlanTestData.newPlanGraph(LocalDate.of(2026, 6, 1), 1, 1);
    txTemplate().executeWithoutResult(tx -> planRepository.save(plan));

    assertThat(
            planRepository.findFirstByHouseholdIdAndWeekStartDate(
                plan.getHouseholdId(), plan.getWeekStartDate()))
        .isPresent();
    assertThat(
            planRepository.countByHouseholdIdAndWeekStartDate(
                plan.getHouseholdId(), plan.getWeekStartDate()))
        .isEqualTo(1);
  }

  @Test
  void cascade_deletePlan_removesAllChildren() {
    Plan plan = PlanTestData.newPlanGraph(LocalDate.of(2026, 7, 6), 2, 2);
    UUID planId = plan.getId();
    txTemplate().executeWithoutResult(tx -> planRepository.save(plan));

    Integer slotCountBefore =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM planner_meal_slots WHERE plan_id = ?", Integer.class, planId);
    assertThat(slotCountBefore).isEqualTo(4);

    txTemplate().executeWithoutResult(tx -> planRepository.deleteById(planId));

    Integer slotCountAfter =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM planner_meal_slots WHERE plan_id = ?", Integer.class, planId);
    assertThat(slotCountAfter).isZero();
    Integer scheduledRecipeCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM planner_scheduled_recipes WHERE slot_id IN ("
                + "SELECT id FROM planner_meal_slots WHERE plan_id = ?)",
            Integer.class,
            planId);
    assertThat(scheduledRecipeCount).isZero();
    List<UUID> dayIds =
        jdbcTemplate.queryForList(
            "SELECT id FROM planner_days WHERE plan_id = ?", UUID.class, planId);
    assertThat(dayIds).isEmpty();
  }
}
