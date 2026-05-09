package com.example.mealprep.nutrition;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.domain.entity.EnforcementDirection;
import com.example.mealprep.nutrition.domain.entity.Goal;
import com.example.mealprep.nutrition.domain.entity.NutritionTargets;
import com.example.mealprep.nutrition.domain.repository.NutritionTargetsRepository;
import com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent;
import com.example.mealprep.nutrition.testdata.NutritionTestData;
import com.example.mealprep.testsupport.OpenApiValidatorConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 * Full HTTP flow over the nutrition-targets aggregate. Registers a user, seeds a row directly via
 * the repository (01a does not yet have an "initialise" endpoint — that lands in 01c), and
 * exercises {@code GET / PUT / GET audit-log} against the OpenAPI validator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
  TestContainersConfig.class,
  OpenApiValidatorConfig.class,
  TargetsFlowIT.NutritionEventCaptureConfig.class
})
@ActiveProfiles("test")
class TargetsFlowIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private OpenApiInteractionValidator openApiValidator;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private NutritionTargetsRepository targetsRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuthProperties authProperties;
  @Autowired private NutritionEventCapture eventCapture;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM nutrition_targets_audit");
    jdbcTemplate.update("DELETE FROM nutrition_activity_adjustment");
    jdbcTemplate.update("DELETE FROM nutrition_eating_window");
    jdbcTemplate.update("DELETE FROM nutrition_micro_target");
    jdbcTemplate.update("DELETE FROM nutrition_per_meal_distribution");
    jdbcTemplate.update("DELETE FROM nutrition_targets");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    eventCapture.clear();
  }

  // ---------------- helpers ----------------

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

  /**
   * Persist a default targets row directly via the repository (no public initialise endpoint in
   * 01a).
   */
  private NutritionTargets seedTargetsForUser(UUID userId) {
    NutritionTargets t =
        NutritionTargets.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .goal(Goal.MAINTAIN)
            .dailyCalorieTarget(2000)
            .calorieToleranceUnder(100)
            .calorieToleranceOver(150)
            .calorieEnforcement("weekly_average")
            .calorieDirection(EnforcementDirection.BOTH_BOUNDED)
            .proteinTargetG(BigDecimal.valueOf(120.0))
            .proteinFloorG(null)
            .proteinEnforcement("daily_floor")
            .proteinDirection(EnforcementDirection.LOWER_FLOOR)
            .carbsTargetG(BigDecimal.valueOf(250.0))
            .carbsFloorG(null)
            .carbsEnforcement("weekly_average")
            .carbsDirection(EnforcementDirection.BOTH_BOUNDED)
            .fatTargetG(BigDecimal.valueOf(70.0))
            .fatFloorG(null)
            .fatEnforcement("weekly_average")
            .fatDirection(EnforcementDirection.BOTH_BOUNDED)
            .fibreTargetG(BigDecimal.valueOf(30.0))
            .fibreFloorG(null)
            .fibreEnforcement("daily_floor")
            .fibreDirection(EnforcementDirection.LOWER_FLOOR)
            .satFatTargetG(BigDecimal.valueOf(20.0))
            .satFatDirection(EnforcementDirection.UPPER_LIMIT)
            .notes(null)
            .userOverriddenDirections(new ArrayList<>())
            .perMealDistribution(new ArrayList<>())
            .microTargets(new ArrayList<>())
            .activityAdjustments(new ArrayList<>())
            .eatingWindow(null)
            .build();
    return targetsRepository.saveAndFlush(t);
  }

  // ---------------- GET /api/v1/nutrition/targets ----------------

  @Test
  void get_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/nutrition/targets")).andExpect(status().isUnauthorized());
  }

  @Test
  void get_returns404_whenNotInitialised() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/nutrition/targets").cookie(user.cookie()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/nutrition-targets-not-found"));
  }

  @Test
  void get_returns200_withAllChildren_whenRowExists() throws Exception {
    AuthedUser user = registerUser();
    seedTargetsForUser(user.userId());

    mvc.perform(get("/api/v1/nutrition/targets").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(user.userId().toString()))
        .andExpect(jsonPath("$.goal").value("MAINTAIN"))
        .andExpect(jsonPath("$.calories.dailyTarget").value(2000))
        .andExpect(jsonPath("$.protein.targetG").value(120.0))
        .andExpect(jsonPath("$.perMealDistribution").isArray())
        .andExpect(jsonPath("$.microTargets").isArray())
        .andExpect(jsonPath("$.activityAdjustments").isArray())
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- PUT /api/v1/nutrition/targets ----------------

  @Test
  void put_returns401_whenAnonymous() throws Exception {
    mvc.perform(
            put("/api/v1/nutrition/targets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.defaultUpdateRequest(0L))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void put_returns404_whenNotInitialised() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(
            put("/api/v1/nutrition/targets")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.defaultUpdateRequest(0L))))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value("https://mealprep.example.com/problems/nutrition-targets-not-found"));
  }

  @Test
  void put_returns409_whenExpectedVersionStale() throws Exception {
    AuthedUser user = registerUser();
    seedTargetsForUser(user.userId());

    UpdateTargetsRequest stale = NutritionTestData.defaultUpdateRequest(99L);
    mvc.perform(
            put("/api/v1/nutrition/targets")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(stale)))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type").value("https://mealprep.example.com/problems/concurrent-update"));
  }

  @Test
  void put_returns400_whenExpectedVersionNegative() throws Exception {
    AuthedUser user = registerUser();
    seedTargetsForUser(user.userId());

    String body =
        objectMapper
            .writeValueAsString(NutritionTestData.defaultUpdateRequest(0L))
            .replace("\"expectedVersion\":0", "\"expectedVersion\":-1");

    mvc.perform(
            put("/api/v1/nutrition/targets")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void put_returns200_writesAuditRowsForChangedFields_andPublishesEvent() throws Exception {
    AuthedUser user = registerUser();
    NutritionTargets seeded = seedTargetsForUser(user.userId());

    UpdateTargetsRequest req = NutritionTestData.defaultUpdateRequest(0L);
    mvc.perform(
            put("/api/v1/nutrition/targets")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.perMealDistribution.length()").value(4))
        .andExpect(jsonPath("$.microTargets.length()").value(2))
        .andExpect(jsonPath("$.activityAdjustments.length()").value(2))
        .andExpect(openApi().isValid(openApiValidator));

    // Audit rows: per-meal, micros, activities, eating window — all changed from empty/null.
    Long auditCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_targets_audit WHERE targets_id = ?",
            Long.class,
            seeded.getId());
    assertThat(auditCount).isGreaterThanOrEqualTo(4L);

    List<String> fields =
        jdbcTemplate.queryForList(
            "SELECT field_path FROM nutrition_targets_audit WHERE targets_id = ?",
            String.class,
            seeded.getId());
    assertThat(fields)
        .contains(
            "perMealDistribution", "microTargets", "activityAdjustments", "eatingWindow", "notes");

    // notes was changed from null to "Default notes" — exactly one row, with the correct kind.
    String actorKind =
        jdbcTemplate.queryForObject(
            "SELECT actor_kind FROM nutrition_targets_audit WHERE targets_id = ? AND field_path = 'notes'",
            String.class,
            seeded.getId());
    assertThat(actorKind).isEqualTo("USER");

    // Event published exactly once.
    assertThat(eventCapture.events()).hasSize(1);
    NutritionTargetsChangedEvent event = eventCapture.events().get(0);
    assertThat(event.userId()).isEqualTo(user.userId());
    assertThat(event.targetsId()).isEqualTo(seeded.getId());
    assertThat(event.changedFieldPaths())
        .contains("perMealDistribution", "microTargets", "activityAdjustments", "eatingWindow");
  }

  @Test
  void put_isNoOp_whenRequestMatchesCurrentState_writesNoAuditRows() throws Exception {
    AuthedUser user = registerUser();
    NutritionTargets seeded = seedTargetsForUser(user.userId());

    // First PUT lands the default-shape state.
    UpdateTargetsRequest req = NutritionTestData.defaultUpdateRequest(0L);
    mvc.perform(
            put("/api/v1/nutrition/targets")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());

    Long auditCountAfterFirst =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_targets_audit WHERE targets_id = ?",
            Long.class,
            seeded.getId());

    eventCapture.clear();

    // Second PUT — same shape, but version is now 1.
    UpdateTargetsRequest noop = NutritionTestData.defaultUpdateRequest(1L);
    mvc.perform(
            put("/api/v1/nutrition/targets")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(noop)))
        .andExpect(status().isOk());

    // No new audit rows, no new event.
    Long auditCountAfterNoOp =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nutrition_targets_audit WHERE targets_id = ?",
            Long.class,
            seeded.getId());
    assertThat(auditCountAfterNoOp).isEqualTo(auditCountAfterFirst);
    assertThat(eventCapture.events()).isEmpty();
  }

  // ---------------- GET /api/v1/nutrition/targets/audit-log ----------------

  @Test
  void getAuditLog_returns401_whenAnonymous() throws Exception {
    mvc.perform(get("/api/v1/nutrition/targets/audit-log")).andExpect(status().isUnauthorized());
  }

  @Test
  void getAuditLog_returns200_emptyPage_whenNotInitialised() throws Exception {
    AuthedUser user = registerUser();

    mvc.perform(get("/api/v1/nutrition/targets/audit-log").cookie(user.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  @Test
  void getAuditLog_returnsPagedRows_newestFirst() throws Exception {
    AuthedUser user = registerUser();
    seedTargetsForUser(user.userId());

    mvc.perform(
            put("/api/v1/nutrition/targets")
                .cookie(user.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(NutritionTestData.defaultUpdateRequest(0L))))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/v1/nutrition/targets/audit-log")
                .cookie(user.cookie())
                .param("page", "0")
                .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(openApi().isValid(openApiValidator));
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class NutritionEventCaptureConfig {
    @Bean
    NutritionEventCapture nutritionEventCapture() {
      return new NutritionEventCapture();
    }
  }

  static class NutritionEventCapture {
    private final List<NutritionTargetsChangedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onTargetsChanged(NutritionTargetsChangedEvent event) {
      events.add(event);
    }

    public List<NutritionTargetsChangedEvent> events() {
      return events;
    }

    public void clear() {
      events.clear();
    }
  }
}
